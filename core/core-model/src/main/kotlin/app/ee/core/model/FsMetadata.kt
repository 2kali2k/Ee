package app.ee.core.model

import java.time.Instant

/**
 * Provider-supplied metadata for a node. Fields that a provider cannot know
 * (e.g. `etag` on a local FS) stay `null` — UI must render them as "—", never
 * guess a default.
 */
data class FsMetadata(
    val sizeBytes: Long? = null,
    val modifiedAt: Instant? = null,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val permissions: Set<Perm> = emptySet(),
    val owner: String? = null,
    val etag: String? = null,
    /** Provider-specific extras, e.g. `("share", "read-only")` for SMB. */
    val extra: Map<String, String> = emptyMap(),
)
