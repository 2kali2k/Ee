package app.ee.provider.local

import android.os.Environment
import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.Progress
import app.ee.core.model.FsCapability
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.Source

/**
 * Local storage provider (docs/02-specification.md P0-1, M1).
 *
 * Storage model (see spec §6 permissions):
 *  - API <= 29 : WRITE/READ_EXTERNAL_STORAGE runtime permissions.
 *  - API 30+   : listing works where the platform grants it (app-specific
 *    dirs, granted trees); full-disk browsing requires the user's explicit
 *    "All files access" opt-in (Settings > Privacy), checked via
 *    [StorageAccess.canManageAllFiles].
 *
 * Recycle bin (P0-5): deletions move to `<root>/.ee_trash/<ts>__<name>`;
 * items already inside the trash are deleted for good.
 */
class LocalFsProvider(private val storageRoot: File) : FsProvider {

    override val type: FsType = FsType.LOCAL

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.WRITE,
        FsCapability.CREATE,
        FsCapability.CREATE_DIR,
        FsCapability.RENAME,
        FsCapability.DELETE,
    )

    override suspend fun root(uri: String): FsNode =
        nodeFor(storageRoot, parent = null)

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val path = LocalPaths.pathForUri(node.uri)
        val dir = File(path)
        val files = withContext(Dispatchers.IO) {
            if (!dir.isDirectory) throw FsException.NotFound(node.uri)
            dir.listFiles()?.toList()
                ?: throw FsException.NotFound(node.uri)
        }
        // name asc, case-insensitive — UI re-sorts per user preference
        for (file in files.sortedBy { it.name.lowercase() }) {
            val isRootLevelTrash = file.name == LocalPaths.TRASH_DIR &&
                LocalPaths.pathForUri(node.uri) == storageRoot.absolutePath
            if (isRootLevelTrash) continue
            emit(nodeFor(file, parent = node))
        }
    }

    override suspend fun metadata(node: FsNode): FsMetadata =
        withContext(Dispatchers.IO) {
            val file = File(LocalPaths.pathForUri(node.uri))
            if (!file.exists()) throw FsException.NotFound(node.uri)
            file.toMetadata()
        }

    override fun open(node: FsNode): Source {
        val file = File(LocalPaths.pathForUri(node.uri))
        if (!file.isFile) throw FsException.NotFound(node.uri)
        return file.inputStream().source()
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> = flow {
        val file = File(LocalPaths.pathForUri(node.uri))
        // M1: fully buffer (files are typically small); streaming chunks land
        // in M2 with resume support.
        val buffer = Buffer()
        bytes.use { src -> buffer.writeAll(src) }
        val total = buffer.size
        var done = 0L
        file.outputStream().use { out ->
            while (buffer.size > 0) {
                val chunk = buffer.readByteArray(64 * 1024L)
                if (chunk.isEmpty()) break
                out.write(chunk)
                done += chunk.size.toLong()
                emit(Progress(done, total))
            }
            out.flush()
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        withContext(Dispatchers.IO) {
            val parent = File(LocalPaths.pathForUri(node.uri))
            if (!parent.isDirectory) throw FsException.NotFound(node.uri)
            val target = File(parent, name)
            if (target.exists()) throw FsException.Remote("$name already exists")
            when (kind) {
                FsKind.DIRECTORY -> if (!target.mkdir()) throw FsException.Remote("cannot create directory")
                FsKind.FILE -> if (!target.createNewFile()) throw FsException.Remote("cannot create file")
                FsKind.SYMLINK -> throw FsException.Unsupported("symlinks are not supported")
            }
            nodeFor(target, parent = node)
        }

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        withContext(Dispatchers.IO) {
            val file = File(LocalPaths.pathForUri(node.uri))
            if (!file.exists()) throw FsException.NotFound(node.uri)
            val target = File(file.parentFile, newName)
            if (target.exists()) throw FsException.Remote("$newName already exists")
            if (!file.renameTo(target)) throw FsException.Remote("rename failed (cross-device?)")
            nodeFor(target, parent = node.parent)
        }

    override suspend fun delete(node: FsNode, force: Boolean) =
        withContext(Dispatchers.IO) {
            val file = File(LocalPaths.pathForUri(node.uri))
            if (!file.exists()) throw FsException.NotFound(node.uri)
            if (file.absolutePath == storageRoot.absolutePath) {
                throw FsException.Unsupported("cannot delete the storage root")
            }
            val inTrash = LocalPaths.inTrash(file.absolutePath, storageRoot.absolutePath)
            if (inTrash) {
                file.deleteRecursively()
            } else {
                moveToTrash(file)
            }
        }

    private fun moveToTrash(file: File) {
        val trash = File(storageRoot, LocalPaths.TRASH_DIR)
        if (!trash.exists() && !trash.mkdirs()) {
            throw FsException.Remote("cannot create recycle bin")
        }
        val used = trash.listFiles()?.mapTo(mutableSetOf()) { it.name } ?: emptySet()
        val target = File(trash, LocalPaths.trashEntryName(System.currentTimeMillis(), file.name, used))
        if (!file.renameTo(target)) {
            // rename across mount points: copy + delete
            if (file.isDirectory) {
                file.copyRecursively(target, overwrite = false)
                file.deleteRecursively()
            } else {
                file.copyTo(target, overwrite = false)
                file.delete()
            }
        }
    }

    private fun File.toMetadata(): FsMetadata = FsMetadata(
        sizeBytes = if (isFile) length() else null,
        modifiedAt = lastModified().takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
        isDirectory = isDirectory,
        mimeType = null, // resolved by UI via extension for M1
        permissions = emptySet(),
        extra = mapOf("path" to absolutePath),
    )

    private fun nodeFor(file: File, parent: FsNode?): FsNode = FsNode(
        id = file.absolutePath,
        uri = LocalPaths.uriFor(file.absolutePath),
        name = file.name.ifEmpty { file.absolutePath },
        providerType = FsType.LOCAL,
        parent = parent,
        metadata = runCatching { file.toMetadata() }.getOrNull(),
    )
}

/** Storage-permission helpers for the UI (settings opt-in flow). */
object StorageAccess {

    /**
     * True when the app can list arbitrary directories on primary storage.
     * Always true below API 30 (after the legacy runtime grant); on 30+ it
     * reflects the "All files access" special permission.
     */
    fun canManageAllFiles(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }
}
