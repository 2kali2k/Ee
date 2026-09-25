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
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A home root: a shortcut into a virtual-filesystem area (or a placeholder
 * for a milestone that has not landed yet).
 */
data class HomeRoot(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val enabled: Boolean,
)

/**
 * M0 home: the static root grid from docs/02-specification.md §7.
 * Wired to real providers in M1/M3; disabled rows show the roadmap state.
 */
val m0Roots: List<HomeRoot> = listOf(
    HomeRoot("Internal storage", "Browse device files", Icons.Outlined.SdStorage, enabled = true),
    HomeRoot("Media", "Images · videos · music", Icons.Outlined.Image, enabled = false),
    HomeRoot("Recent", "Recently used files", Icons.Outlined.History, enabled = false),
    HomeRoot("Favorites", "Pinned files & folders", Icons.Outlined.FavoriteBorder, enabled = false),
    HomeRoot("Archives", "Zip · 7z · tar", Icons.Outlined.Archive, enabled = false),
    HomeRoot("Vault", "Encrypted folder", Icons.Outlined.Lock, enabled = false),
    HomeRoot("Network", "SMB · SFTP · FTP · WebDAV", Icons.Outlined.Lan, enabled = false),
    HomeRoot("Cloud", "S3 · Dropbox · Mega (P2)", Icons.Outlined.Cloud, enabled = false),
)

@Composable
fun HomeScreen(
    roots: List<HomeRoot> = m0Roots,
    onRootClick: (HomeRoot) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = "Ee",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "File manager · M0 scaffold",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
        }
        items(roots.size) { index ->
            val root = roots[index]
            RootCard(root = root, onClick = { onRootClick(root) })
        }
    }
}

@Composable
private fun RootCard(root: HomeRoot, onClick: () -> Unit) {
    Card(
        onClick = if (root.enabled) onClick else {},
        enabled = root.enabled,
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
                tint = if (root.enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = root.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (root.enabled) MaterialTheme.colorScheme.onSurface
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
