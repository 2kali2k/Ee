package app.ee.provider.ftp

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
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply

/**
 * FTP client provider over Apache Commons Net (docs/02-specification.md P1-5
 * client half; the embedded LAN server is a separate M3b item).
 *
 * URI layout: `ee://ftp/<connectionId>/<path>` — `path` is relative to the
 * profile's base directory. Passive mode by default (NAT-friendly).
 *
 * M3 limitations (flagged): no TLS (FTPS) yet; whole-file writes; one
 * control connection per profile for the process lifetime.
 */
class FtpFsProvider(private val source: ConnectionSource) : FsProvider {

    override val type: FsType = FsType.FTP

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
        FsCapability.STREAM,
    )

    private val clients = mutableMapOf<String, FTPClient>()

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(uri)
        val conn = connection(connId)
        val full = resolve(conn, suffix)
        clientFor(conn)
        FsNode(
            id = uri,
            uri = uri,
            name = conn.name,
            providerType = FsType.FTP,
            metadata = FsMetadata(isDirectory = true, extra = mapOf("connId" to conn.id)),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val full = resolve(conn, suffix)
        val files = withContext(Dispatchers.IO) {
            clientFor(conn).listFiles(full)
        }
        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            emit(childNode(conn, suffix, f))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val f = statFile(clientFor(conn), resolve(conn, suffix))
            ?: throw FsException.NotFound(node.uri)
        node.metadata ?: FsMetadata(
            sizeBytes = if (f.isDirectory) null else f.size,
            modifiedAt = runCatching { f.timestamp.toDateTime().toInstant().toEpochMilli() }
                .getOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
            isDirectory = f.isDirectory,
        )
    }

    override fun open(node: FsNode): Source {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val client = clientFor(conn)
        return try {
            client.retrieveFileStream(resolve(conn, suffix)).source()
        } catch (e: Exception) {
            throw FsException.Remote("FTP open failed: ${e.message}", e)
        }
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val client = clientFor(conn)
        withContext(Dispatchers.IO) {
            val out = try {
                client.storeFileStream(resolve(conn, suffix))
            } catch (e: Exception) {
                throw FsException.Remote("FTP write failed: ${e.message}", e)
            }
            bytes.use { src ->
                out.use { stream ->
                    val sink: BufferedSink = stream.sink().buffer()
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
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw FsException.Remote("FTP store rejected: code ${client.replyCode}")
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val client = clientFor(conn)
            val parent = resolve(conn, suffix)
            val full = if (parent == "/") "/$name" else "$parent/$name"
            if (kind == FsKind.DIRECTORY) {
                if (!client.makeDirectory(full)) throw FsException.Remote("FTP mkdir failed: $full")
            }
            FsNode(
                id = "ee://ftp/${conn.id}/$full",
                uri = "ee://ftp/${conn.id}/$full",
                name = name,
                providerType = FsType.FTP,
                metadata = FsMetadata(isDirectory = kind == FsKind.DIRECTORY),
            )
        }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val client = clientFor(conn)
            val old = resolve(conn, suffix)
            val dir = old.substringBeforeLast('/')
            val new = if (dir.isEmpty() || dir == "/") "/$newName" else "$dir/$newName"
            if (!client.rename(old, new)) throw FsException.Remote("FTP rename failed: $old -> $new")
            node.copy(uri = "ee://ftp/${conn.id}/$new", id = "ee://ftp/${conn.id}/$new", name = newName)
        }

    override suspend fun delete(node: FsNode, force: Boolean) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        withContext(Dispatchers.IO) {
            val client = clientFor(conn)
            val full = resolve(conn, suffix)
            val f = statFile(client, full) ?: return@withContext
            if (f.isDirectory) {
                if (!force) {
                    val children = client.listFiles(full).filter { it.name != "." && it.name != ".." }
                    if (children.isNotEmpty()) throw FsException.Remote("directory not empty (force to delete recursively)")
                }
                if (force) {
                    // client-side recursive delete (FTP has no recursive RM)
                    val stack = ArrayDeque(client.listFiles(full).map { it.name }.filter { it != "." && it != ".." })
                    while (stack.isNotEmpty()) {
                        val name = stack.removeFirst()
                        val p = "$full/$name"
                        val child = statFile(client, p)
                        if (child != null && child.isDirectory) {
                            stack.addAll(client.listFiles(p).map { it.name }.filter { it != "." && it != ".." })
                        }
                        runCatching { if (child != null && child.isDirectory) client.removeDirectory(p) else client.deleteFile(p) }
                    }
                }
                client.removeDirectory(full)
            } else {
                client.deleteFile(full)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun connection(connId: String): NetConnection =
        source.resolve(connId)
            ?: throw FsException.NotConnected("unknown FTP connection: $connId")

    private fun clientFor(conn: NetConnection): FTPClient {
        clients[conn.id]?.let { if (it.isConnected) return it }
        val client = FTPClient().apply {
            connectTimeout = 15_000
            dataTimeout = 60_000
            controlConnectionEncoding = "UTF-8"
        }
        try {
            client.connect(conn.host, conn.port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw FsException.NotConnected("FTP connect failed: code ${client.replyCode}")
            }
            val logged = if (conn.secret.isEmpty()) client.login(conn.username) else client.login(conn.username, conn.secret)
            if (!logged) throw FsException.Unauthorized("FTP authentication rejected")
            client.enterLocalPassiveMode()
            if (conn.path.isNotBlank() && conn.path != "/") {
                if (!client.changeWorkingDirectory(conn.path)) {
                    throw FsException.Remote("FTP base path missing: ${conn.path}")
                }
            }
        } catch (e: FsException) {
            runCatching { client.disconnect() }
            throw e
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw FsException.NotConnected("FTP connect failed: ${e.message}", e)
        }
        clients[conn.id] = client
        return client
    }

    /** Stat helper: list the path (dir) or its parent (file), find by name. */
    private fun statFile(client: FTPClient, full: String): org.apache.commons.net.ftp.FTPFile? {
        val name = full.substringAfterLast('/')
        if (name.isEmpty()) return null
        client.listFiles(full).firstOrNull { it.name == name }?.let { return it }
        val parent = full.substringBeforeLast('/')
        if (parent.isEmpty() || parent == full) return null
        return client.listFiles(parent).firstOrNull { it.name == name }
    }

    /** Profile base + suffix → absolute FTP path. */
    private fun resolve(conn: NetConnection, suffix: String): String {
        val base = conn.path.ifBlank { "/" }
        val cleanBase = if (base.endsWith("/")) base.dropLast(1) else base
        return if (suffix.isEmpty()) cleanBase.ifEmpty { "/" } else "$cleanBase/$suffix"
    }

    private fun childNode(conn: NetConnection, parentSuffix: String, f: org.apache.commons.net.ftp.FTPFile): FsNode {
        val childSuffix = if (parentSuffix.isEmpty()) f.name else "$parentSuffix/${f.name}"
        return FsNode(
            id = "ee://ftp/${conn.id}/$childSuffix",
            uri = "ee://ftp/${conn.id}/$childSuffix",
            name = f.name,
            providerType = FsType.FTP,
            metadata = FsMetadata(
                sizeBytes = if (f.isDirectory) null else f.size,
                modifiedAt = runCatching { f.timestamp.toDateTime().toInstant().toEpochMilli() }
                    .getOrNull()?.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                isDirectory = f.isDirectory,
                extra = mapOf("connId" to conn.id),
            ),
        )
    }

    companion object {
        /** `ee://ftp/<connId>/<suffix>` → (connId, suffix). */
        fun split(uri: String): Pair<String, String> {
            val rest = FsUri.parse(uri).path
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}
