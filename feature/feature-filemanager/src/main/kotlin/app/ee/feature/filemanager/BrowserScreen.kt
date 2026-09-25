package app.ee.feature.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.fs.FsRegistry
import app.ee.core.model.FsNode

/**
 * The file browser (M1 core screen — P0-3/4/5).
 *
 * @param startUri canonical VFS uri of the initial root
 * @param onBack close this browser (app backstack)
 * @param onOpenFile launch "open with" for a file node
 * @param onShare share selected file nodes
 * @param onSettings open settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    startUri: String,
    vfs: FsRegistry,
    onBack: () -> Unit,
    onOpenFile: (FsNode) -> Unit,
    onShare: (List<FsNode>) -> Unit,
    onSettings: () -> Unit,
) {
    val vm: BrowserViewModel = viewModel(
        factory = viewModelFactory {
            initializer { BrowserViewModel(vfs, startUri) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    var newFolderDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FsNode?>(null) }

    val visible = remember(state) { visibleChildren(state) }
    val inTrash = state.current?.uri?.endsWith("/${BrowserViewModel.TRASH_URI_SUFFIX}") == true

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = state.current?.name ?: "…",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        val canUp = vm.canGoUp()
                        IconButton(onClick = {
                            if (canUp) vm.up() else onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (state.search == null) {
                            IconButton(onClick = { vm.openSearch() }) {
                                Icon(Icons.Outlined.Search, contentDescription = "Search")
                            }
                            IconButton(onClick = {
                                vm.setViewMode(
                                    if (state.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST,
                                )
                            }) {
                                Icon(
                                    if (state.viewMode == ViewMode.LIST) Icons.Outlined.GridView
                                    else Icons.Outlined.ViewList,
                                    contentDescription = "Toggle view",
                                )
                            }
                        }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Outlined.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Sort by name") },
                                leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                                onClick = { menuOpen = false; vm.setSort(SortMode.NAME) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by size") },
                                leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                                onClick = { menuOpen = false; vm.setSort(SortMode.SIZE) },
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by date") },
                                leadingIcon = { Icon(Icons.Outlined.Sort, null) },
                                onClick = { menuOpen = false; vm.setSort(SortMode.DATE) },
                            )
                            DropdownMenuItem(
                                text = { Text("Select all") },
                                leadingIcon = { Icon(Icons.Outlined.Check, null) },
                                onClick = { menuOpen = false; vm.selectAll(visible) },
                            )
                            if (inTrash) {
                                DropdownMenuItem(
                                    text = { Text("Empty trash") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { menuOpen = false; vm.emptyTrash() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                leadingIcon = { Icon(Icons.Outlined.Description, null) },
                                onClick = { menuOpen = false; onSettings() },
                            )
                        }
                    },
                )
                if (state.search != null) {
                    SearchBar(
                        query = state.search.orEmpty(),
                        onQuery = vm::setSearchQuery,
                        onClose = vm::closeSearch,
                    )
                }
                state.busy?.let { busy ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(busy, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        floatingActionButton = {
            if (!state.selecting) {
                FloatingActionButton(onClick = { newFolderDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "New folder")
                }
            }
        },
        bottomBar = {
            if (state.selecting) {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${state.selection.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp),
                        )
                        val single = state.selection.size == 1
                        IconButton(
                            enabled = single,
                            onClick = {
                                visible.firstOrNull { it.id in state.selection }?.let {
                                    renameTarget = it
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rename")
                        }
                        IconButton(
                            enabled = visible.count { it.id in state.selection && !it.isDirectory } > 0,
                            onClick = {
                                onShare(visible.filter { it.id in state.selection })
                            },
                        ) {
                            Icon(Icons.Outlined.InsertDriveFile, contentDescription = "Share")
                        }
                        IconButton(onClick = { vm.deleteSelected() }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Delete")
                        }
                        TextButton(onClick = { vm.clearSelection() }) { Text("Done") }
                    }
                }
            }
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.error != null -> ErrorPane(
                    message = state.error.orEmpty(),
                    onDismiss = vm::dismissError,
                )

                visible.isEmpty() && !state.loading -> EmptyPane(hasFilter = state.search != null)

                state.viewMode == ViewMode.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible.size) { index ->
                        val node = visible[index]
                        GridItem(
                            node = node,
                            selected = node.id in state.selection,
                            onClick = {
                                if (state.selecting) vm.toggleSelect(node.id)
                                else if (node.isDirectory) vm.open(node) else onOpenFile(node)
                            },
                            onLongClick = { vm.toggleSelect(node.id) },
                        )
                    }
                }

                else -> LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(visible.size) { index ->
                        val node = visible[index]
                        ListItem(
                            node = node,
                            selected = node.id in state.selection,
                            onClick = {
                                if (state.selecting) vm.toggleSelect(node.id)
                                else if (node.isDirectory) vm.open(node) else onOpenFile(node)
                            },
                            onLongClick = { vm.toggleSelect(node.id) },
                        )
                    }
                }
            }
        }
    }

    if (newFolderDialog) {
        NameDialog(
            title = "New folder",
            onConfirm = { name ->
                newFolderDialog = false
                vm.newFolder(name)
            },
            onDismiss = { newFolderDialog = false },
        )
    }
    renameTarget?.let { target ->
        NameDialog(
            title = "Rename",
            initial = target.name,
            onConfirm = { name ->
                renameTarget = null
                vm.rename(target, name)
            },
            onDismiss = { renameTarget = null },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            placeholder = { Text("Search in this folder") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "Close search")
        }
    }
}

@Composable
private fun ErrorPane(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

@Composable
private fun EmptyPane(hasFilter: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (hasFilter) "No matches" else "This folder is empty",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun fileIcon(node: FsNode): ImageVector {
    if (node.isDirectory) return Icons.Outlined.Folder
    return when (LocalPathsExtension(node.name)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> Icons.Outlined.Image
        "mp4", "mkv", "webm", "avi", "mov", "3gp" -> Icons.Outlined.Movie
        "mp3", "ogg", "flac", "wav", "m4a" -> Icons.Outlined.MusicNote
        "zip", "7z", "rar", "tar", "gz", "bz2" -> Icons.Outlined.Archive
        "apk" -> Icons.Outlined.Android
        "txt", "md", "json", "xml", "log", "csv", "kt", "java" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
}

@Composable
private fun ListItem(
    node: FsNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                fileIcon(node),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val size = formatSize(node.metadata?.sizeBytes)
                val date = formatDate(node.metadata?.modifiedAt?.toEpochMilli())
                val sub = listOf(size, date).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GridItem(
    node: FsNode,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                fileIcon(node),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                node.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value.trim()) },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Local helper (keeps the screen file self-contained). */
private fun LocalPathsExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot in 1 until name.length) name.substring(dot + 1).lowercase() else ""
}
