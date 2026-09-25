package app.ee.feature.filemanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.fs.FsException
import app.ee.core.fs.FsProvider
import app.ee.core.fs.FsRegistry
import app.ee.core.fs.FsUri
import app.ee.core.model.FsNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer

/**
 * Text & Markdown editor (M5 — P1-11): read via the VFS provider, edit,
 * write back through the same provider (works on local, vault, and network
 * roots alike when the provider is writable).
 *
 * `.md` files get a preview tab rendered by [renderMarkdown].
 */
class EditorViewModel(
    private val vfs: FsRegistry,
    private val uri: String,
    private val isMarkdown: Boolean,
) : ViewModel() {

    sealed interface Phase {
        data object Loading : Phase
        data class Error(val message: String) : Phase
        data object Ready : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Loading)
    val phase: StateFlow<Phase> = _phase

    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving

    private var node: app.ee.core.model.FsNode? = null

    val canPreview: Boolean get() = isMarkdown

    init {
        viewModelScope.launch {
            try {
                val parsed = FsUri.parse(uri)
                val provider = vfs.get(parsed.type)
                // network roots carry a connection id in the first segment
                val typePart = parsed.type.name.lowercase()
                val firstSegment = parsed.path.substringBefore('/').ifEmpty { "" }
                val rootUri = if (firstSegment.isEmpty()) "ee://$typePart/"
                else "ee://$typePart/$firstSegment"
                val root = provider.root(rootUri)
                val node = walkTo(provider, root, parsed.path)
                    ?: throw FsException.NotFound(uri)
                this.node = node
                val source = provider.open(node)
                val data = withContext(Dispatchers.IO) {
                    Buffer().use { buf ->
                        buf.writeAll(source)
                        buf.readByteArray()
                    }
                }
                _text.value = String(data, Charsets.UTF_8)
                _phase.value = Phase.Ready
            } catch (e: Exception) {
                _phase.value = Phase.Error(e.message ?: "Cannot open file")
            }
        }
    }

    fun setText(value: String) {
        _text.value = value
        _dirty.value = true
    }

    fun save() {
        if (_saving.value) return
        viewModelScope.launch {
            val n = node ?: return@launch
            _saving.value = true
            try {
                val parsed = FsUri.parse(n.uri)
                vfs.get(parsed.type).write(n, Buffer().write(_text.value.encodeToByteArray()))
                _dirty.value = false
            } catch (e: Exception) {
                _phase.value = Phase.Error(e.message ?: "Save failed")
            } finally {
                _saving.value = false
            }
        }
    }

    /** Descend from [root] to the node at [path] ("" → root itself). */
    private suspend fun walkTo(provider: FsProvider, root: FsNode, path: String): FsNode? {
        if (path.isEmpty()) return root
        var current = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            val children = provider.list(current).toList()
            current = children.firstOrNull { it.name.equals(segment, ignoreCase = true) }
                ?: return null
        }
        return current
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    vfs: FsRegistry,
    uri: String,
    title: String,
    onBack: () -> Unit,
) {
    val isMarkdown = title.endsWith(".md", ignoreCase = true)
    val vm: EditorViewModel = viewModel(
        factory = viewModelFactory {
            initializer { EditorViewModel(vfs, uri, isMarkdown) }
        },
    )
    val phase by vm.phase.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "${title}${if (dirty) " •" else ""}",
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (vm.canPreview) {
                        TextButton(onClick = { preview = !preview }) {
                            Text(if (preview) "Edit" else "Preview")
                        }
                    }
                    TextButton(
                        onClick = { vm.save() },
                        enabled = !saving,
                    ) { Text("Save") }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val p = phase) {
                is EditorViewModel.Phase.Loading -> {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is EditorViewModel.Phase.Error -> {
                    Text(
                        p.message,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                EditorViewModel.Phase.Ready -> {
                    if (preview && vm.canPreview) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp),
                        ) {
                            Text(renderMarkdown(text), fontSize = 15.sp)
                        }
                    } else {
                        androidx.compose.material3.OutlinedTextField(
                            value = text,
                            onValueChange = vm::setText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            singleLine = false,
                            minLines = 1,
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                keyboardType = KeyboardType.Text,
                            ),
                        )
                    }
                }
            }
        }
    }
}
