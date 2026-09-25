package app.ee.feature.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.db.ConnectionDao
import app.ee.core.db.ConnectionEntity
import app.ee.core.model.FsType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Connection manager (docs/02-specification.md P1-3…P1-6 UI, §3.1 "Network"):
 * saved profiles for SMB / SFTP / FTP / WebDAV / HTTP, add + delete, and
 * "open" pushes the browser at `ee://<type>/<connId>/`.
 *
 * The screen is data-in/callbacks-out: the app module owns persistence
 * (Room profile row + Keystore secret via core-security).
 */
data class ProfileForm(
    val name: String,
    val type: FsType,
    val host: String,
    val port: Int,
    val path: String,
    val username: String,
    val password: String,
)

data class ConnectionUi(
    val id: Long,
    val name: String,
    val type: FsType,
    val host: String,
    val port: Int,
    val path: String,
)

class ConnectionsViewModel(
    dao: ConnectionDao,
    private val onSave: suspend (ProfileForm) -> Unit,
    private val onDelete: (Long) -> Unit,
) : ViewModel() {

    val connections: StateFlow<List<ConnectionUi>> = dao.observeAll()
        .map { list -> list.map { it.toUi() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    fun save(form: ProfileForm) {
        viewModelScope.launch {
            try {
                onSave(form)
                _error.value = null
            } catch (e: Exception) {
                _error.value = e.message ?: "Save failed"
            }
        }
    }

    fun delete(id: Long) {
        onDelete(id)
    }

    private fun ConnectionEntity.toUi(): ConnectionUi =
        ConnectionUi(
            id = id,
            name = name,
            type = runCatching { FsType.valueOf(fsType) }.getOrDefault(FsType.SMB),
            host = host,
            port = port,
            path = path,
        )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(
    onBack: () -> Unit,
    onOpen: (FsType, Long) -> Unit,
    dao: ConnectionDao,
    onSave: suspend (ProfileForm) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val vm: ConnectionsViewModel = viewModel(
        factory = viewModelFactory {
            initializer { ConnectionsViewModel(dao, onSave, onDelete) }
        },
    )
    val connections by vm.connections.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ConnectionUi?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addDialog = true }) {
                Icon(Icons.Outlined.Add, contentDescription = "Add connection")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            error?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            Box(Modifier.fillMaxSize()) {
                if (connections.isEmpty()) {
                    Column(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            Icons.Outlined.Dns,
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                        )
                        Text(
                            "No saved connections",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Add SMB, SFTP, FTP, WebDAV or HTTP",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(connections) { conn ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(conn.name, style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "${conn.type.name} · ${conn.host}:${conn.port}${if (conn.path.isEmpty()) " " else " $conn.path"}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                    TextButton(onClick = { onOpen(conn.type, conn.id) }) {
                                        Text("Open")
                                    }
                                    IconButton(onClick = { deleting = conn }) {
                                        Icon(Icons.Outlined.Close, contentDescription = "Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (addDialog) {
        ProfileDialog(
            onDismiss = { addDialog = false },
            onConfirm = { form ->
                addDialog = false
                vm.save(form)
            },
        )
    }

    deleting?.let { conn ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete connection?") },
            text = {
                Text(
                    "\"${conn.name}\" will be removed. Files on the server are not " +
                        "touched; the stored password is erased from the Keystore.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(conn.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private val TYPE_OPTIONS = listOf(FsType.SMB, FsType.SFTP, FsType.FTP, FsType.WEBDAV, FsType.HTTP)

private fun defaultPort(type: FsType): Int = when (type) {
    FsType.SMB -> 445
    FsType.SFTP -> 22
    FsType.FTP -> 21
    FsType.WEBDAV -> 80
    else -> 80
}

@Composable
private fun ProfileDialog(onDismiss: () -> Unit, onConfirm: (ProfileForm) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(FsType.SMB) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("445") }
    var path by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var typeMenu by remember { mutableStateOf(false) }

    val valid = name.isNotBlank() && host.isNotBlank() &&
        (type == FsType.HTTP || username.isNotBlank()) &&
        port.toIntOrNull() != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = type.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Type") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { typeMenu = true }) { Text("Change") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text("Host") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter(Char::isDigit).take(5) },
                        label = { Text("Port") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = {
                        Text(
                            when (type) {
                                FsType.SMB -> "Share (e.g. public)"
                                FsType.SFTP -> "Base dir (e.g. home)"
                                FsType.FTP -> "Base dir (e.g. pub)"
                                FsType.WEBDAV -> "Base path (e.g. dav)"
                                else -> "Base URL (e.g. http://host/files/)"
                            },
                        )
                    },
                    singleLine = true,
                )
                if (type != FsType.HTTP) {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (kept in Android Keystore)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onConfirm(
                        ProfileForm(
                            name = name.trim(),
                            type = type,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: defaultPort(type),
                            path = path.trim().trimStart('/'),
                            username = username.trim(),
                            password = password,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (typeMenu) {
        AlertDialog(
            onDismissRequest = { typeMenu = false },
            title = { Text("Protocol") },
            text = {
                Column {
                    TYPE_OPTIONS.forEach { t ->
                        TextButton(onClick = {
                            type = t
                            port = defaultPort(t).toString()
                            typeMenu = false
                        }) { Text(t.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { typeMenu = false }) { Text("Cancel") } },
        )
    }
}
