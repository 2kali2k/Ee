package app.ee

import android.app.Application
import android.os.Environment
import app.ee.core.db.EeDatabase
import app.ee.core.fs.FsRegistry
import app.ee.feature.settings.ThemeMode
import app.ee.provider.archive.ArchiveFsProvider
import app.ee.provider.local.LocalFsProvider
import app.ee.provider.media.MediaFsProvider
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Composition root (docs/02-specification.md §4.1): the only place that wires
 * concrete providers into the VFS registry and owns app-scoped singletons.
 *
 * M2: archive browsing (provider-archive) and the MediaStore media roots
 * (provider-media) join the registry.
 */
class EeApp : Application() {

    lateinit var vfs: FsRegistry
        private set
    lateinit var database: EeDatabase
        private set

    val storageRoot: File get() = Environment.getExternalStorageDirectory()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    override fun onCreate() {
        super.onCreate()
        database = EeDatabase.get(this)
        vfs = FsRegistry()
        vfs.register(LocalFsProvider(storageRoot))
        vfs.register(ArchiveFsProvider())
        vfs.register(MediaFsProvider(this))
        // M3: smb/sftp/ftp/webdav providers — M4: vault — P2: cloud
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode.name)
            .apply()
    }

    private fun loadThemeMode(): ThemeMode = runCatching {
        val stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_THEME, null)
        if (stored != null) ThemeMode.valueOf(stored) else ThemeMode.SYSTEM
    }.getOrDefault(ThemeMode.SYSTEM)

    companion object {
        const val PREFS = "ee_prefs"
        const val KEY_THEME = "theme"
    }
}
