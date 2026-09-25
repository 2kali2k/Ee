package app.ee.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.ee.core.fs.FsRegistry
import app.ee.core.fs.FsUri
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

private const val CATEGORY_IMAGES = "ee://media/images"
private const val CATEGORY_VIDEOS = "ee://media/videos"
private const val CATEGORY_AUDIO = "ee://media/audio"

private data class MediaItemUi(
    val name: String,
    val contentUri: String,
    val sizeBytes: Long?,
)

/**
 * Media library (M2 — P1-10): Images / Videos / Audio tabs over the
 * provider-media roots. Thumbnails via Coil (content URIs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaLibraryScreen(
    onBack: () -> Unit,
    vfs: FsRegistry,
    onOpenImage: (String, String) -> Unit,
    onOpenVideo: (String, String) -> Unit,
    onOpenAudio: (String, String) -> Unit,
) {
    val vm: MediaLibraryViewModel = viewModel(
        factory = viewModelFactory {
            initializer { MediaLibraryViewModel(vfs) }
        },
    )
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(0) }

    val onTabChanged: (Int) -> Unit = {
        tab = it
        vm.selectCategory(
            when (it) {
                0 -> CATEGORY_IMAGES
                1 -> CATEGORY_VIDEOS
                else -> CATEGORY_AUDIO
            },
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Media") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                )
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { onTabChanged(0) }) { Text("Images") }
                    Tab(selected = tab == 1, onClick = { onTabChanged(1) }) { Text("Videos") }
                    Tab(selected = tab == 2, onClick = { onTabChanged(2) }) { Text("Audio") }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator()
                }

                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                items.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No media found",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items) { item ->
                        MediaTile(
                            item = item,
                            isAudio = tab == 2,
                            onClick = {
                                when (tab) {
                                    0 -> onOpenImage(item.contentUri, item.name)
                                    1 -> onOpenVideo(item.contentUri, item.name)
                                    else -> onOpenAudio(item.contentUri, item.name)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaTile(item: MediaItemUi, isAudio: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (isAudio) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            ) {
                Text(
                    item.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                )
            }
        } else {
            AsyncImage(
                model = item.contentUri,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp)),
            )
        }
    }
}

/** Loads one media category at a time through the VFS (provider-media). */
class MediaLibraryViewModel(private val vfs: FsRegistry) : ViewModel() {
    private val _category = MutableStateFlow(CATEGORY_IMAGES)
    private val _items = MutableStateFlow<List<MediaItemUi>>(emptyList())
    private val _loading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)

    val items: StateFlow<List<MediaItemUi>> = _items
    val loading: StateFlow<Boolean> = _loading
    val error: StateFlow<String?> = _error

    init {
        refresh()
    }

    fun selectCategory(uri: String) {
        if (_category.value == uri) return
        _category.value = uri
        refresh()
    }

    private fun refresh() {
        _loading.value = true
        _error.value = null
        _items.value = emptyList()
        viewModelScope.launch {
            try {
                val type = FsUri.parse(_category.value).type
                val provider = vfs.provider(type)
                val root = provider.root(_category.value)
                val nodes = provider.list(root).toList()
                _items.value = nodes.map { n ->
                    MediaItemUi(
                        name = n.name,
                        contentUri = n.metadata?.extra?.get("contentUri") ?: n.uri,
                        sizeBytes = n.metadata?.sizeBytes,
                    )
                }
                _loading.value = false
            } catch (e: Exception) {
                _error.value = e.message ?: "Cannot read media"
                _loading.value = false
            }
        }
    }
}
