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
import androidx.compose.runtime.getValue
import app.ee.designsystem.EeTheme
import app.ee.feature.filemanager.BrowserScreen
import app.ee.feature.filemanager.FileActions
import app.ee.feature.home.HomeScreen
import app.ee.feature.settings.SettingsScreen
import app.ee.feature.settings.ThemeMode
import app.ee.provider.local.LocalPaths
import app.ee.provider.local.StorageAccess
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val router = Router()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!router.back()) isEnabled = false
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
                when (val screen = router.current()) {
                    Screen.Home -> HomeScreen(
                        storageRootUri = LocalPaths.uriFor(app.storageRoot.absolutePath),
                        onRootClick = { uri -> uri?.let { router.navigate(Screen.Browser(it)) } },
                        onSettings = { router.navigate(Screen.Settings) },
                    )

                    is Screen.Browser -> BrowserScreen(
                        startUri = screen.startUri,
                        vfs = app.vfs,
                        onBack = {
                            if (!router.back()) finish()
                        },
                        onOpenFile = { FileActions.openFile(this, it) },
                        onShare = { FileActions.shareFiles(this, it) },
                        onSettings = { router.navigate(Screen.Settings) },
                    )

                    Screen.Settings -> SettingsScreen(
                        onBack = {
                            if (!router.back()) finish()
                        },
                        themeMode = themeMode,
                        onThemeModeChange = app::setThemeMode,
                        canManageAllFiles = StorageAccess.canManageAllFiles(),
                        onOpenStorageSettings = { openAppStorageSettings() },
                        appVersion = "0.2.0-m1",
                    )
                }
            }
        }
    }

    private fun openAppStorageSettings() {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        }
        startActivity(intent)
    }
}
