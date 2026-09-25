package app.ee.core.model

/**
 * A single node in the virtual filesystem (a file or directory at a point in
 * time).
 *
 * `id` is provider-stable (survives re-listing the parent), while `uri` is the
 * canonical, serializable address used for persistence, intents and deep links.
 * See `app.ee.core.fs.FsUri` for the scheme convention (`ee://<type>/<path>`).
 *
 * Deliberately NOT an Android `Uri` type: `core-model` stays pure JVM so the
 * whole VFS core can be unit-tested without a device. The Android boundary
 * (`FileContentProvider`, intents) maps to/from `android.net.Uri`.
 */
data class FsNode(
    val id: String,
    val uri: String,
    val name: String,
    val providerType: FsType,
    val parent: FsNode? = null,
    /** Set on directory nodes after a successful list. */
    val children: List<FsNode>? = null,
    val metadata: FsMetadata? = null,
) {
    val isDirectory: Boolean
        get() = metadata?.isDirectory ?: (children != null)
}
