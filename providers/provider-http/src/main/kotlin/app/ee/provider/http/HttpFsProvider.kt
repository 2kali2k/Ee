package app.ee.provider.http

import android.util.Base64
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Source
import okio.source

/**
 * Plain HTTP(S) resource provider (docs/02-specification.md §4 provider-http):
 * stream remote files, and list directory indexes by parsing the HTML links
 * (Apache/nginx autoindex style) — deliberately naive, flagged.
 *
 * The profile's `path` is the base URL (e.g. `http://host:8080/files/`).
 * Read-only: no write ops.
 */
class HttpFsProvider(
    private val source: ConnectionSource,
    private val client: OkHttpClient = app.ee.core.net.HttpClientFactory.default(),
) : FsProvider {

    override val type: FsType = FsType.HTTP

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.STREAM,
    )

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(uri)
        val conn = connection(connId)
        // HEAD-ish probe: a GET that we close immediately
        val request = Request.Builder().url(url(conn, suffix)).get().build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                401, 403 -> throw FsException.Unauthorized("HTTP: ${response.code}")
                in 200..299 -> Unit
                else -> throw FsException.Remote("HTTP root probe failed: ${response.code}")
            }
        }
        FsNode(
            id = uri,
            uri = uri,
            name = conn.name,
            providerType = FsType.HTTP,
            metadata = FsMetadata(isDirectory = true, extra = mapOf("connId" to conn.id)),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val html = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url(conn, suffix)).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw FsException.Remote("HTTP list failed: ${response.code}")
                }
                response.body?.string().orEmpty()
            }
        }
        val contentType = "text/html"
        if (!html.contains("<a")) {
            // not a directory index — the resource is a single file
            emit(
                FsNode(
                    id = node.uri,
                    uri = node.uri,
                    name = node.name,
                    providerType = FsType.HTTP,
                    metadata = FsMetadata(isDirectory = false, mimeType = contentType),
                ),
            )
            return@flow
        }
        val base = url(conn, suffix)
        for (href in LINKS.findAll(html)) {
            val raw = href.groupValues[1].trim()
            if (raw.isEmpty() || raw.startsWith("javascript:") || raw.startsWith("#")) continue
            val target = absoluteUrl(base, raw)
            val name = raw.trimEnd('/').substringAfterLast('/').trim().ifEmpty { continue }
            if (name == ".." || name == ".") continue
            emit(
                FsNode(
                    id = "ee://http/${conn.id}/$target",
                    uri = "ee://http/${conn.id}/$target",
                    name = name,
                    providerType = FsType.HTTP,
                    metadata = FsMetadata(
                        isDirectory = raw.endsWith("/"),
                        extra = mapOf("connId" to conn.id, "url" to target),
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata =
        node.metadata ?: throw FsException.NotFound(node.uri)

    override fun open(node: FsNode): Source {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val request = Request.Builder()
            .url(url(conn, suffix))
            .get()
            .apply {
                if (conn.secret.isNotEmpty()) {
                    header("Authorization", "Basic " + Base64.encodeToString("${conn.username}:${conn.secret}".toByteArray(), Base64.NO_WRAP))
                }
            }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw FsException.Remote("HTTP open failed: ${e.message}", e)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw FsException.Remote("HTTP open failed: $code")
        }
        val body = response.body ?: run {
            response.close()
            throw FsException.Remote("HTTP open failed: empty body")
        }
        return body.source().let { src ->
            object : Source {
                override fun read(sink: okio.Buffer, byteCount: Long): Long {
                    val n = src.read(sink, byteCount)
                    if (n < 0) response.close()
                    return n
                }

                override fun timeout(): okio.Timeout = src.timeout()

                override fun close() {
                    runCatching { src.close() }
                    response.close()
                }
            }
        }
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> =
        throw FsException.Unsupported("HTTP roots are read-only")

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        throw FsException.Unsupported("HTTP roots are read-only")

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        throw FsException.Unsupported("HTTP roots are read-only")

    override suspend fun delete(node: FsNode, force: Boolean) {
        throw FsException.Unsupported("HTTP roots are read-only")
    }

    private fun connection(connId: String): NetConnection =
        source.resolve(connId)
            ?: throw FsException.NotConnected("unknown HTTP connection: $connId")

    private fun url(conn: NetConnection, suffix: String): String {
        val base = conn.path.trimEnd('/')
        return if (suffix.isEmpty()) base else "$base/$suffix"
    }

    private fun absoluteUrl(base: String, raw: String): String {
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        if (raw.startsWith("/")) {
            // absolute path on the same host
            val schemeEnd = base.indexOf("://")
            if (schemeEnd > 0) {
                val hostEnd = base.indexOf('/', schemeEnd + 3)
                val host = if (hostEnd > 0) base.substring(0, hostEnd) else base
                return host + raw
            }
            return raw
        }
        val parentDir = if (base.endsWith("/")) base else base.substringBeforeLast('/')
        val cleaned = raw.replace("../", "").replace("./", "")
        return parentDir + "/" + cleaned
    }

    companion object {
        private val LINKS = Regex("""<a\s[^>]*?href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        /** `ee://http/<connId>/<url>` → (connId, url). */
        fun split(uri: String): Pair<String, String> {
            val rest = FsUri.parse(uri).path
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}
