package app.ee.provider.archive

import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.FsUri
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
import okio.Source
import okio.source

/**
 * Archives as virtual folders (M2 — P0-6 browse, P1-1 create/extract).
 *
 * URI layout:
 *   root : `ee://archive/<absolute archive path>`
 *   item : `ee://archive/<absolute archive path>/<root-relative entry name>`
 *
 * Directories inside the archive are browsable: listing a node yields its
 * direct children only. M2 supports zip/tar/gz/bz2 (see [ArchiveEngine]).
 */
class ArchiveFsProvider : FsProvider {

    override val type: FsType = FsType.ARCHIVE

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.STREAM,
    )

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val archivePath = archivePathOf(uri)
        val archive = File(archivePath)
        if (!archive.isFile) throw FsException.NotFound(uri)
        if (!ArchiveEngine.isSupported(archive.name)) {
            throw FsException.Unsupported("format not supported yet: ${archive.name}")
        }
        FsNode(
            id = uri,
            uri = uri,
            name = archive.name,
            providerType = FsType.ARCHIVE,
            metadata = FsMetadata(
                sizeBytes = archive.length(),
                modifiedAt = Instant.ofEpochMilli(archive.lastModified()),
                isDirectory = true,
                extra = mapOf("archive" to archivePath),
            ),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val archivePath = node.metadata?.extra?.get("archive")
            ?: archivePathOf(node.uri)
        val archive = File(archivePath)
        val entries = withContext(Dispatchers.IO) { ArchiveEngine.entries(archive) }
        val prefix = entryPrefixOf(node.uri, archivePath)

        for (entry in entries) {
            // root-relative name of this entry, or null if not a direct child
            val childName = when {
                prefix.isEmpty() -> entry.name.takeIf { !it.contains('/') }
                else -> entry.name
                    .takeIf { it.startsWith("$prefix/") }
                    ?.removePrefix("$prefix/")
                    ?.takeIf { !it.contains('/') }
            } ?: continue

            val fullEntry = if (prefix.isEmpty()) childName else "$prefix/$childName"
            val childUri = node.uri + "/" + childName
            emit(
                FsNode(
                    id = childUri,
                    uri = childUri,
                    name = childName,
                    providerType = FsType.ARCHIVE,
                    parent = node,
                    metadata = FsMetadata(
                        sizeBytes = if (entry.isDirectory) null else entry.sizeBytes,
                        modifiedAt = entry.modifiedAt.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) },
                        isDirectory = entry.isDirectory,
                        extra = mapOf("entry" to fullEntry),
                    ),
                ),
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata =
        node.metadata ?: throw FsException.NotFound(node.uri)

    override fun open(node: FsNode): Source {
        val archivePath = node.metadata?.extra?.get("archive")
            ?: archivePathOf(node.uri)
        val entryName = node.metadata?.extra?.get("entry")
            ?: throw FsException.NotConnected("not an archive entry")
        return ArchiveEngine.openEntry(File(archivePath), entryName).source()
    }

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> =
        throw unsupported("writing into an archive")

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        throw unsupported("writing into an archive")

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        throw unsupported("renaming inside an archive")

    override suspend fun delete(node: FsNode, force: Boolean) {
        throw unsupported("deleting inside an archive (extract, then work on the copy)")
    }

    private fun unsupported(message: String): Nothing = throw FsException.Unsupported(message)

    /** `ee://archive/<path>…` → `/abs/path` of the archive file. */
    private fun archivePathOf(uri: String): String {
        val parsed = FsUri.parse(uri)
        require(parsed.type == FsType.ARCHIVE) { "wrong type: $uri" }
        return "/" + parsed.path
    }

    /** Root-relative entry prefix of a node ("" for the archive root). */
    private fun entryPrefixOf(uri: String, archivePath: String): String {
        val rest = uri.removePrefix("ee://archive/")
        return rest
            .removePrefix(archivePath.removePrefix("/"))
            .removePrefix("/")
    }
}
