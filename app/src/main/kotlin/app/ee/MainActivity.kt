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

        setContent {
            val themeMode by app.themeMode.collectAsStateWithLifecycle()
            EeTheme(
                darkTheme = when (themeMode) {
                    ThemeMode.DARK -> true
                    ThemeMode.LIGHT -> false
                    ThemeMode.SYSTEM -> isSystemInDarkTheme()
                },
            ) {
                MainContent(app = app, router = router)
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
            onOpenFile = { node -> FileActions.openFile(context, node) },
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

        Screen.Settings -> SettingsScreen(
            onBack = popOrFinish,
            themeMode = app.themeMode.value,
            onThemeModeChange = app::setThemeMode,
            canManageAllFiles = StorageAccess.canManageAllFiles(),
            onOpenStorageSettings = activity::openAppStorageSettings,
            appVersion = "0.3.0-m2",
        )
    }
}
