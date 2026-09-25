package app.ee.feature.network

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import app.ee.provider.ftpsrv.FtpShareServer
import app.ee.provider.ftpsrv.LanAddress
import app.ee.provider.ftpsrv.QrBitMatrix
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** LAN sharing state for the Connections screen (P1-5). */
data class LanShareState(
    val busy: Boolean = false,
    val active: Boolean = false,
    val url: String? = null,
    val port: Int = 0,
    val qrPixels: IntArray? = null,
    val error: String? = null,
)

class LanShareViewModel(root: File) : ViewModel() {

    private val server = FtpShareServer(root)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(LanShareState())
    val state: StateFlow<LanShareState> = _state

    fun toggle() {
        val s = _state.value
        if (s.busy) return
        if (s.active || server.active) stop() else start()
    }

    private fun start() {
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            runCatching {
                val port = server.start()
                val ip = LanAddress.localIpv4() ?: "127.0.0.1"
                val url = server.shareUrl(ip, port)
                val pixels = QrBitMatrix.encode(url)
                _state.value = LanShareState(
                    active = true,
                    url = url,
                    port = port,
                    qrPixels = pixels,
                )
            }.onFailure { e ->
                _state.update {
                    it.copy(busy = false, error = e.message ?: "تعذر بدء خادم FTP")
                }
            }
        }
    }

    private fun stop() {
        _state.update { it.copy(busy = true) }
        scope.launch {
            server.stop()
            _state.value = LanShareState()
        }
    }

    override fun onCleared() {
        scope.cancel()
        runCatching { server.stop() }
        super.onCleared()
    }
}

/** Card with start/stop + share URL + QR code (scan → open the server). */
@Composable
fun SharingCard(
    state: LanShareState,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("مشاركة عبر الشبكة المحلية", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "خادم FTP مدمج بملفات هذا الجهاز",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onClick = onToggle, enabled = !state.busy) {
                    Text(if (state.active) "إيقاف" else "بدء")
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "جارٍ التجهيز…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val url = state.url
            if (url != null) {
                Spacer(Modifier.height(12.dp))
                Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Spacer(Modifier.height(12.dp))
                state.qrPixels?.let { pixels ->
                    val bitmap = remember(pixels) {
                        Bitmap.createBitmap(
                            pixels,
                            QrBitMatrix.DEFAULT_SIZE,
                            QrBitMatrix.DEFAULT_SIZE,
                            Bitmap.Config.ARGB_8888,
                        )
                    }
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "QR — FTP",
                        modifier = Modifier.size(180.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "امسح الرمز لفتح الخادم (المستخدم: ${FtpShareServer.DEFAULT_USER} / ${FtpShareServer.DEFAULT_PASS} — المنفذ ${state.port})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
