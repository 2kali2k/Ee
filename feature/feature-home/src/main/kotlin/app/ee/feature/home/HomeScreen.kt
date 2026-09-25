package app.ee.feature.home

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A home root: a shortcut into a virtual-filesystem area.
 * `uri == null` means "not wired yet" (shown disabled with its milestone).
 */
data class HomeRoot(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val uri: String? = null,
)

@Composable
fun HomeScreen(
    storageRootUri: String?,
    onRootClick: (String?) -> Unit,
    onSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val roots = remember(storageRootUri) {
        listOf(
            HomeRoot("Internal storage", "Browse device files", Icons.Outlined.SdStorage, storageRootUri),
            HomeRoot("Recycle bin", "Deleted items (restorable)", Icons.Outlined.RestoreFromTrash,
                storageRootUri?.let { "$it/.ee_trash" }),
            HomeRoot("Media", "Images · videos · music — M2", Icons.Outlined.Image, null),
            HomeRoot("Recent", "Recently used files — M2", Icons.Outlined.History, null),
            HomeRoot("Favorites", "Pinned files & folders — M2", Icons.Outlined.FavoriteBorder, null),
            HomeRoot("Archives", "Zip · 7z · tar — M2", Icons.Outlined.Archive, null),
            HomeRoot("Vault", "Encrypted folder — M4", Icons.Outlined.Lock, null),
            HomeRoot("Network", "SMB · SFTP · FTP · WebDAV — M3", Icons.Outlined.Lan, null),
            HomeRoot("Cloud", "S3 · Dropbox · Mega — P2", Icons.Outlined.Cloud, null),
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Ee",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "Privacy-first file manager · M1",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        leadingIcon = { Icon(Icons.Outlined.MoreVert, null) },
                        onClick = { menuOpen = false; onSettings() },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        items(roots.size) { index ->
            val root = roots[index]
            RootCard(root = root, onClick = { onRootClick(root.uri) })
        }
    }
}

@Composable
private fun RootCard(root: HomeRoot, onClick: () -> Unit) {
    val enabled = root.uri != null
    Card(
        onClick = if (enabled) onClick else {},
        enabled = enabled,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = root.icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = root.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = root.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}
