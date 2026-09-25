package app.ee.feature.transfers

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Shared download core (M3b — P1-9 background transfers): HTTP Range resume
 * (206 → append, 200 → restart). Used by [TransferWorker] (WorkManager) so
 * downloads survive configuration changes and process death.
 *
 * Cancel co-operatively: the caller cancels the worker's coroutine; a
 * blocking socket read keeps running until the next byte or the 60 s read
 * timeout (flagged M3 limitation).
 *
 * @return (bytesDone, bytesTotal) of the finished download
 */
object DownloadEngine {

    suspend fun run(
        client: OkHttpClient,
        file: File,
        url: String,
        onProgress: suspend (done: Long, total: Long) -> Unit,
    ): Pair<Long, Long> {
        val startAt = file.length()
        var done = startAt
        var total = 0L
        val request = Request.Builder()
            .url(url)
            .apply { if (startAt > 0) header("Range", "bytes=$startAt-") }
            .build()
        client.newCall(request).execute().use { response ->
            val code = response.code
            val resume = code == 206
            if (!resume && code != 200) throw IllegalStateException("HTTP $code")
            if (!resume && startAt > 0) {
                // server ignored Range — restart from scratch
                file.delete()
                done = 0
            }
            val contentRange = response.header("Content-Range")
            total = if (contentRange != null) {
                contentRange.substringAfterLast('/').toLongOrNull()
                    ?: (response.body?.contentLength() ?: 0L) + done
            } else {
                (response.body?.contentLength() ?: 0L) + done
            }
            val body = response.body ?: throw IllegalStateException("empty body")
            FileOutputStream(file, resume).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (isActive) {
                    val n = body.source().read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
            if (!isActive) throw kotlinx.coroutines.CancellationException("download cancelled")
        }
        return done to total
    }
}
