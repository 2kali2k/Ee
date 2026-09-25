package app.ee.feature.audio

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Audio playback (M3b — P1-8): ExoPlayer behind a [MediaController] from
 * [EeAudioService], so the system media UI (lock screen, notification
 * shade) can control playback. Play/pause + seek in-app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    uri: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val vm: AudioPlayerViewModel = viewModel(
        factory = viewModelFactory {
            initializer { AudioPlayerViewModel(context.applicationContext) }
        },
    )
    val playing by vm.playing.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    val duration by vm.duration.collectAsStateWithLifecycle()

    LaunchedEffect(uri) {
        vm.load(uri, title)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(32.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(formatTime(position), style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = if (duration > 0) position.toFloat() / duration.toFloat() else 0f,
                    onValueChange = { frac -> vm.seek((frac * duration).toLong()) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(formatTime(duration), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            IconButton(onClick = { vm.toggle() }) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(72.dp),
                )
            }
        }
    }
}

/** Owns the [MediaController] bound to [EeAudioService]. */
class AudioPlayerViewModel(context: Context) : ViewModel() {

    private val appContext: Context = context.applicationContext
    @Volatile
    private var controller: MediaController? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration

    /** Outlives viewModelScope so onCleared can still release off-main. */
    private val housekeeping = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.Job(),
    )

    init {
        viewModelScope.launch {
            appContext.startService(Intent(appContext, EeAudioService::class.java))
            val token = withContext(Dispatchers.IO) { waitForToken() }
                ?: return@launch
            val c = withContext(Dispatchers.IO) {
                MediaController.Builder(appContext, token).build()
            }
            c.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playing.value = isPlaying
                }
            })
            controller = c
            // surface whatever is already loaded (the service can outlive the screen)
            _position.value = c.currentPosition
            _duration.value = c.duration.coerceAtLeast(0L)
            _playing.value = c.isPlaying
        }
    }

    fun load(uri: String, title: String) {
        viewModelScope.launch {
            val c = withContext(Dispatchers.IO) { waitForController() } ?: return@launch
            withContext(Dispatchers.IO) {
                c.setMediaItem(MediaItem.fromUri(uri))
                c.setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                c.prepare()
                c.play()
            }
        }
    }

    fun toggle() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                controller?.let { if (it.isPlaying) it.pause() else it.play() }
            }
        }
    }

    fun seek(ms: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { controller?.seekTo(ms) }
        }
    }

    private suspend fun waitForToken(): SessionToken? {
        var token: SessionToken? = null
        repeat(50) {
            token = EeAudioService.TOKEN
            if (token == null) delay(100)
        }
        return token
    }

    private suspend fun waitForController(): MediaController? {
        var c: MediaController? = null
        repeat(50) {
            c = controller
            if (c == null) delay(100)
        }
        return c
    }

    override fun onCleared() {
        housekeeping.launch {
            runCatching { controller?.release() }
        }
        controller = null
        super.onCleared()
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%02d:%02d".format(m, s)
    }
}
