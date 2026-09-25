package app.ee.feature.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.ee.core.db.TransferDao
import app.ee.core.db.TransferEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** UI model of a running/queued download. */
data class DownloadJob(
    val id: String,
    val url: String,
    val fileName: String,
    val state: State,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String? = null,
) {
    enum class State { RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    val progress: Float
        get() = if (bytesTotal > 0) bytesDone.toFloat() / bytesTotal.toFloat() else 0f
}

/**
 * Download manager (M2 — P1-9): OkHttp with HTTP Range resume. Pause keeps
 * the partial file; resume continues from `bytesDone`. Jobs live in Room so
 * the queue survives process death; in M2 the engine runs in the ViewModel
 * scope (WorkManager background execution lands in M3).
 *
 * Known M2 limitation: a blocking OkHttp request is only interrupted when
 * it returns a byte; a hanging server stalls pause/cancel until the socket
 * times out (client read timeout = 60 s).
 */
class TransfersViewModel(
    appContext: Context,
    private val client: OkHttpClient,
    private val dao: TransferDao,
) : ViewModel() {

    private val downloadsDir: File =
        appContext.getExternalFilesDir(null) ?: appContext.filesDir

    private val _jobs = MutableStateFlow<Map<String, DownloadJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DownloadJob>> = _jobs

    private val engineJobs = mutableMapOf<String, Job>()

    init {
        viewModelScope.launch {
            dao.observeAll().collect { entities ->
                _jobs.update { current ->
                    entities.associate { e ->
                        e.id to (current[e.id] ?: toJob(e))
                    }
                }
            }
        }
    }

    fun add(url: String) {
        val id = UUID.randomUUID().toString()
        val fileName = url.substringAfterLast('/').ifEmpty { "download-$id" }
        val job = DownloadJob(id, url, fileName, DownloadJob.State.RUNNING, 0L, 0L)
        _jobs.update { it + (id to job) }
        persistJob(job)
        run(id)
    }

    fun pause(id: String) {
        engineJobs[id]?.cancel()
        engineJobs.remove(id)
        _jobs.update { it + (id to it[id]?.copy(state = DownloadJob.State.PAUSED)) }
        persistState(id)
    }

    fun resume(id: String) {
        val job = _jobs.value[id] ?: return
        _jobs.update { it + (id to job.copy(state = DownloadJob.State.RUNNING, error = null)) }
        run(id)
    }

    fun cancel(id: String) {
        engineJobs[id]?.cancel()
        engineJobs.remove(id)
        val job = _jobs.value[id] ?: return
        downloadsDir.resolve(job.fileName).takeIf { it.exists() }?.delete()
        _jobs.update { it + (id to job.copy(state = DownloadJob.State.CANCELLED)) }
        persistState(id)
    }

    fun removeFinished(id: String) {
        _jobs.update { it - id }
        viewModelScope.launch { dao.remove(id) }
    }

    private fun run(id: String) {
        val job = _jobs.value[id] ?: return
        engineJobs[id]?.cancel()
        val file = downloadsDir.resolve(job.fileName)
        val jobHandle = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { performDownload(id, job, file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _jobs.update {
                    it + (id to it[id]?.copy(state = DownloadJob.State.FAILED, error = e.message ?: "failed"))
                }
                persistState(id)
            } finally {
                engineJobs.remove(id)
            }
        }
        engineJobs[id] = jobHandle
    }

    private suspend fun performDownload(id: String, job: DownloadJob, file: File) {
        val startAt = file.length()
        var done = startAt
        var total = 0L
        val request = Request.Builder()
            .url(job.url)
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
                    _jobs.update {
                        it + (id to it[id]?.copy(state = DownloadJob.State.RUNNING, bytesDone = done, bytesTotal = total))
                    }
                }
            }
            // pause/cancel lands here when the blocking read returns — the
            // partial file is kept for resume, so don't mark it done
            if (!isActive) return
            _jobs.update { it + (id to it[id]?.copy(state = DownloadJob.State.DONE, bytesDone = done, bytesTotal = total)) }
            persistState(id)
        }
    }

    /** Re-reads the job's latest fields and upserts the Room row. */
    private fun persistState(id: String) {
        _jobs.value[id]?.let { persistJob(it) }
    }

    private fun persistJob(job: DownloadJob) {
        viewModelScope.launch {
            dao.upsert(
                TransferEntity(
                    id = job.id,
                    kind = "DOWNLOAD",
                    fromUri = job.url,
                    toPath = downloadsDir.absolutePath,
                    state = job.state.name,
                    bytesDone = job.bytesDone,
                    bytesTotal = job.bytesTotal,
                    error = job.error,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun toJob(e: TransferEntity): DownloadJob = DownloadJob(
        id = e.id,
        url = e.fromUri,
        fileName = e.fromUri.substringAfterLast('/').ifEmpty { e.id },
        state = runCatching { DownloadJob.State.valueOf(e.state) }
            .getOrDefault(DownloadJob.State.PAUSED),
        bytesDone = e.bytesDone,
        bytesTotal = e.bytesTotal,
        error = e.error,
    )
}
