package app.ee.feature.imageviewer

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Image viewer (M1 P0-7, delivered with M2): full-screen image on a black
 * background. Pinch zoom + crop land in M3 (pinch: a zoomable wrapper;
 * crop: UCrop-style custom implementation).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    contentUri: String,
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uri = remember(contentUri) { Uri.parse(contentUri) }

    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Black,
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
                ImageView(ctx).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageBitmap(loadBitmap(context, uri))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

private fun loadBitmap(context: android.content.Context, uri: Uri): android.graphics.Bitmap? =
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        }
    }.getOrNull()
