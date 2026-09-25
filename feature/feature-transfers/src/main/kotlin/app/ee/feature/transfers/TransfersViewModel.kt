package app.ee.feature.transfers

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import app.ee.core.db.TransferDao
import app.ee.core.db.TransferEntity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
 * Download manager (M3b — P1-9): the queue lives in Room and the engine runs
 * in [TransferWorker] (WorkManager) — transfers survive configuration
 * changes and process death. The Room table is the single source of truth;
 * the UI model here is a derived view that also carries optimistic entries
 * for a split second after `add`.
 *
 * Pause keeps the partial file (worker cancelled, state PAUSED); resume
 * re-enqueues and [DownloadEngine] continues via HTTP Range.
 */
class TransfersViewModel(
    appContext: Context,
    private val dao: TransferDao,
) : ViewModel() {

    private val downloadsDir: File =
        appContext.getExternalFilesDir(null) ?: appContext.filesDir

    private val wm: WorkManager = WorkManager.getInstance(appContext)

    private val _jobs = MutableStateFlow<Map<String, DownloadJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DownloadJob>> = _jobs

    init {
        viewModelScope.launch {
            dao.observeAll().collect { entities ->
                _jobs.update { current ->
                    entities.associate { e ->
                        val fromDb = toJob(e)
                        current[e.id]?.let {
                            it.copy(
                                state = fromDb.state,
                                bytesDone = fromDb.bytesDone,
                                bytesTotal = fromDb.bytesTotal,
                                error = fromDb.error,
                            )
                        } ?: fromDb
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
        enqueue(id)
    }

    fun pause(id: String) {
        wm.cancelUniqueWork(TransferWorker.uniqueWorkName(id))
        updateState(id) { it.copy(state = DownloadJob.State.PAUSED, error = null) }
    }

    fun resume(id: String) {
        updateState(id) { it.copy(state = DownloadJob.State.RUNNING, error = null) }
        enqueue(id)
    }

    fun cancel(id: String) {
        wm.cancelUniqueWork(TransferWorker.uniqueWorkName(id))
        _jobs.value[id]?.let { job ->
            File(downloadsDir, job.fileName).takeIf { it.exists() }?.delete()
        }
        updateState(id) { it.copy(state = DownloadJob.State.CANCELLED) }
    }

    fun removeFinished(id: String) {
        _jobs.update { it - id }
        viewModelScope.launch { dao.remove(id) }
    }

    private fun enqueue(id: String) {
        val request = OneTimeWorkRequestBuilder<TransferWorker>()
            .setInputData(workDataOf(TransferWorker.KEY_ID to id))
            .build()
        wm.enqueueUniqueWork(
            TransferWorker.uniqueWorkName(id),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun updateState(id: String, transform: (DownloadJob) -> DownloadJob) {
        val job = _jobs.value[id] ?: return
        val next = transform(job)
        _jobs.value = _jobs.value + (id to next)
        persistJob(next)
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
