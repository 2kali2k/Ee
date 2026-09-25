package app.ee.provider.webdav

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
import java.net.URLDecoder
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Source
import okio.source

/**
 * WebDAV provider over plain OkHttp (docs/02-specification.md P1-6):
 * PROPFIND for listing, GET/PUT/MKCOL/MOVE/DELETE for file ops.
 *
 * URI layout: `ee://webdav/<connectionId>/<path>` — `path` is relative to the
 * profile's base path. Scheme is chosen by port (443 → https, else http);
 * basic auth from the profile's credentials.
 *
 * M3 limitations (flagged): no chunked/Range uploads; the 207 response is
 * parsed with a conservative regex (DAV servers emit well-formed
 * multi-status, but a full XML parser lands with hardening); no digest auth.
 */
class WebDavFsProvider(
    private val source: ConnectionSource,
    private val client: OkHttpClient = app.ee.core.net.HttpClientFactory.default(),
) : FsProvider {

    override val type: FsType = FsType.WEBDAV

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
        FsCapability.STREAM,
    )

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(uri)
        val conn = connection(connId)
        // validate connectivity up front
        propfind(conn, fullPath(conn, suffix)).first
        FsNode(
            id = uri,
            uri = uri,
            name = conn.name,
            providerType = FsType.WEBDAV,
            metadata = FsMetadata(isDirectory = true, extra = mapOf("connId" to conn.id)),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val entries = withContext(Dispatchers.IO) { propfind(conn, fullPath(conn, suffix)).second }
        for (e in entries) {
            emit(
                FsNode(
                    id = "ee://webdav/${conn.id}/${suffixOf(conn, e.href)}",
                    uri = "ee://webdav/${conn.id}/${suffixOf(conn, e.href)}",
                    name = e.href.substringAfterLast('/').ifEmpty { conn.name },
                    providerType = FsType.WEBDAV,
                    metadata = FsMetadata(
                        sizeBytes = if (e.isDirectory) null else e.size,
                        modifiedAt = e.lastModified,
                        isDirectory = e.isDirectory,
                        mimeType = e.contentType,
                        extra = mapOf("connId" to conn.id),
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata = withContext(Dispatchers.IO) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = fullPath(conn, suffix)
        val (self, children) = propfind(conn, path)
        val entry = self ?: children.firstOrNull { it.href.endsWith(path) }
            ?: throw FsException.NotFound(node.uri)
        node.metadata ?: FsMetadata(
            sizeBytes = if (entry.isDirectory) null else entry.size,
            modifiedAt = entry.lastModified,
            isDirectory = entry.isDirectory,
            mimeType = entry.contentType,
        )
    }

    override fun open(node: FsNode): Source {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val request = Request.Builder()
            .url(base(conn) + fullPath(conn, suffix))
            .get()
            .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw FsException.Remote("WebDAV open failed: ${e.message}", e)
        }
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw if (code == 401 || code == 403) FsException.Unauthorized("WebDAV: HTTP $code")
            else FsException.Remote("WebDAV open failed: HTTP $code")
        }
        val body = response.body ?: run {
            response.close()
            throw FsException.Remote("WebDAV open failed: empty body")
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

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        val path = fullPath(conn, suffix)
        val total = bytes.buffered().buffer.size
        withContext(Dispatchers.IO) {
            val body = bytes.buffered().buffer.clone().toRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder()
                .url(base(conn) + path)
                .put(body)
                .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in setOf(201, 204)) {
                    throw FsException.Remote("WebDAV PUT failed: HTTP ${response.code}")
                }
            }
        }
        emit(Progress(total, total.takeIf { it > 0 }))
    }.flowOn(Dispatchers.IO)

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val parent = fullPath(conn, suffix)
            val full = if (parent == "/") "/$name" else "$parent/$name"
            if (kind == FsKind.DIRECTORY) {
                val request = Request.Builder()
                    .url(base(conn) + full)
                    .method("MKCOL", "".toRequestBody())
                    .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code !in setOf(201, 301)) {
                        throw FsException.Remote("WebDAV MKCOL failed: HTTP ${response.code}")
                    }
                }
            }
            FsNode(
                id = "ee://webdav/${conn.id}/$full",
                uri = "ee://webdav/${conn.id}/$full",
                name = name,
                providerType = FsType.WEBDAV,
                metadata = FsMetadata(isDirectory = kind == FsKind.DIRECTORY),
            )
        }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val (connId, suffix) = split(node.uri)
            val conn = connection(connId)
            val full = fullPath(conn, suffix)
            val dir = full.substringBeforeLast('/')
            val dest = if (dir.isEmpty() || dir == "/") "/$newName" else "$dir/$newName"
            val request = Request.Builder()
                .url(base(conn) + full)
                .method("MOVE", "".toRequestBody())
                .header("Destination", base(conn) + dest)
                .header("Overwrite", "T")
                .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
                .build()
            client.newCall(request).execute().use { response ->
                if (response.code !in setOf(201, 204)) {
                    throw FsException.Remote("WebDAV MOVE failed: HTTP ${response.code}")
                }
            }
            node.copy(uri = "ee://webdav/${conn.id}/$dest", id = "ee://webdav/${conn.id}/$dest", name = newName)
        }

    override suspend fun delete(node: FsNode, force: Boolean) {
        val (connId, suffix) = split(node.uri)
        val conn = connection(connId)
        withContext(Dispatchers.IO) {
            val path = fullPath(conn, suffix)
            val request = Request.Builder()
                .url(base(conn) + path)
                .delete()
                .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
                .build()
            client.newCall(request).execute().use { response ->
                // 204 is standard; some servers answer 200
                if (response.code !in setOf(200, 204)) {
                    throw FsException.Remote("WebDAV DELETE failed: HTTP ${response.code}")
                }
            }
        }
    }

    // ── PROPFIND ───────────────────────────────────────────────────────────

    private class Entry(
        val href: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Instant?,
        val contentType: String?,
    )

    /** Returns (selfEntry?, children) for a PROPFIND Depth:1. */
    private fun propfind(conn: NetConnection, path: String): Pair<Entry?, List<Entry>> {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname/><d:getcontentlength/><d:getlastmodified/>
                <d:resourcetype/><d:getcontenttype/>
              </d:prop>
            </d:propfind>
        """.trimIndent()
        val request = Request.Builder()
            .url(base(conn) + path)
            .method("PROPFIND", xml.toRequestBody())
            .header("Depth", "1")
            .apply { if (conn.secret.isNotEmpty()) header("Authorization", basic(conn)) }
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: Exception) {
            throw FsException.Remote("WebDAV PROPFIND failed: ${e.message}", e)
        }
        response.use {
            val code = it.code
            if (code != 207 && code != 200) {
                throw when (code) {
                    401, 403 -> FsException.Unauthorized("WebDAV: HTTP $code")
                    404 -> FsException.NotFound(path)
                    else -> FsException.Remote("WebDAV PROPFIND failed: HTTP $code")
                }
            }
            val body = it.body?.string() ?: ""
            val blocks = RESPONSE_BLOCK.findAll(body).map { it.value }.toList()
            val entries = blocks.mapNotNull { block -> parseEntry(block) }
            val self = entries.firstOrNull { it.href.equals(path, ignoreCase = true) }
                ?: entries.firstOrNull { it.href.endsWith(path) }
            val children = entries.filter { !it.href.equals(path, ignoreCase = true) }
            return self to children
        }
    }

    private fun parseEntry(block: String): Entry? {
        val hrefRaw = HREF.find(block)?.groupValues?.get(1) ?: return null
        val href = decodeHref(hrefRaw)
        val isDirectory = COLLECTION in block
        val size = LENGTH.find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val lastModified = MODIFIED.find(block)?.groupValues?.get(1)
            ?.let { runCatching { ZonedDateTime.parse(it, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
        val contentType = TYPE.find(block)?.groupValues?.get(1)
        return Entry(href, isDirectory, size, lastModified, contentType)
    }

    private fun decodeHref(raw: String): String {
        var s = raw.trim()
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        // strip scheme://host when the href is absolute
        val schemeIdx = s.indexOf("://")
        if (schemeIdx > 0) {
            val after = s.indexOf('/', schemeIdx + 3)
            if (after > 0) s = s.substring(after)
        }
        return s.split('/').joinToString("/") {
            runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun connection(connId: String): NetConnection =
        source.resolve(connId)
            ?: throw FsException.NotConnected("unknown WebDAV connection: $connId")

    private fun base(conn: NetConnection): String {
        val scheme = if (conn.port == 443) "https" else "http"
        val port = if (conn.port == 443 || conn.port == 80) "" else ":${conn.port}"
        return "$scheme://${conn.host}$port"
    }

    private fun basic(conn: NetConnection): String =
        "Basic " + Base64.encodeToString(
            "${conn.username}:${conn.secret}".toByteArray(),
            Base64.NO_WRAP,
        )

    private fun fullPath(conn: NetConnection, suffix: String): String {
        val base = conn.path.ifEmpty { "/" }
        val cleanBase = if (base.endsWith("/")) base.dropLast(1) else base
        return if (suffix.isEmpty()) cleanBase.ifEmpty { "/" } else "$cleanBase/$suffix"
    }

    private fun suffixOf(conn: NetConnection, href: String): String {
        val full = fullPath(conn, "")
        return href.removePrefix(full).removePrefix("/")
    }

    companion object {
        private val RESPONSE_BLOCK =
            Regex("<d:response[\\s>].*?</d:response>", RegexOption.DOT_MATCHES_ALL)
        private val HREF = Regex("<d:href[^>]*>(.*?)</d:href>", RegexOption.DOT_MATCHES_ALL)
        private val COLLECTION = Regex("<d:collection\\s*/?>")
        private val LENGTH = Regex("<d:getcontentlength>(\\d+)</d:getcontentlength>")
        private val MODIFIED = Regex("<d:getlastmodified>(.*?)</d:getlastmodified>")
        private val TYPE = Regex("<d:getcontenttype>(.*?)</d:getcontenttype>")

        /** `ee://webdav/<connId>/<suffix>` → (connId, suffix). */
        fun split(uri: String): Pair<String, String> {
            val rest = FsUri.parse(uri).path
            val slash = rest.indexOf('/')
            return if (slash < 0) rest to "" else rest.substring(0, slash) to rest.substring(slash + 1)
        }
    }
}
