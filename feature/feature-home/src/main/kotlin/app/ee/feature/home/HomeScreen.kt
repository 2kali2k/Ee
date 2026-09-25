package app.ee.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.db.EeDatabase
import app.ee.core.db.RecentFileEntity
import app.ee.core.model.FsMetadata
import app.ee.core.model.FsNode
import app.ee.core.model.FsType

/**
 * Home (docs/02-specification.md §3.1): storage roots + library entries +
 * recents. Entries whose milestones have not landed yet render as disabled
 * placeholders — honest progress over fake buttons.
 */

enum class RootTarget {
    BROWSER,
    MEDIA,
    TRANSFERS,
    NETWORK,
}

data class HomeRoot(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val target: RootTarget,
    val uri: String?,
)

class HomeViewModel(database: EeDatabase) : ViewModel() {
    val recents = database.recentFiles().observeRecent(10)
        .stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    database: EeDatabase,
    onRootClick: (HomeRoot) -> Unit,
    onSettingsClick: () -> Unit,
    onOpenFile: (FsNode) -> Unit,
) {
    val vm: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(database) } },
    )
    val recents by vm.recents.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EE") },
                actions = {
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionTitle("Recent")
            val recentList = recents
            if (recentList.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "Files you open will appear here",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        recentList.forEach { entity ->
                            val isLast = entity == recentList.last()
                            RecentRow(
                                entity = entity,
                                onClick = { onOpenFile(entity.toNode()) },
                            )
                            if (!isLast) {
                                HorizontalDivider(
                                    Modifier.padding(horizontal = 12.dp),
                                    thickness = HorizontalDividerDefaultsThickness,
                                )
                            }
                        }
                    }
                }
            }

            SectionTitle("Storage")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    RootTile(
                        title = "Internal",
                        subtitle = "Primary storage",
                        icon = Icons.Outlined.Folder,
                        enabled = true,
                        onClick = {
                            onRootClick(
                                HomeRoot(
                                    "Internal",
                                    "Primary storage",
                                    Icons.Outlined.Folder,
                                    RootTarget.BROWSER,
                                    "ee://local/storage/emulated/0",
                                ),
                            )
                        },
                    )
                }
                item {
                    RootTile(
                        title = "Trash",
                        subtitle = "Deleted items",
                        icon = Icons.Outlined.Delete,
                        enabled = true,
                        onClick = {
                            onRootClick(
                                HomeRoot(
                                    "Trash",
                                    "Deleted items",
                                    Icons.Outlined.Delete,
                                    RootTarget.BROWSER,
                                    "ee://local/storage/emulated/0/.ee_trash",
                                ),
                            )
                        },
                    )
                }
                item {
                    RootTile(
                        title = "Downloads",
                        subtitle = "Transfer station",
                        icon = Icons.Outlined.Download,
                        enabled = true,
                        onClick = {
                            onRootClick(
                                HomeRoot(
                                    "Downloads",
                                    "Transfer station",
                                    Icons.Outlined.Download,
                                    RootTarget.TRANSFERS,
                                    null,
                                ),
                            )
                        },
                    )
                }
                item {
                    RootTile(
                        title = "Network",
                        subtitle = "SMB · SFTP · FTP · WebDAV",
                        icon = Icons.Outlined.Dns,
                        enabled = true,
                        onClick = {
                            onRootClick(
                                HomeRoot(
                                    "Network",
                                    "SMB · SFTP · FTP · WebDAV",
                                    Icons.Outlined.Dns,
                                    RootTarget.NETWORK,
                                    null,
                                ),
                            )
                        },
                    )
                }
            }

            SectionTitle("Library")
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    RootTile(
                        title = "Media",
                        subtitle = "Images · Videos · Audio",
                        icon = Icons.Outlined.Image,
                        enabled = true,
                        onClick = {
                            onRootClick(
                                HomeRoot(
                                    "Media",
                                    "Images · Videos · Audio",
                                    Icons.Outlined.Image,
                                    RootTarget.MEDIA,
                                    null,
                                ),
                            )
                        },
                    )
                }
                item {
                    RootTile(
                        "Archives",
                        "Open one in the browser",
                        Icons.Outlined.Folder,
                        enabled = false,
                        onClick = {},
                    )
                }
                item {
                    RootTile("Cloud", "M4", Icons.Outlined.Folder, enabled = false, onClick = {})
                }
                item {
                    RootTile("Vault", "M5", Icons.Outlined.Folder, enabled = false, onClick = {})
                }
            }
        }
    }
}

private val HorizontalDividerDefaultsThickness: androidx.compose.ui.unit.Dp = 1.dp

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun RecentRow(entity: RecentFileEntity, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(entity.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Text(
                "${entity.uri} · ${formatSize(entity.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RootTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun RecentFileEntity.toNode(): FsNode =
    FsNode(
        id = uri,
        uri = uri,
        name = name,
        providerType = runCatching { FsType.valueOf(fsType) }.getOrDefault(FsType.LOCAL),
        metadata = FsMetadata(sizeBytes = sizeBytes, isDirectory = false),
    )

private fun formatSize(bytes: Long?): String = when {
    bytes == null -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.0f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
}
