package app.ee.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App lock gate (M4 — P0-2): shown instead of all content while the app is
 * locked. PIN pad (4–8 digits) + optional biometric/device-credential button
 * (the actual BiometricPrompt is launched by the activity, which holds the
 * prompt context).
 */
@Composable
fun LockScreen(
    pinSet: Boolean,
    biometricAvailable: Boolean,
    onBiometric: () -> Unit,
    onVerifyPin: (String) -> Boolean,
    onSetPin: (String) -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var setDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("App locked", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))

        if (biometricAvailable) {
            Button(onClick = onBiometric) {
                Icon(
                    Icons.Filled.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.size(8.dp))
                Text("Fingerprint / device credential")
            }
            Spacer(Modifier.height(24.dp))
        }

        if (pinSet) {
            // PIN dots
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(8) { i ->
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(
                                if (i < pin.length) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                CircleShape,
                            ),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            val errorText = error
            if (errorText != null) {
                Text(
                    errorText,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
            }

            // keypad
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "clear", "0", "back")
            keys.chunked(3).forEach { rowKeys ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    rowKeys.forEach { k ->
                        when (k) {
                            "clear" -> KeyButton(text = "C") { pin = ""; error = null }
                            "back" -> KeyButton(text = "⌫") {
                                pin = pin.dropLast(1)
                                error = null
                            }
                            else -> KeyButton(text = k) {
                                if (pin.length < 8) {
                                    pin += k
                                    error = null
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (!onVerifyPin(pin)) {
                        error = "Incorrect PIN"
                        pin = ""
                    }
                    // success: the parent recomposes away from this screen
                },
                enabled = pin.length >= 4,
            ) { Text("Unlock") }
        } else {
            Text(
                "No PIN set",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = { setDialog = true }) { Text("Set a PIN") }
        }
    }

    if (setDialog) {
        SetPinDialog(
            onDismiss = { setDialog = false },
            onConfirm = { newPin ->
                onSetPin(newPin)
                setDialog = false
            },
        )
    }
}

@Composable
private fun KeyButton(text: String, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(64.dp),
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Two-step PIN creation (enter + confirm, 4–8 digits). */
@Composable
fun SetPinDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.isNotEmpty() && first != second
    val valid = first.length in 4..8 && second == first

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set PIN") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = first,
                    onValueChange = { first = it.filter(Char::isDigit).take(8) },
                    label = { Text("New PIN (4–8 digits)") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = second,
                    onValueChange = { second = it.filter(Char::isDigit).take(8) },
                    label = { Text("Confirm PIN") },
                    singleLine = true,
                )
                if (mismatch) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "PINs do not match",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = valid,
                onClick = { onConfirm(first) },
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
