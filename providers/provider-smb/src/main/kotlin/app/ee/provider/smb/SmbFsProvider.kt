package app.ee.provider.smb

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
import com.hierynomus.smbj.SmbClient
import com.hierynomus.smbj.SmbException
import com.hierynomus.smbj.auth.LoginPasswordAuthenticator
import com.hierynomus.smbj.share.SmbFile
import com.hierynomus.smbj.share.SmbShare
import java.net.InetSocketAddress
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

/**
 * SMB 2/3 provider over SMBJ (docs/02-specification.md P1-3).
 *
 * URI layout: `ee://smb/<connectionId>/<path inside the share>` — the
 * connection profile (host/port/share/credentials) is resolved by
 * [ConnectionSource]; one process-wide [SmbClient] per connection.
 *
 * M3 limitations (flagged): connections stay open for the process lifetime
 * (no auto-reconnect on network change); writes are whole-file (no chunked
 * resume); directory copy/move uses recursive delete+recreate at the
 * transfer layer, not here.
 */
class SmbFsProvider(private val source: ConnectionSource) : FsProvider {

    override val type: FsType = FsType.SMB

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
        FsCapability.STREAM,
    )

    private val clients = mutableMapOf<String, SmbClient>()
    private val shares = mutableMapOf<String, SmbShare>()

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val (connId, path) = split(uri)
        val conn = connection(connId)
        val file = dirFile(conn, path)
        FsNode(
            id = uri,
            uri = uri,
            name = conn.name,
            providerType = FsType.SMB,
            metadata = dirMetadata(conn, path, file),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val (connId, path) = split(node.uri)
        val conn = connection(connId)
        val dir = dirFile(conn, path)
        withContext(Dispatchers.IO) {
            val files = try {
                dir.listFiles()
            } catch (e: SmbException) {
                throw FsException.Remote("SMB list failed: ${e.message}", e)
            }
            for (f in files) {
                emit(childNode(conn, path, f))
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata = withContext(Dispatchers.IO) {
        val (connId, path) = split(node.uri)
        val conn = connection(connId)
        val file = fileAt(conn, path)
        node.metadata ?: FsMetadata(
            sizeBytes = if (file.isDirectory) null else file.length(),
            modifiedAt = file.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
            isDirectory = file.isDirectory,
        )
    }

    override fun open(node: FsNode): Source {
        val (connId, path) = split(node.uri)
        val conn = connection(connId)
        val file = fileAt(conn, path)
        if (file.isDirectory) throw FsException.Remote("is a directory: $path")
        return try {
            file.createFile().source()
        } catch (e: SmbException) {
            throw FsException.Remote("SMB open failed: ${e.message}", e)
        }
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        val (connId, path) = split(node.uri)
        val conn = connection(connId)
        val file = fileAt(conn, path)
        withContext(Dispatchers.IO) {
            bytes.use { src ->
                file.openOutputStream().use { out ->
                    val sink: BufferedSink = out.sink().buffer()
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
            val (connId, path) = split(node.uri)
            val conn = connection(connId)
            val dir = dirFile(conn, path)
            val created = try {
                when (kind) {
                    FsKind.DIRECTORY -> dir.getFile(name).createDirectory()
                    else -> dir.getFile(name)
                }
            } catch (e: SmbException) {
                throw FsException.Remote("SMB create failed: ${e.message}", e)
            }
            childNode(conn, path, created)
        }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, path) = split(node.uri)
            val conn = connection(connId)
            val file = fileAt(conn, path)
            val parent = dirFile(conn, parentOf(path))
            val target = parent.getFile(newName)
            try {
                if (!file.renameTo(target)) throw FsException.Remote("SMB rename failed: $path -> $newName")
            } catch (e: SmbException) {
                throw FsException.Remote("SMB rename failed: ${e.message}", e)
            }
            childNode(conn, parentOf(path), target)
        }

    override suspend fun delete(node: FsNode, force: Boolean) {
        val (connId, path) = split(node.uri)
        val conn = connection(connId)
        withContext(Dispatchers.IO) {
            val file = fileAt(conn, path)
            try {
                if (file.isDirectory && !force && file.listFiles().isNotEmpty()) {
                    throw FsException.Remote("directory not empty (force to delete recursively)")
                }
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            } catch (e: SmbException) {
                throw FsException.Remote("SMB delete failed: ${e.message}", e)
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun connection(connId: String): NetConnection =
        source.resolve(connId)
            ?: throw FsException.NotConnected("unknown SMB connection: $connId")

    private fun clientFor(conn: NetConnection): SmbClient = clients.getOrPut(conn.id) {
        val client = SmbClient.builder().build()
        try {
            client.connect(InetSocketAddress(conn.host, conn.port))
            client.authenticate(LoginPasswordAuthenticator(conn.username, conn.secret))
            client
        } catch (e: Exception) {
            runCatching { client.close() }
            throw FsException.NotConnected("SMB connect failed: ${e.message}", e)
        }
    }

    private fun shareFor(conn: NetConnection): SmbShare {
        shares[conn.id]?.let { return it }
        val client = clientFor(conn)
        return try {
            client.connectShare(conn.path).also { shares[conn.id] = it }
        } catch (e: SmbException) {
            throw FsException.NotConnected("SMB share failed: ${e.message}", e)
        }
    }

    /** Share-relative path; "" is the share root. */
    private fun dirFile(conn: NetConnection, path: String): SmbFile {
        val share = shareFor(conn)
        return try {
            share.getFileDirectory("/$path".removePrefix("//"))
        } catch (e: SmbException) {
            throw FsException.Remote("SMB path failed: ${e.message}", e)
        }
    }

    private fun fileAt(conn: NetConnection, path: String): SmbFile {
        if (path.isEmpty()) return dirFile(conn, "")
        val name = path.substringAfterLast('/')
        return try {
            dirFile(conn, parentOf(path)).getFile(name)
        } catch (e: SmbException) {
            throw FsException.Remote("SMB path failed: ${e.message}", e)
        }
    }

    private fun dirMetadata(conn: NetConnection, path: String, file: SmbFile): FsMetadata =
        FsMetadata(
            sizeBytes = null,
            modifiedAt = file.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
            isDirectory = true,
            extra = mapOf("connId" to conn.id),
        )

    private fun childNode(conn: NetConnection, parentPath: String, f: SmbFile): FsNode {
        val childPath = if (parentPath.isEmpty()) f.name else "$parentPath/${f.name}"
        return FsNode(
            id = "ee://smb/${conn.id}/$childPath",
            uri = "ee://smb/${conn.id}/$childPath",
            name = f.name,
            providerType = FsType.SMB,
            metadata = FsMetadata(
                sizeBytes = if (f.isDirectory) null else f.length(),
                modifiedAt = f.lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                isDirectory = f.isDirectory,
                extra = mapOf("connId" to conn.id),
            ),
        )
    }

    companion object {
        /** `ee://smb/<connId>/<path>` → (connId, share-relative path). */
        fun split(uri: String): Pair<String, String> {
            val rest = FsUri.parse(uri).path
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }

        fun parentOf(path: String): String {
            if (path.isEmpty() || path.endsWith("/")) return path
            return path.substringBeforeLast('/', "")
        }
    }
}
