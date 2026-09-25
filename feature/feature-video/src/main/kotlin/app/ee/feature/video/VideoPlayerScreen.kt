package app.ee.feature.video

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * Video playback (M2 — P1-7): Media3 ExoPlayer + the platform PlayerView
 * (standard controller UI). Gesture controls, subtitles and cast land in M3
 * (PlayerView already exposes them once wired; M2 keeps the surface small).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    uri: String,
    title: String,
    onBack: () -> Unit,
) {
    var player by remember { mutableStateOf<ExoPlayer?>(null) }

    DisposableEffect(uri) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val p = ExoPlayer.Builder(context).build()
        p.setMediaItem(MediaItem.fromUri(uri))
        p.playWhenReady = true
        p.prepare()
        player = p
        onDispose {
            p.release()
            player = null
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
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            // the ExoPlayer is created in the DisposableEffect below — wire it
            // here on first recomposition
            update = { view ->
                if (view.player !== player) view.player = player
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}
