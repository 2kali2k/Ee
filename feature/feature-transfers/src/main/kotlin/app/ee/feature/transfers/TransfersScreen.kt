package app.ee.feature.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.db.EeDatabase

/**
 * Transfer station (M2 — P1-9): the download queue with add / pause /
 * resume / cancel. In M2 only DOWNLOAD kind exists; uploads and copies join
 * the same list with M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    database: EeDatabase,
) {
    val vm: TransfersViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                TransfersViewModel(
                    androidx.compose.ui.platform.LocalContext.current.applicationContext,
                    database.transfers(),
                )
            }
        },
    )
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { addDialog = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "Add download")
                    }
                },
            )
        },
    ) { padding ->
        val list = jobs.values.sortedByDescending { it.bytesDone + it.bytesTotal }
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No downloads yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(list.size) { index ->
                    val job = list[index]
                    JobCard(
                        job = job,
                        onPause = { vm.pause(job.id) },
                        onResume = { vm.resume(job.id) },
                        onCancel = { vm.cancel(job.id) },
                        onRemove = { vm.removeFinished(job.id) },
                    )
                }
            }
        }
    }

    if (addDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add download") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/file.zip") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = url.startsWith("http"),
                    onClick = {
                        addDialog = false
                        vm.add(url.trim())
                    },
                ) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { addDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun JobCard(
    job: DownloadJob,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(job.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        text = when (job.state) {
                            DownloadJob.State.RUNNING ->
                                "${formatKb(job.bytesDone)} / ${formatKb(job.bytesTotal)}"
                            DownloadJob.State.PAUSED ->
                                "Paused · ${formatKb(job.bytesDone)}"
                            DownloadJob.State.DONE -> "Done"
                            DownloadJob.State.FAILED -> job.error ?: "Failed"
                            DownloadJob.State.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (job.state) {
                    DownloadJob.State.RUNNING -> IconButton(onClick = onPause) {
                        Icon(Icons.Outlined.Pause, contentDescription = "Pause")
                    }

                    DownloadJob.State.PAUSED, DownloadJob.State.FAILED -> IconButton(onClick = onResume) {
                        Icon(Icons.Outlined.PlayArrow, contentDescription = "Resume")
                    }

                    DownloadJob.State.DONE -> IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove")
                    }

                    DownloadJob.State.CANCELLED -> IconButton(onClick = onRemove) {
                        Icon(Icons.Outlined.Close, contentDescription = "Remove")
                    }
                }
            }
            when (job.state) {
                DownloadJob.State.RUNNING ->
                    LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())

                DownloadJob.State.PAUSED ->
                    LinearProgressIndicator(progress = { job.progress }, Modifier.fillMaxWidth())

                else -> Unit
            }
        }
    }
}

private fun formatKb(bytes: Long): String = when {
    bytes <= 0 -> "0 B"
    bytes < 1024 * 1024 -> "${"%.0f".format(bytes / 1024.0)} KB"
    bytes < 1024L * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}
