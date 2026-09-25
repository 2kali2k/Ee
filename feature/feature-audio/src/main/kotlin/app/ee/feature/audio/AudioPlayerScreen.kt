package app.ee.feature.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Audio playback (M2 — P1-8): ExoPlayer with play/pause + seek.
 * Lock-screen MediaSession and playlists land in M3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    uri: String,
    title: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }

    DisposableEffect(uri) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val p = ExoPlayer.Builder(context).build()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        })
        p.playWhenReady = true
        p.prepare()
        player = p
        onDispose {
            p.release()
            player = null
        }
    }

    LaunchedEffect(player) {
        while (true) {
            val p = player
            if (p != null) {
                position = p.currentPosition
                duration = p.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
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
                    onValueChange = { frac ->
                        val p = player ?: return@Slider
                        scope.launch { p.seekTo((frac * duration).toLong()) }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                )
                Text(formatTime(duration), style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            IconButton(
                onClick = {
                    val p = player ?: return@IconButton
                    scope.launch { if (playing) p.pause() else p.play() }
                },
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    modifier = Modifier.size(72.dp),
                )
            }
        }
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
