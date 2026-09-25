package app.ee.core.fs

import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType
import app.ee.core.model.FsCapability
import kotlinx.coroutines.flow.Flow
import okio.BufferedSink
import okio.Source

/** Streaming progress of a write operation. `total` is `null` when unknown. */
data class Progress(val done: Long, val total: Long? = null) {
    val fraction: Double? get() = total?.takeIf { it > 0 }?.let { done.toDouble() / it }
}

/**
 * A mountable filesystem root. This is the spine of the app (see
 * docs/02-specification.md §4.2): one browser UI, N providers.
 *
 * Contract:
 *  - Implementations are created once and reused; they must be thread-safe.
 *  - All [Flow]s are cold and re-runnable; collectors get the latest state.
 *  - Errors are thrown (typed [FsException]s) — never swallowed.
 *  - [capabilities] drives what the UI offers; UI must not assume more.
 */
interface FsProvider {

    val type: FsType
    val capabilities: Set<FsCapability>

    /**
     * Connect/mount and return the root node.
     * @param uri canonical root URI, `ee://<type>/...` (see [FsUri]).
     */
    suspend fun root(uri: String): FsNode

    /**
     * List a directory, emitting every child. Emits one element per child;
     * the provider decides batching internally.
     */
    fun list(node: FsNode): Flow<FsNode>

    /** Fresh metadata for a node (a re-stat). */
    suspend fun metadata(node: FsNode): FsMetadata

    /** Open a file for reading. The caller closes the returned [Source]. */
    fun open(node: FsNode): Source

    /**
     * Write `bytes` to `node` (truncating/creating as the provider defines).
     * Emits [Progress] while the provider consumes the source.
     */
    suspend fun write(node: FsNode, bytes: Source): Flow<Progress>

    /** Create a file or directory inside [node] (which must be a directory). */
    suspend fun create(node: FsNode, kind: FsKind, name: String): FsNode

    suspend fun rename(node: FsNode, newName: String): FsNode

    /** Delete. Non-empty directories are rejected unless [force]. */
    suspend fun delete(node: FsNode, force: Boolean)
}

/** Base type for all VFS errors; UI maps these to user-facing messages. */
sealed class FsException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotConnected(message: String = "Not connected", cause: Throwable? = null) :
        FsException(message, cause)

    class NotFound(nodeUri: String) : FsException("Not found: $nodeUri")

    class Unauthorized(message: String = "Access denied", cause: Throwable? = null) :
        FsException(message, cause)

    class Unsupported(message: String, cause: Throwable? = null) :
        FsException(message, cause)

    class Remote(message: String, cause: Throwable? = null) :
        FsException(message, cause)
}
