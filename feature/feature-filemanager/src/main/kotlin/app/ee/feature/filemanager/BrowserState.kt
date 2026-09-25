package app.ee.feature.filemanager

import app.ee.core.model.FsNode

/** Browser presentation state (MVVM, docs/02-specification.md §4.3). */
data class BrowserState(
    val current: FsNode? = null,
    val children: List<FsNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    /** Selected node ids (multi-select). */
    val selection: Set<String> = emptySet(),
    val viewMode: ViewMode = ViewMode.LIST,
    val sort: SortMode = SortMode.NAME,
    /** `null` = search closed. */
    val search: String? = null,
    /** Non-null while an operation runs, e.g. "Moving 3 items…". */
    val busy: String? = null,
) {
    val selecting: Boolean get() = selection.isNotEmpty()
}

enum class ViewMode { LIST, GRID }

enum class SortMode { NAME, SIZE, DATE }

/**
 * Pure derivation (JVM-testable): apply search filter + sort to listed
 * children. Directories always come first, then the requested sort key.
 */
fun visibleChildren(state: BrowserState): List<FsNode> {
    var list = state.children
    val query = state.search?.trim()?.takeIf { it.isNotEmpty() }
    if (query != null) {
        list = list.filter { it.name.contains(query, ignoreCase = true) }
    }
    return list.sortedWith(
        compareByDescending<FsNode> { it.isDirectory }
            .thenBy { state.sort.key(it) },
    )
}

private fun SortMode.key(node: FsNode): Comparable<Any> = when (this) {
    SortMode.NAME -> node.name.lowercase()
    SortMode.SIZE -> (node.metadata?.sizeBytes ?: Long.MIN_VALUE).toLong()
    SortMode.DATE -> (node.metadata?.modifiedAt?.toEpochMilli() ?: Long.MIN_VALUE).toLong()
}

fun formatSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

fun formatDate(epochMillis: Long?): String {
    if (epochMillis == null || epochMillis <= 0) return ""
    val sdf = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(epochMillis))
}
