package app.ee.provider.local

import app.ee.core.fs.FsUri
import app.ee.core.model.FsType

/**
 * Path <-> VFS-URI mapping for the local provider. Pure JVM (no Android
 * types) so it is unit-testable on the host.
 */
object LocalPaths {

    /** Hidden recycle-bin directory directly under the storage root. */
    const val TRASH_DIR = ".ee_trash"

    fun uriFor(absolutePath: String): String =
        FsUri.encode(FsType.LOCAL, absolutePath.removePrefix("/"))

    fun pathForUri(uri: String): String =
        "/" + FsUri.parse(uri).path

    /** True if [absolutePath] is inside the trash of [root]. */
    fun inTrash(absolutePath: String, root: String): Boolean {
        val trash = "$root/$TRASH_DIR"
        return absolutePath == trash || absolutePath.startsWith("$trash/")
    }

    /** Collision-safe name for moving an item into the trash. */
    fun trashEntryName(timestampMs: Long, name: String, used: Set<String>): String {
        var candidate = "${timestampMs}__$name"
        var i = 1
        while (candidate in used) {
            candidate = "${timestampMs}__$name~$i"
            i++
        }
        return candidate
    }

    fun extension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot in 1 until name.length) name.substring(dot + 1).lowercase() else ""
    }
}
