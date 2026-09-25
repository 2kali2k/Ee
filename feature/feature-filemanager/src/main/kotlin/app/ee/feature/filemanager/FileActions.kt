package app.ee.feature.filemanager

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.ee.core.model.FsNode
import java.io.File

/**
 * Outbound intents for file nodes (P0-9). File URIs must go through a
 * FileProvider (file:// is blocked by FUSE); the `app` module declares it
 * with authority `<applicationId>.fileprovider`.
 */
object FileActions {

    fun openFile(context: Context, node: FsNode) {
        val file = toFile(node) ?: return
        val uri = providerUri(context, file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, guessMime(node.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Open with"))
    }

    fun shareFiles(context: Context, nodes: List<FsNode>) {
        val intent = if (nodes.size == 1) {
            Intent(Intent.ACTION_SEND)
                .setType(guessMime(nodes[0].name))
                .putExtra(Intent.EXTRA_STREAM, providerUri(context, toFile(nodes[0]) ?: return))
        } else {
            val uris = nodes.mapNotNull { toFile(it)?.let { f -> providerUri(context, f) } }
            if (uris.isEmpty()) return
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    private fun providerUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** The `extra` map from provider-local carries the absolute path. */
    private fun toFile(node: FsNode): File? =
        node.metadata?.extra?.get("path")?.let(::File)?.takeIf { it.exists() }

    private val EXTENSION_MIMES = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "mp4" to "video/mp4", "mkv" to "video/x-matroska", "webm" to "video/webm",
        "avi" to "video/x-msvideo", "mov" to "video/quicktime", "3gp" to "video/3gpp",
        "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "flac" to "audio/flac",
        "wav" to "audio/wav", "m4a" to "audio/mp4",
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "zip" to "application/zip", "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar", "tar" to "application/x-tar",
        "gz" to "application/gzip", "apk" to "application/vnd.android.package-archive",
        "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json",
        "xml" to "application/xml", "html" to "text/html", "csv" to "text/csv",
        "kt" to "text/x-kotlin", "java" to "text/x-java",
    )

    fun guessMime(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot in 1 until name.length) {
            EXTENSION_MIMES[name.substring(dot + 1).lowercase()]?.let { return it }
        }
        return "application/octet-stream"
    }
}
