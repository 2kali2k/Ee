package app.ee.feature.transfers

import android.content.Context
import android.os.Environment
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.ee.core.db.AutoBackupEntity
import app.ee.core.db.EeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tree mirror used by [AutoBackupWorker] (M5 — P1-13): copy every file under
 * [source] to [dest] preserving relative paths, skipping files whose size
 * and mtime already match (cheap incremental check).
 *
 * Pure JVM apart from [Environment] at the call site — the core is
 * unit-testable on the host.
 */
object BackupMirror {

    /**
     * @return number of files copied (0 when already in sync)
     * @throws IllegalArgumentException when source and destination overlap
     */
    fun mirror(source: File, dest: File): Int {
        require(source.isDirectory) { "source not a directory: ${source.absolutePath}" }
        if (dest.isDirectory) {
            val s = source.absolutePath
            val d = dest.absolutePath
            require(!s.startsWith(d + File.separator) && !d.startsWith(s + File.separator)) {
                "backup destination must not be inside the source (or vice versa)"
            }
        }
        if (!dest.exists()) require(dest.mkdirs()) { "cannot create dest ${dest.absolutePath}" }

        var copied = 0
        source.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.relativeTo(source).path
            val target = File(dest, rel)
            val fresh = target.exists() &&
                target.length() == f.length() &&
                target.lastModified() >= f.lastModified()
            if (!fresh) {
                target.parentFile?.mkdirs()
                f.copyTo(target, overwrite = true)
                copied++
            }
        }
        return copied
    }
}

/**
 * Periodic auto-backup (M5 — P1-13): WorkManager schedules [mirror] for each
 * enabled job (daily or weekly). Results land in the Room row's
 * [AutoBackupEntity.lastStatus], which the Settings UI displays.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val dao = EeDatabase.get(applicationContext).autoBackups()
        val job = dao.getById(id) ?: return Result.failure()
        return try {
            val (copied, note) = withContext(Dispatchers.IO) {
                val root = Environment.getExternalStorageDirectory()
                val source = File(root, job.sourcePath)
                val dest = File(root, job.destPath)
                if (!source.isDirectory) {
                    0 to "source missing"
                } else {
                    val n = BackupMirror.mirror(source, dest)
                    n to null
                }
            }
            dao.upsert(
                job.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastStatus = if (note != null) note else "ok: $copied copied",
                ),
            )
            if (note != null) Result.retry() else Result.success()
        } catch (e: Exception) {
            dao.upsert(
                job.copy(
                    lastRunAt = System.currentTimeMillis(),
                    lastStatus = e.message ?: "failed",
                ),
            )
            Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "backup_id"

        fun uniqueName(id: String): String = "ee-backup-$id"
    }
}
