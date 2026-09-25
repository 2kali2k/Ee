package app.ee.feature.settings

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, LIGHT, DARK }

fun formatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024 -> "$bytes B"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

/**
 * Settings (P0-8, M1): appearance, storage stats (P0-10), privacy
 * (full-disk opt-in, spec §6), about.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    canManageAllFiles: Boolean,
    onOpenStorageSettings: () -> Unit,
    appVersion: String,
    // M4 — P0-2 app lock
    lockEnabled: Boolean,
    onLockEnabledChange: (Boolean) -> Unit,
    pinSet: Boolean,
    onSetPin: (String) -> Unit,
    // M5 — P1-13 auto-backup
    backups: List<AutoBackupUi>,
    onAddBackup: (name: String, source: String, dest: String, intervalHours: Int) -> Unit,
    onToggleBackup: (id: String, enabled: Boolean) -> Unit,
    onRemoveBackup: (id: String) -> Unit,
) {
    var pinDialog by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf(false) }
    val storage = remember {
        runCatching {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            total to free
        }.getOrNull()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Theme",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                label = {
                                    Text(
                                        when (mode) {
                                            ThemeMode.SYSTEM -> "System"
                                            ThemeMode.LIGHT -> "Light"
                                            ThemeMode.DARK -> "Dark"
                                        }
                                    )
                                },
                            )
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Storage", style = MaterialTheme.typography.titleMedium)
                    if (storage != null) {
                        val (total, free) = storage
                        val used = (total - free).coerceAtLeast(0)
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                formatBytes(used),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "of ${formatBytes(total)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { if (total > 0) used.toFloat() / total.toFloat() else 0f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(
                            "Storage stats unavailable",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "All-files access",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (canManageAllFiles) "Granted — the app can browse the whole device storage."
                        else "Not granted. Without it, browsing is limited to what Android shares. " +
                            "Nothing is collected or transmitted; this only controls local file access.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!canManageAllFiles) {
                        Button(onClick = onOpenStorageSettings) { Text("Grant access") }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Security", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("App lock", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Locks the app in the background; unlock with your " +
                                    "PIN or fingerprint.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        androidx.compose.material3.Switch(
                            checked = lockEnabled,
                            onCheckedChange = onLockEnabledChange,
                        )
                    }
                    if (lockEnabled) {
                        Button(onClick = { pinDialog = true }) {
                            Text(if (pinSet) "Change PIN" else "Set PIN")
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Auto-backup",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { backupDialog = true }) { Text("Add") }
                    }
                    if (backups.isEmpty()) {
                        Text(
                            "Mirror a folder to another location on a schedule (daily/weekly).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    backups.forEach { b ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(b.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${b.source} → ${b.dest} · ${if (b.daily) "daily" else "weekly"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                b.lastStatus?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            androidx.compose.material3.Switch(
                                checked = b.enabled,
                                onCheckedChange = { onToggleBackup(b.id, it) },
                            )
                            IconButton(onClick = { onRemoveBackup(b.id) }) {
                                Icon(androidx.compose.material.icons.Icons.Outlined.Close, contentDescription = "Remove")
                            }
                        }
                    }
                }
            }

            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Ee $appVersion\nA privacy-first file manager, rebuilt from scratch. " +
                            "No ads. No trackers. No telemetry.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Start,
                    )
                }
            }
        }
    }

    if (pinDialog) {
        SetPinDialog(
            onDismiss = { pinDialog = false },
            onConfirm = { newPin ->
                onSetPin(newPin)
                pinDialog = false
            },
        )
    }

    if (backupDialog) {
        AddBackupDialog(
            onDismiss = { backupDialog = false },
            onConfirm = { name, source, dest, hours ->
                onAddBackup(name, source, dest, hours)
                backupDialog = false
            },
        )
    }
}

/** One auto-backup job as shown in Settings (M5 — P1-13). */
data class AutoBackupUi(
    val id: String,
    val name: String,
    val source: String,
    val dest: String,
    val daily: Boolean,
    val enabled: Boolean,
    val lastStatus: String?,
)

@Composable
private fun AddBackupDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, source: String, dest: String, intervalHours: Int) -> Unit,
) {
    var name by remember { mutableStateOf("Backup") }
    var source by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("Backups") }
    var daily by remember { mutableStateOf(true) }
    val valid = name.isNotBlank() && source.isNotBlank() && dest.isNotBlank()

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New auto-backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text("Source folder (relative to storage)") },
                    placeholder = { Text("e.g. DCIM/Camera") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = dest,
                    onValueChange = { dest = it },
                    label = { Text("Destination folder") },
                    placeholder = { Text("e.g. Backups") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilterChip(
                        selected = daily,
                        onClick = { daily = true },
                        label = { Text("Daily") },
                    )
                    androidx.compose.material3.FilterChip(
                        selected = !daily,
                        onClick = { daily = false },
                        label = { Text("Weekly") },
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = valid,
                onClick = { onConfirm(name.trim(), source.trim(), dest.trim(), if (daily) 24 else 168) },
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
