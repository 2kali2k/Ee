package app.ee.core.model

/**
 * A resolved network connection profile, including the live secret
 * (docs/02-specification.md §6: secrets are resolved on demand from the
 * Android Keystore-backed store and are never persisted in plaintext).
 *
 * Pure JVM value type — Android storage lives in `core-security`, the
 * profiles themselves in Room (`core-database.ConnectionEntity`).
 */
data class NetConnection(
    val id: String,
    val name: String,
    val type: FsType,
    val host: String,
    val port: Int,
    val path: String,
    val username: String,
    val secret: String,
)

/**
 * Resolves connection profiles by id for a provider. Implemented by the app
 * composition root (Room row + Keystore secret); providers treat it as
 * read-only and re-resolve on every connection (profiles can be edited).
 */
fun interface ConnectionSource {
    fun resolve(id: String): NetConnection?
}

/**
 * Clipboard entry for copy/move (P0-5 ops, M3). Carries everything the
 * source provider needs to re-open the item — `extra` holds provider-specific
 * metadata (e.g. provider-local's absolute `path`).
 */
data class ClipboardEntry(
    val uri: String,
    val name: String,
    val type: FsType,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val extra: Map<String, String> = emptyMap(),
)
