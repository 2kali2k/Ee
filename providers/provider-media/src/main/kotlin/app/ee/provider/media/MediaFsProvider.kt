package app.ee.provider.media

import android.content.Context
import android.provider.MediaStore
import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.Progress
import app.ee.core.model.FsCapability
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okio.Source

/**
 * Media library roots over MediaStore (M2 — P1-10). Categories:
 *
 *   `ee://media/images` · `ee://media/videos` · `ee://media/audio`
 *
 * Nodes carry the real `content://` URI in metadata.extra["contentUri"] —
 * that's what players and thumbnails use. This provider is read-only and
 * does not stream bytes itself (MediaStore content is handed to the
 * platform's media pipeline).
 */
class MediaFsProvider(private val context: Context) : FsProvider {

    override val type: FsType = FsType.MEDIA

    override val capabilities: Set<FsCapability> = setOf(
        FsCapability.READ,
        FsCapability.STREAM,
    )

    override suspend fun root(uri: String): FsNode = withContext(Dispatchers.IO) {
        val category = categoryOf(uri)
        FsNode(
            id = uri,
            uri = uri,
            name = category.displayName,
            providerType = FsType.MEDIA,
            metadata = FsMetadata(isDirectory = true, extra = mapOf("category" to category.name)),
        )
    }

    override fun list(node: FsNode): Flow<FsNode> = flow {
        val category = node.metadata?.extra?.get("category")
            ?.let { runCatching { MediaCategory.valueOf(it) }.getOrNull() }
            ?: categoryOf(node.uri)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
        val query = category.query
        val cursor = withContext(Dispatchers.IO) {
            context.contentResolver.query(
                query.collection, projection, query.selection, query.args, sortOrder,
            )
                ?: throw FsException.NotConnected("media unavailable")
        }
        cursor.use { c ->
            val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val dateIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val mimeIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idIdx)
                val contentUri = query.collection.buildUpon()
                    .appendPath(id.toString()).build().toString()
                emit(
                    FsNode(
                        id = node.uri + "/$id",
                        uri = node.uri + "/$id",
                        name = c.getString(nameIdx) ?: "item $id",
                        providerType = FsType.MEDIA,
                        parent = node,
                        metadata = FsMetadata(
                            sizeBytes = if (c.isNull(sizeIdx)) null else c.getLong(sizeIdx),
                            // MediaStore DATE_MODIFIED is in seconds
                            modifiedAt = c.getLong(dateIdx).takeIf { it > 0 }?.let { it * 1000 }
                                ?.let { java.time.Instant.ofEpochMilli(it) },
                            isDirectory = false,
                            mimeType = c.getString(mimeIdx),
                            extra = mapOf("contentUri" to contentUri),
                        ),
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun metadata(node: FsNode): FsMetadata =
        node.metadata ?: throw FsException.NotFound(node.uri)

    override fun open(node: FsNode): Source =
        throw FsException.Unsupported("use metadata.extra[contentUri] with the media pipeline")

    override suspend fun write(node: FsNode, bytes: Source): Flow<Progress> =
        throw FsException.Unsupported("MediaStore roots are read-only")

    override suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode =
        throw FsException.Unsupported("MediaStore roots are read-only")

    override suspend fun rename(node: FsNode, newName: String): FsNode =
        throw FsException.Unsupported("MediaStore roots are read-only")

    override suspend fun delete(node: FsNode, force: Boolean) {
        throw FsException.Unsupported("MediaStore roots are read-only (delete via the local path)")
    }

    private fun categoryOf(uri: String): MediaCategory {
        val cat = uri.removePrefix("ee://media/").substringBefore('/')
        return MediaCategory.entries.firstOrNull { it.name.equals(cat, ignoreCase = true) }
            ?: throw FsException.Unsupported("unknown media category in $uri")
    }

    private enum class MediaCategory(val displayName: String, val query: MediaCategoryQuery) {
        IMAGES("Images", MediaCategoryQuery.Images),
        VIDEOS("Videos", MediaCategoryQuery.Videos),
        AUDIO("Audio", MediaCategoryQuery.Audio),
    }

    private enum class MediaCategoryQuery(
        val collection: android.net.Uri,
        val selection: String? = null,
        val args: Array<String>? = null,
    ) {
        Images(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("0"),
        ),
        Videos(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.IS_PENDING} = 0",
            arrayOf("0"),
        ),
        Audio(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
        ),
    }
}
