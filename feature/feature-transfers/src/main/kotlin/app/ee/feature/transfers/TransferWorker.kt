package app.ee.feature.transfers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ee.core.db.EeDatabase
import app.ee.core.net.HttpClientFactory
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background download (M3b — P1-9): WorkManager drives [DownloadEngine] so
 * transfers outlive the UI and process death. Progress is persisted to Room
 * at ~2 Hz; the UI observes the table (single source of truth).
 */
class TransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val appContext = applicationContext
        val dao = EeDatabase.get(appContext).transfers()
        val entity = dao.getById(id) ?: return Result.failure()
        val file = File(entity.toPath, entity.fromUri.substringAfterLast('/').ifEmpty { id })

        var lastEmit = 0L
        return try {
            val (done, total) = withContext(Dispatchers.IO) {
                DownloadEngine.run(
                    client = HttpClientFactory.default(),
                    file = file,
                    url = entity.fromUri,
                ) { d, t ->
                    val now = System.currentTimeMillis()
                    if (now - lastEmit >= 500 || (t > 0 && d == t)) {
                        lastEmit = now
                        dao.upsert(
                            entity.copy(
                                state = DownloadJob.State.RUNNING.name,
                                bytesDone = d,
                                bytesTotal = t,
                            ),
                        )
                    }
                }
            }
            dao.upsert(
                entity.copy(
                    state = DownloadJob.State.DONE.name,
                    bytesDone = done,
                    bytesTotal = total,
                    error = null,
                ),
            )
            Result.success()
        } catch (e: CancellationException) {
            // paused/cancelled from the UI — state is owned by the ViewModel
            Result.failure()
        } catch (e: Exception) {
            dao.upsert(entity.copy(state = DownloadJob.State.FAILED.name, error = e.message ?: "failed"))
            Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "transfer_id"

        fun uniqueWorkName(id: String): String = "ee-download-$id"
    }
}
