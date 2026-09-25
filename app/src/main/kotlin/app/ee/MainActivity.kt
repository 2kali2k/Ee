package app.ee

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.ee.designsystem.EeTheme
import app.ee.feature.audio.AudioPlayerScreen
import app.ee.feature.filemanager.BrowserScreen
import app.ee.feature.filemanager.FileActions
import app.ee.feature.home.HomeScreen
import app.ee.feature.home.MediaLibraryScreen
import app.ee.feature.home.RootTarget
import app.ee.feature.imageviewer.ImageViewerScreen
import app.ee.feature.settings.SettingsScreen
import app.ee.feature.settings.ThemeMode
import app.ee.feature.transfers.TransfersScreen
import app.ee.feature.video.VideoPlayerScreen
import app.ee.provider.local.StorageAccess
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Single-activity host (docs/02-specification.md §4.6). The in-app Router
 * owns the backstack; screen content is dispatched in [MainContent].
 */
class MainActivity : ComponentActivity() {

    private val router = Router()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // at the root, hand back to the default behaviour (finish)
                if (!router.pop()) isEnabled = false
            }
        })

        val app = application as EeApp

        // P0-2: lock the app whenever the whole process goes to background
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    app.lock()
                }
            },
        )

        // M5 — P1-12: share-receive (SEND/SEND_MULTIPLE) + file-chooser target
        handleLaunch(intent)

        setContent {
            val themeMode by app.themeMode.collectAsStateWithLifecycle()
            val lockEnabled by app.lockEnabled.collectAsStateWithLifecycle()
            val locked by app.locked.collectAsStateWithLifecycle()
            EeTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
            ) {
                if (lockEnabled && locked) {
                    LockGate(app = app, activity = this)
                } else {
                    MainContent(app = app, router = router)
                    // P1-12: "save here" dialog for incoming shares
                    val incoming by this@MainActivity.incomingFiles.collectAsStateWithLifecycle()
                    if (incoming.isNotEmpty()) {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { this@MainActivity.incomingFiles.value = emptyList() },
                            title = {
                                androidx.compose.material3.Text(
                                    "Save ${if (incoming.size == 1) incoming[0].name else "${incoming.size} files"}?",
                                )
                            },
                            text = {
                                androidx.compose.material3.Text(
                                    "Destination: " + this@MainActivity.downloadsDir().absolutePath,
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { this@MainActivity.saveIncoming() },
                                ) { androidx.compose.material3.Text("Save here") }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                    onClick = { this@MainActivity.incomingFiles.value = emptyList() },
                                ) { androidx.compose.material3.Text("Discard") }
                            },
                        )
                    }
                }
            }
        }
    }

    /** P0-2: fingerprint / device-credential unlock (API 28+; PIN-only below). */
    private fun isBiometricAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return androidx.biometric.BiometricManager.from(this)
            .canAuthenticate(
                androidx.biometric.BiometricManager.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.DEVICE_CREDENTIAL,
            ) == androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun launchBiometricPrompt() {
        val app = application as EeApp
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        val manager = androidx.biometric.BiometricManager.from(this)
        if (
            manager.canAuthenticate(
                androidx.biometric.BiometricManager.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.DEVICE_CREDENTIAL,
            ) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS
        ) {
            return
        }
        val prompt = androidx.biometric.BiometricPrompt(
            this,
            androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: androidx.biometric.BiometricPrompt.AuthenticationResult,
                ) {
                    app.unlock()
                }
                // failure keeps the app locked; PIN fallback remains
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Ee")
                .setAllowedAuthenticators(
                    androidx.biometric.BiometricManager.BIOMETRIC_STRONG or
                        androidx.biometric.BiometricManager.DEVICE_CREDENTIAL,
                )
                .setConfirmationRequired(false)
                .build(),
        )
    }

    // ------------------------------------------------------------------
    // M5 — P1-12: share-receive ("Save here") + GET_CONTENT chooser target
    // ------------------------------------------------------------------

    /** One incoming shared item: a content [uri] or shared plain [text]. */
    data class IncomingFile(val name: String, val uri: Uri?, val text: String? = null)

    private val incomingFiles =
        kotlinx.coroutines.flow.MutableStateFlow<List<IncomingFile>>(emptyList())

    private var chooserMode = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT -> {
                chooserMode = true
                router.push(Screen.Browser("ee://local/"))
            }

            Intent.ACTION_SEND -> {
                val stream = runCatching {
                    intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                }.getOrNull()
                val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val file = when {
                    stream != null -> IncomingFile(displayName(stream), stream)
                    text != null -> IncomingFile(
                        "shared-text-${System.currentTimeMillis() / 1000}.txt",
                        null,
                        text,
                    )
                    else -> null
                }
                if (file != null) incomingFiles.value = listOf(file)
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = runCatching {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                }.getOrNull() ?: return
                if (streams.isEmpty()) return
                incomingFiles.value = streams.map { IncomingFile(displayName(it), it) }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) name = cursor.getString(idx)
            }
        }
        return name ?: "shared-${System.currentTimeMillis() / 1000}"
    }

    /** Download dir with an app-private fallback. */
    private fun downloadsDir(): java.io.File {
        val public = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        if (public.exists() || public.mkdirs()) {
            if (public.canWrite()) return public
        }
        return java.io.File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "")
    }

    private fun saveIncoming() {
        val files = incomingFiles.value
        if (files.isEmpty()) return
        val dir = downloadsDir()
        androidx.lifecycle.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var ok = 0
            files.forEach { item ->
                val file = java.io.File(dir, item.name)
                runCatching {
                    if (item.text != null) {
                        file.writeText(item.text)
                    } else {
                        contentResolver.openInputStream(item.uri ?: return@runCatching)?.use { input ->
                            file.outputStream().use { out -> input.copyTo(out) }
                        }
                    }
                    ok++
                }
            }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                incomingFiles.value = emptyList()
                android.widget.Toast.makeText(
                    this@MainActivity,
                    "Saved $ok of ${files.size} to ${dir.absolutePath}",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    fun openAppStorageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName"),
            )
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }
}

@Composable
private fun MainContent(app: EeApp, router: Router) {
    val context = LocalContext.current
    val activity = context as MainActivity
    val backups by app.backups.collectAsStateWithLifecycle()

    val popOrFinish: () -> Unit = {
        if (!router.pop()) activity.finish()
    }

    when (val screen = router.current) {
        Screen.Home -> HomeScreen(
            database = app.database,
            onRootClick = { root ->
                when (root.target) {
                    RootTarget.BROWSER ->
                        root.uri?.let { router.push(Screen.Browser(it)) }
                    RootTarget.MEDIA -> router.push(Screen.Media)
                    RootTarget.TRANSFERS -> router.push(Screen.Transfers)
                    RootTarget.NETWORK -> router.push(Screen.Network)
                    RootTarget.VAULT -> router.push(Screen.Vault)
                }
            },
            onSettingsClick = { router.push(Screen.Settings) },
            onOpenFile = { node -> FileActions.openFile(context, node) },
        )

        is Screen.Browser -> BrowserScreen(
            startUri = screen.startUri,
            vfs = app.vfs,
            recentsDao = app.database.recentFiles(),
            clipboard = app.clipboard,
            setClipboard = app::setClipboard,
            onBack = popOrFinish,
            onOpenFile = { node ->
                if (activity.chooserMode) {
                    // P1-12: GET_CONTENT target — return the picked file
                    val path = node.metadata?.extra?.get("path")
                    val file = path?.let(::java.io.File)?.takeIf { it.exists() }
                    if (file != null) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            activity, "${activity.packageName}.fileprovider", file,
                        )
                        activity.setResult(
                            android.app.Activity.RESULT_OK,
                            Intent().putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        activity.finish()
                    }
                } else if (FileActions.isTextFile(node.name)) {
                    router.push(Screen.Editor(node.uri, node.name))
                } else {
                    FileActions.openFile(context, node)
                }
            },
            onOpenArchive = { node ->
                node.metadata?.extra?.get("path")?.let { path ->
                    router.push(Screen.Browser("ee://archive/$path"))
                }
            },
            onShare = { nodes -> FileActions.shareFiles(context, nodes) },
            onSettings = { router.push(Screen.Settings) },
        )

        Screen.Media -> MediaLibraryScreen(
            onBack = popOrFinish,
            vfs = app.vfs,
            onOpenImage = { uri, title -> router.push(Screen.Image(uri, title)) },
            onOpenVideo = { uri, title -> router.push(Screen.Video(uri, title)) },
            onOpenAudio = { uri, title -> router.push(Screen.Audio(uri, title)) },
        )

        Screen.Transfers -> TransfersScreen(
            onBack = popOrFinish,
            database = app.database,
        )

        Screen.Vault -> app.ee.feature.vault.VaultScreen(
            vault = app.vault,
            onBack = popOrFinish,
            onEnter = { uri -> router.push(Screen.Browser(uri)) },
        )

        Screen.Network -> app.ee.feature.network.ConnectionsScreen(
            onBack = popOrFinish,
            onOpen = { type, id ->
                router.push(Screen.Browser("ee://${type.name.lowercase()}/$id"))
            },
            dao = app.database.connections(),
            onSave = { form -> app.saveProfile(form) },
            onDelete = { id -> app.deleteProfile(id) },
            shareRoot = app.storageRoot,
        )

        is Screen.Image -> ImageViewerScreen(
            contentUri = screen.uri,
            title = screen.title,
            onBack = popOrFinish,
        )

        is Screen.Video -> VideoPlayerScreen(
            uri = screen.uri,
            title = screen.title,
            onBack = popOrFinish,
        )

        is Screen.Audio -> AudioPlayerScreen(
            uri = screen.uri,
            title = screen.title,
            onBack = popOrFinish,
        )

        is Screen.Editor -> app.ee.feature.filemanager.EditorScreen(
            vfs = app.vfs,
            uri = screen.uri,
            title = screen.title,
            onBack = popOrFinish,
        )

        Screen.Settings -> SettingsScreen(
            onBack = popOrFinish,
            themeMode = app.themeMode.value,
            onThemeModeChange = app::setThemeMode,
            canManageAllFiles = StorageAccess.canManageAllFiles(),
            onOpenStorageSettings = activity::openAppStorageSettings,
            appVersion = BuildConfig.VERSION_NAME,
            lockEnabled = app.lockEnabled.value,
            onLockEnabledChange = app::setLockEnabled,
            pinSet = app.pinSet.value,
            onSetPin = app::setPin,
            backups = backups.map {
                app.ee.feature.settings.AutoBackupUi(
                    id = it.id,
                    name = it.name,
                    source = it.sourcePath,
                    dest = it.destPath,
                    daily = it.intervalHours <= 48,
                    enabled = it.enabled,
                    lastStatus = it.lastStatus?.let { s ->
                        val stamp = it.lastRunAt.takeIf { t -> t > 0 }
                            ?.let { t -> java.text.SimpleDateFormat("dd MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) }
                        stamp?.let { "$s · $it" } ?: s
                    },
                )
            },
            onAddBackup = { name, source, dest, hours -> app.addBackup(name, source, dest, hours) },
            onToggleBackup = { id, enabled -> app.toggleBackup(id, enabled) },
            onRemoveBackup = { id -> app.removeBackup(id) },
        )
    }
}

/** Full-screen lock gate (P0-2) — replaces all content until unlocked. */
@Composable
private fun LockGate(app: EeApp, activity: MainActivity) {
    val pinSet by app.pinSet.collectAsStateWithLifecycle()
    val biometricOk = remember { activity.isBiometricAvailable() }
    app.ee.feature.settings.LockScreen(
        pinSet = pinSet,
        biometricAvailable = biometricOk,
        onBiometric = { activity.launchBiometricPrompt() },
        onVerifyPin = { pin ->
            val ok = app.verifyPin(pin)
            if (ok) app.unlock()
            ok
        },
        onSetPin = { pin ->
            app.setPin(pin)
            // a fresh PIN immediately unlocks (it is what was just entered)
            app.unlock()
        },
    )
}
