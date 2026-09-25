package app.ee.feature.filemanager

import app.ee.core.db.RecentFileDao
import app.ee.core.fs.FsException
import app.ee.core.fs.FsRegistry
import app.ee.core.fs.FsUri
import app.ee.core.model.ClipboardEntry
import app.ee.core.model.FsKind
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okio.Buffer

/**
 * Browser ViewModel: one instance per opened browser (the `app` Router gives
 * each Browser screen its own backstack entry + ViewModel).
 */
class BrowserViewModel(
    private val vfs: FsRegistry,
    startUri: String,
    private val recentDao: RecentFileDao? = null,
    private val appClipboard: StateFlow<List<ClipboardEntry>>,
    private val setClipboard: (List<ClipboardEntry>) -> Unit,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserState())
    val state: StateFlow<BrowserState> = _state

    /** Clipboard shared across browser instances (app-scoped). */
    private val _clipboardCount = MutableStateFlow(0)
    val clipboardCount: StateFlow<Int> = _clipboardCount
    private val pendingMove = MutableStateFlow(false)

    private var listJob: Job? = null
    private var current: FsNode? = null

    init {
        openUri(startUri)
        viewModelScope.launch {
            appClipboard.collect { _clipboardCount.value = it.size }
        }
    }

    // ── copy / move (M3 — P0-5 ops) ────────────────────────────────────────

    fun copySelected() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        pendingMove.value = false
        setClipboard(nodes.map { it.toClipboardEntry() })
        clearSelection()
    }

    fun moveSelected() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        pendingMove.value = true
        setClipboard(nodes.map { it.toClipboardEntry() })
        clearSelection()
    }

    fun clearClipboard() {
        setClipboard(emptyList())
    }

    /**
     * Paste the clipboard into the current directory. M3: files only —
     * directory trees ride the transfer station (M3b). Whole-file copy
     * (source buffered in memory; chunked streaming lands with transfers).
     */
    fun paste() {
        val entries = _clipboardCount.value
        if (entries <= 0) return
        val target = current ?: return
        if (!target.isDirectory) return
        val items = clipboardSnapshot()
        if (items.isEmpty()) return
        operate(
            if (pendingMove.value) "Moving ${items.size} item(s)…"
            else "Copying ${items.size} item(s)…",
        ) {
            items.forEach { item ->
                val srcProvider = vfs.provider(item.type)
                val srcNode = FsNode(
                    id = item.uri,
                    uri = item.uri,
                    name = item.name,
                    providerType = item.type,
                    metadata = FsMetadata(sizeBytes = item.sizeBytes, isDirectory = false, extra = item.extra),
                )
                val buffer = Buffer()
                srcProvider.open(srcNode).use { buffer.readFrom(it) }
                val childUri = target.uri + "/" + item.name
                val child = FsNode(
                    id = childUri,
                    uri = childUri,
                    name = item.name,
                    providerType = target.providerType,
                )
                vfs.provider(target.providerType).write(child, buffer).collect {}
                if (pendingMove.value) {
                    srcProvider.delete(srcNode, force = false)
                }
            }
            setClipboard(emptyList())
            list()
        }
    }

    private fun clipboardSnapshot(): List<ClipboardEntry> =
        appClipboard.value

    private fun FsNode.toClipboardEntry(): ClipboardEntry =
        ClipboardEntry(
            uri = uri,
            name = name,
            type = providerType,
            isDirectory = isDirectory,
            sizeBytes = metadata?.sizeBytes,
            extra = metadata?.extra ?: emptyMap(),
        )

    /** Open a fresh root/uri (used at start and when navigating from home). */
    fun openUri(uri: String) {
        val type = FsUri.parse(uri).type
        current = FsNode(
            id = uri,
            uri = uri,
            name = uri.substringAfterLast('/').ifEmpty { "Root" },
            providerType = type,
        )
        list()
    }

    fun open(node: FsNode) {
        if (node.isDirectory) {
            current = node
            list()
        } else {
            recordRecent(node)
        }
        // files: handled by the UI layer (open-with intents) — see FileActions
    }

    /** Files opened from the browser land in the home "Recent" list (M2). */
    private fun recordRecent(node: FsNode) {
        val dao = recentDao ?: return
        viewModelScope.launch {
            runCatching {
                dao.touch(
                    uri = node.uri,
                    name = node.name,
                    fsType = node.providerType.name,
                    sizeBytes = node.metadata?.sizeBytes,
                    lastOpenedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    fun up() {
        val parent = current?.parent ?: return
        current = parent
        list()
    }

    fun canGoUp(): Boolean = _state.value.current?.parent != null

    fun toggleSelect(id: String) {
        _state.update {
            val sel = it.selection.toMutableSet()
            if (id !in sel) sel.add(id) else sel.remove(id)
            it.copy(selection = sel)
        }
    }

    fun selectAll(visible: List<FsNode>) {
        _state.update { it.copy(selection = visible.mapTo(mutableSetOf()) { it.id }) }
    }

    fun clearSelection() {
        _state.update { it.copy(selection = emptySet()) }
    }

    fun setViewMode(mode: ViewMode) {
        _state.update { it.copy(viewMode = mode) }
    }

    fun setSort(sort: SortMode) {
        _state.update { it.copy(sort = sort) }
    }

    fun openSearch(initial: String = "") {
        _state.update { it.copy(search = initial) }
    }

    fun closeSearch() {
        _state.update { it.copy(search = null) }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(search = query) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    fun newFolder(name: String) {
        operate("Creating folder…") {
            val parent = requireCurrent()
            val created = vfs.provider(parent.providerType).create(parent, FsKind.DIRECTORY, name)
            current = created
            list()
        }
    }

    fun rename(node: FsNode, newName: String) {
        require(newName.isNotBlank()) { "empty name" }
        operate("Renaming…") {
            vfs.provider(node.providerType).rename(node, newName)
            list()
        }
    }

    /** Delete all selected nodes (moves to trash for local roots). */
    fun deleteSelected() {
        val selected = selectedNodes()
        if (selected.isEmpty()) return
        operate(if (selected.size == 1) "Deleting…" else "Deleting ${selected.size} items…") {
            // deepest first so parents don't shadow children
            selected.sortedByDescending { it.uri.length }.forEach { node ->
                vfs.provider(node.providerType).delete(node, force = false)
            }
            clearSelection()
            list()
        }
    }

    /** Only shown when the browser is inside the local trash. */
    fun emptyTrash() {
        val node = current ?: return
        if (!node.uri.endsWith("/" + TRASH_URI_SUFFIX)) return
        operate("Emptying trash…") {
            vfs.provider(node.providerType).list(node).collect { child ->
                vfs.provider(child.providerType).delete(child, force = true)
            }
            list()
        }
    }

    private fun selectedNodes(): List<FsNode> =
        _state.value.children.filter { it.id in _state.value.selection }

    private fun requireCurrent(): FsNode =
        current ?: throw FsException.NotConnected("browser has no current node")

    private fun list() {
        val node = current ?: return
        listJob?.cancel()
        _state.update {
            it.copy(current = node, children = emptyList(), loading = true, error = null, selection = emptySet())
        }
        listJob = viewModelScope.launch {
            try {
                val provider = vfs.provider(node.providerType)
                val items = provider.list(node).toList()
                _state.update { it.copy(children = items, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        loading = false,
                        error = e.message ?: e::class.simpleName ?: "Unknown error",
                    )
                }
            }
        }
    }

    private fun operate(label: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = label) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = null, error = e.message ?: "Operation failed") }
            } finally {
                _state.update { it.copy(busy = null) }
            }
        }
    }

    companion object {
        const val TRASH_URI_SUFFIX = ".ee_trash"
    }
}
