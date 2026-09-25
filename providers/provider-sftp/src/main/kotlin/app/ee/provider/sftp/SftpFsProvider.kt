package app.ee.provider.sftp

import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.FsUri
import app.ee.core.fs.Progress
import app.ee.core.model.ConnectionSource
import app.ee.core.model.FsCapability
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import app.ee.core.model.NetConnection
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.SftpClient
import org.apache.sshd.sftp.SftpEntry
import org.apache.sshd.sftp.SftpOpenFlags
import org.apache.sshd.sftp.common.SftpException

/**
 * SFTP provider over Apache MINA SSHD (docs/02-specification.md P1-4).
 *
 * URI layout: `ee://sftp/<connectionId>/<absolute path without leading />` —
 * the connection profile's `path` is the base directory (default "/").
 *
 * M3 limitations (flagged): one session per connection for the process
 * lifetime (no reconnect on drop); whole-file writes (no resume); key-based
 * auth lands with M5 hardening (M3 is password auth).
 */
class SftpFsProvider(private val source: ConnectionSource) : FsProvider {

    override val type: FsType = FsType.SFTP

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
        FsCapability.STREAM,
    )

    private val sessions = mutableMapOf<String, ClientSession>()
    private val clients = mutableMapOf<String, SftpClient>()

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(uri)
        val conn = connection(connId)
        val path = resolve(conn, suffix)
        sftpFor(conn).stat(path)
        FsNode(
            id = uri,
            uri = uri,
            name = conn.name,
            providerType = FsType.SFTP,
            metadata = FsMetadata(isDirectory = true, extra = mapOf("connId" to conn.id)),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = resolve(conn, suffix)
        val entries = withContext(Dispatchers.IO) {
            try {
                sftpFor(conn).ls(path)
            } catch (e: SftpException) {
                throw FsException.Remote("SFTP list failed: ${e.message}", e)
            } catch (e: Exception) {
                throw FsException.NotConnected("SFTP not connected", e)
            }
        }
        for (entry in entries) {
            if (entry.filename == "." || entry.filename == "..") continue
            emit(childNode(conn, suffix, entry))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val attrs = try {
            sftpFor(conn).stat(resolve(conn, suffix))
        } catch (e: SftpException) {
            throw FsException.Remote("SFTP stat failed: ${e.message}", e)
        }
        node.metadata ?: FsMetadata(
            sizeBytes = if (attrs.isDirectory) null else attrs.size,
            modifiedAt = attrs.lastModifiedTime.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
            isDirectory = attrs.isDirectory,
        )
    }

    override fun open(node: FsNode): Source {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = resolve(conn, suffix)
        return try {
            sftpFor(conn).open(path, SftpOpenFlags.READ).source()
        } catch (e: SftpException) {
            throw FsException.Remote("SFTP open failed: ${e.message}", e)
        } catch (e: Exception) {
            throw FsException.NotConnected("SFTP not connected", e)
        }
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = resolve(conn, suffix)
        withContext(Dispatchers.IO) {
            bytes.use { src ->
                val out = try {
                    sftpFor(conn).open(path, SftpOpenFlags.CREATE, SftpOpenFlags.TRUNCATE, SftpOpenFlags.WRITE)
                } catch (e: SftpException) {
                    throw FsException.Remote("SFTP write failed: ${e.message}", e)
                }
                out.use { channel ->
                    val sink: BufferedSink = channel.sink().buffer()
                    var done = 0L
                    while (true) {
                        val n = src.read(sink.buffer, 64 * 1024L)
                        if (n < 0) break
                        sink.write(sink.buffer, n)
                        sink.flush()
                        done += n
                        emit(Progress(done, null))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val dirPath = resolve(conn, suffix)
            val childPath = if (dirPath.endsWith("/")) "$dirPath$name" else "$dirPath/$name"
            if (kind == FsKind.DIRECTORY) {
                try {
                    sftpFor(conn).mkdir(childPath)
                } catch (e: SftpException) {
                    throw FsException.Remote("SFTP mkdir failed: ${e.message}", e)
                }
            }
            FsNode(
                id = "ee://sftp/${conn.id}/$childPath",
                uri = "ee://sftp/${conn.id}/$childPath",
                name = name,
                providerType = FsType.SFTP,
                metadata = FsMetadata(isDirectory = kind == FsKind.DIRECTORY),
            )
        }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val oldPath = resolve(conn, suffix)
            val dir = if (oldPath.endsWith("/")) oldPath.dropLast(1) else oldPath.substringBeforeLast('/')
            val newPath = if (dir.isEmpty()) "/$newName" else "$dir/$newName"
            try {
                sftpFor(conn).rename(oldPath, newPath)
            } catch (e: SftpException) {
                throw FsException.Remote("SFTP rename failed: ${e.message}", e)
            }
            node.copy(uri = "ee://sftp/${conn.id}/$newPath", id = "ee://sftp/${conn.id}/$newPath", name = newName)
        }

    override suspend fun delete(node: FsNode, force: Boolean) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = resolve(conn, suffix)
        withContext(Dispatchers.IO) {
            try {
                val attrs = sftpFor(conn).stat(path)
                if (attrs.isDirectory && !force) {
                    val entries = sftpFor(conn).ls(path).filter { it.filename != "." && it.filename != ".." }
                    if (entries.isNotEmpty()) throw FsException.Remote("directory not empty (force to delete recursively)")
                }
                if (attrs.isDirectory && force) {
                    // recursive client-side delete (server-side recursive rm is nonstandard)
                    val stack = ArrayDeque(sftpFor(conn).ls(path).map { it.filename }.filter { it != "." && it != ".." })
                    while (stack.isNotEmpty()) {
                        val name = stack.removeFirst()
                        val p = "$path/$name"
                        if (sftpFor(conn).stat(p).isDirectory) {
                            stack.addAll(sftpFor(conn).ls(p).map { it.filename }.filter { it != "." && it != ".." })
                        }
                        sftpFor(conn).rm(p)
                    }
                }
                sftpFor(conn).rm(path)
            } catch (e: FsException) {
                throw e
            } catch (e: SftpException) {
                throw FsException.Remote("SFTP delete failed: ${e.message}", e)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun connection(connId: String): NetConnection =
        source.resolve(connId)
            ?: throw FsException.NotConnected("unknown SFTP connection: $connId")

    private fun sftpFor(conn: NetConnection): SftpClient {
        clients[conn.id]?.let { return it }
        val client = SshClient.getInstance()
        val session = try {
            client.connect { session ->
                session.remoteAddress = java.net.InetSocketAddress.createUnresolved(conn.host, conn.port)
                session.username = conn.username
            }
        } catch (e: Exception) {
            throw FsException.NotConnected("SFTP connect failed: ${e.message}", e)
        }
        val auth = try {
            session.authPassword(conn.secret)
        } catch (e: Exception) {
            runCatching { session.close() }
            throw FsException.NotConnected("SFTP auth failed", e)
        }
        if (!auth.isSuccess) {
            runCatching { session.close() }
            throw FsException.Unauthorized("SFTP authentication rejected")
        }
        val sftp = try {
            session.createSftpClient()
        } catch (e: Exception) {
            runCatching { session.close() }
            throw FsException.NotConnected("SFTP session failed: ${e.message}", e)
        }
        sessions[conn.id] = session
        clients[conn.id] = sftp
        return sftp
    }

    /** connId-relative suffix → absolute SFTP path under the profile's base. */
    private fun resolve(conn: NetConnection, suffix: String): String {
        val base = conn.path.ifEmpty { "/" }
        val cleanBase = if (base.endsWith("/")) base.dropLast(1) else base
        return if (suffix.isEmpty()) cleanBase.ifEmpty { "/" } else "$cleanBase/$suffix"
    }

    private fun childNode(conn: NetConnection, parentSuffix: String, entry: SftpEntry): FsNode {
        val attrs = entry.attributes
        val childSuffix = if (parentSuffix.isEmpty()) entry.filename else "$parentSuffix/${entry.filename}"
        return FsNode(
            id = "ee://sftp/${conn.id}/$childSuffix",
            uri = "ee://sftp/${conn.id}/$childSuffix",
            name = entry.filename,
            providerType = FsType.SFTP,
            metadata = FsMetadata(
                sizeBytes = if (attrs.isDirectory) null else attrs.size,
                modifiedAt = attrs.lastModifiedTime.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                isDirectory = attrs.isDirectory,
                extra = mapOf("connId" to conn.id),
            ),
        )
    }

    companion object {
        /** `ee://sftp/<connId>/<suffix>` → (connId, suffix). */
        fun split(uri: String): Pair<String, String> {
            val rest = FsUri.parse(uri).path
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}
