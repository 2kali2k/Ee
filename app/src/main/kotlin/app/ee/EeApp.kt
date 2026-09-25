package app.ee

import android.app.Application
import android.os.Environment
import app.ee.core.db.ConnectionDao
import app.ee.core.db.ConnectionEntity
import app.ee.core.db.EeDatabase
import app.ee.core.fs.FsRegistry
import app.ee.core.model.ClipboardEntry
import app.ee.core.model.ConnectionSource
import app.ee.core.model.FsType
import app.ee.core.model.NetConnection
import app.ee.core.security.KeystoreSecretStore
import app.ee.feature.network.ProfileForm
import app.ee.feature.settings.ThemeMode
import app.ee.provider.archive.ArchiveFsProvider
import app.ee.provider.ftp.FtpFsProvider
import app.ee.provider.http.HttpFsProvider
import app.ee.provider.local.LocalFsProvider
import app.ee.provider.media.MediaFsProvider
import app.ee.provider.sftp.SftpFsProvider
import app.ee.provider.smb.SmbFsProvider
import app.ee.provider.webdav.WebDavFsProvider
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Composition root (docs/02-specification.md §4.1): the only place that wires
 * concrete providers into the VFS registry and owns app-scoped singletons.
 *
 * M3: network providers (SMB/SFTP/FTP/WebDAV/HTTP) behind [ConnectionSource],
 * Keystore-backed credential store, and the copy/move clipboard.
 */
class EeApp : Application() {

    lateinit var vfs: FsRegistry
        private set
    lateinit var database: EeDatabase
        private set
    lateinit var secretStore: KeystoreSecretStore
        private set

    val storageRoot: File get() = Environment.getExternalStorageDirectory()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode

    /** Copy/move clipboard shared across browser instances (M3 P0-5 ops). */
    private val _clipboard = MutableStateFlow<List<ClipboardEntry>>(emptyList())
    val clipboard: StateFlow<List<ClipboardEntry>> = _clipboard

    fun setClipboard(entries: List<ClipboardEntry>) {
        _clipboard.value = entries
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** In-memory profile cache + Keystore secret resolution for providers. */
    private class RoomConnectionSource(
        private val dao: ConnectionDao,
        private val store: KeystoreSecretStore,
        private val scope: CoroutineScope,
    ) : ConnectionSource {

        // immutable snapshot swapped on the Main dispatcher; safe to read
        // from provider threads
        @Volatile
        private var rows: List<ConnectionEntity> = emptyList()

        init {
            scope.launch {
                dao.observeAll().collect { rows = it }
            }
        }

        override fun resolve(id: String): NetConnection? {
            val longId = id.toLongOrNull() ?: return null
            val entity = rows.firstOrNull { it.id == longId } ?: return null
            return NetConnection(
                id = entity.id.toString(),
                name = entity.name,
                type = runCatching { FsType.valueOf(entity.fsType) }.getOrNull() ?: return null,
                host = entity.host,
                port = entity.port,
                path = entity.path,
                username = entity.username,
                secret = store.get("conn-${entity.id}") ?: "",
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        database = EeDatabase.get(this)
        secretStore = KeystoreSecretStore(this)

        val connectionSource = RoomConnectionSource(database.connections(), secretStore, appScope)

        vfs = FsRegistry()
        vfs.register(LocalFsProvider(storageRoot))
        vfs.register(ArchiveFsProvider())
        vfs.register(MediaFsProvider(this))
        // M3: network protocols
        vfs.register(SmbFsProvider(connectionSource))
        vfs.register(SftpFsProvider(connectionSource))
        vfs.register(FtpFsProvider(connectionSource))
        vfs.register(WebDavFsProvider(connectionSource))
        vfs.register(HttpFsProvider(connectionSource))
        // M4: vault — P2: cloud
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

    /** Persists a profile row + the password in the Keystore-backed store. */
    suspend fun saveProfile(form: ProfileForm): Long {
        val id = database.connections().add(
            ConnectionEntity(
                name = form.name,
                fsType = form.type.name,
                host = form.host,
                port = form.port,
                path = form.path,
                username = form.username,
                authRef = "conn-0", // replaced below with the real row id
                createdAt = System.currentTimeMillis(),
            ),
        )
        database.connections().updateAuthRef(id, "conn-$id")
        if (form.password.isNotEmpty()) secretStore.put("conn-$id", form.password)
        return id
    }

    fun deleteProfile(id: Long) {
        appScope.launch(Dispatchers.IO) {
            runCatching {
                database.connections().remove(id)
                secretStore.delete("conn-$id")
            }
        }
    }

    companion object {
        const val PREFS = "ee_prefs"
        const val KEY_THEME = "theme"
    }
}
