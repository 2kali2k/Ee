package app.ee.core.model

import java.util.UUID

/**
 * A unit of work in the transfer station (downloads, uploads, copies, moves,
 * archive create/extract). Persisted in Room (core-database) and driven by
 * WorkManager (feature-transfers).
 */
data class TransferTask(
    val id: UUID = UUID.randomUUID(),
    val kind: Kind,
    val from: FsNode?,
    val to: FsNode,
    val state: State = State.QUEUED,
    /** 0.0..1.0; `null` until the total size is known. */
    val progress: Double? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
) {
    enum class Kind {
        DOWNLOAD,
        UPLOAD,
        COPY,
        MOVE,
        CREATE_ARCHIVE,
        EXTRACT_ARCHIVE,
        AUTO_BACKUP,
    }

    enum class State {
        QUEUED,
        RUNNING,
        PAUSED,
        CANCELLED,
        DONE,
        FAILED,
    }

    val isActive: Boolean
        get() = state == State.QUEUED || state == State.RUNNING || state == State.PAUSED
}
