package app.ee.core.fs

import app.ee.core.model.FsType

/**
 * Canonical URI scheme for VFS addresses: `ee://<type>/<opaque-path>`.
 *
 * Examples:
 *  - `ee://local/emulated/0/Download/report.pdf`
 *  - `ee://smb/192.168.1.10%2FPublic/documents`
 *  - `ee://vault/uuid-.../2024-notes.md`
 *
 * Core stays JVM-pure, so this is a `String` convention, not
 * `android.net.Uri`; the Android boundary (content provider / intents)
 * converts. The path segment is URL-encoded per segment so it round-trips
 * through any provider's opaque part.
 */
object FsUri {

    const val SCHEME = "ee"

    /** Encode `type` + provider-opaque path into a canonical VFS URI. */
    fun encode(type: FsType, path: String): String {
        require(path == path.trim()) { "path must not have surrounding spaces: '$path'" }
        val normalized = path.removePrefix("/")
        return "$SCHEME://${type.name.lowercase()}/$normalized"
    }

    data class Parsed(val type: FsType, val path: String) {
        val isRoot: Boolean get() = path.isEmpty()
    }

    /** Parse a canonical VFS URI. Throws [IllegalArgumentException] on bad input. */
    fun parse(uri: String): Parsed {
        val rest = uri.removePrefix("$SCHEME://")
        val slash = rest.indexOf('/')
        require(slash > 0) { "not a valid VFS uri (missing path): $uri" }
        val typePart = rest.substring(0, slash).lowercase()
        val path = rest.substring(slash + 1)
        val type = FsType.entries.firstOrNull { it.name.lowercase() == typePart }
            ?: throw IllegalArgumentException("unknown FsType in uri: $uri")
        return Parsed(type, path)
    }

    val Parsed.uri: String get() = encode(type, path)
}
