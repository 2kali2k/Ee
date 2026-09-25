package app.ee.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.ee.core.model.FsType
import kotlinx.coroutines.flow.Flow

/**
 * App persistence (docs/02-specification.md §4.3). Manual DI for now:
 * the `app` module owns the instance (EeApp.database). Hilt lands later.
 */

@Entity(tableName = "recents")
data class RecentFileEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val fsType: String,
    val sizeBytes: Long?,
    val lastOpenedAt: Long,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val uri: String,
    val name: String,
    val fsType: String,
    val addedAt: Long,
)

/** Saved connection profile. `authRef` is a reference into core-security
 *  (Keystore) — never plaintext credentials. */
@Entity(tableName = "connections")
data class ConnectionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fsType: String,
    val host: String,
    val port: Int,
    val path: String,
    val username: String,
    val authRef: String,
    val createdAt: Long,
)

/** Transfer station row (M2 — P1-9). `id` is the TransferTask UUID. */
@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val fromUri: String,
    val toPath: String,
    val state: String,
    val bytesDone: Long,
    val bytesTotal: Long,
    val error: String?,
    val createdAt: Long,
)

@Dao
interface RecentFileDao {
    @Query("SELECT * FROM recents ORDER BY lastOpenedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentFileEntity>>

    @Query("INSERT OR REPLACE INTO recents (uri, name, fsType, sizeBytes, lastOpenedAt) VALUES (:uri, :name, :fsType, :sizeBytes, :lastOpenedAt)")
    suspend fun touch(
        uri: String,
        name: String,
        fsType: String,
        sizeBytes: Long?,
        lastOpenedAt: Long,
    )

    @Query("DELETE FROM recents WHERE uri = :uri")
    suspend fun remove(uri: String)
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<FavoriteEntity>>

    @Query("INSERT OR IGNORE INTO favorites (uri, name, fsType, addedAt) VALUES (:uri, :name, :fsType, :addedAt)")
    suspend fun add(favorite: FavoriteEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE uri = :uri)")
    fun isFavorite(uri: String): Flow<Boolean>

    @Query("DELETE FROM favorites WHERE uri = :uri")
    suspend fun remove(uri: String)
}

@Dao
interface ConnectionDao {
    @Query("SELECT * FROM connections ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ConnectionEntity>>

    @Query("SELECT * FROM connections WHERE id = :id")
    fun observeById(id: Long): Flow<ConnectionEntity?>

    @Query("SELECT * FROM connections WHERE id = :id")
    suspend fun byId(id: Long): ConnectionEntity?

    @Query("INSERT INTO connections (name, fsType, host, port, path, username, authRef, createdAt) VALUES (:name, :fsType, :host, :port, :path, :username, :authRef, :createdAt)")
    suspend fun add(connection: ConnectionEntity): Long

    @Query("UPDATE connections SET authRef = :authRef WHERE id = :id")
    suspend fun updateAuthRef(id: Long, authRef: String)

    @Query("DELETE FROM connections WHERE id = :id")
    fun remove(id: Long)
}

/** Auto-backup job (M5 — P1-13): periodic source→dest tree mirror. */
@Entity(tableName = "auto_backups")
data class AutoBackupEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** Relative to the external storage root (e.g. "DCIM/Camera"). */
    val sourcePath: String,
    val destPath: String,
    /** 24 = daily, 168 = weekly (WorkManager minimum is 15 minutes). */
    val intervalHours: Int,
    val enabled: Boolean,
    val lastRunAt: Long = 0,
    val lastStatus: String? = null,
    val createdAt: Long,
)

@Dao
interface AutoBackupDao {
    @Query("SELECT * FROM auto_backups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AutoBackupEntity>>

    @Query("SELECT * FROM auto_backups WHERE id = :id")
    suspend fun getById(id: String): AutoBackupEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(job: AutoBackupEntity)

    @Query("DELETE FROM auto_backups WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers WHERE id = :id")
    fun observe(id: String): Flow<TransferEntity?>

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getById(id: String): TransferEntity?

    @Query("INSERT OR REPLACE INTO transfers (id, kind, fromUri, toPath, state, bytesDone, bytesTotal, error, createdAt) VALUES (:id, :kind, :fromUri, :toPath, :state, :bytesDone, :bytesTotal, :error, :createdAt)")
    suspend fun upsert(transfer: TransferEntity)

    @Query("DELETE FROM transfers WHERE id = :id")
    suspend fun remove(id: String)
}

class FsTypeConverter {
    @TypeConverter
    fun fromType(value: FsType): String = value.name

    @TypeConverter
    fun toType(value: String): FsType = runCatching { FsType.valueOf(value) }.getOrDefault(FsType.LOCAL)
}

@Database(
    entities = [
        RecentFileEntity::class,
        FavoriteEntity::class,
        ConnectionEntity::class,
        TransferEntity::class,
        AutoBackupEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
@TypeConverters(FsTypeConverter::class)
abstract class EeDatabase : RoomDatabase() {
    abstract fun recentFiles(): RecentFileDao
    abstract fun favorites(): FavoriteDao
    abstract fun connections(): ConnectionDao
    abstract fun transfers(): TransferDao
    abstract fun autoBackups(): AutoBackupDao

    companion object {
        @Volatile
        private var instance: EeDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transfers` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `kind` TEXT NOT NULL,
                        `fromUri` TEXT NOT NULL,
                        `toPath` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `bytesDone` INTEGER NOT NULL,
                        `bytesTotal` INTEGER NOT NULL,
                        `error` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `auto_backups` (
                        `id` TEXT NOT NULL PRIMARY KEY,
                        `name` TEXT NOT NULL,
                        `sourcePath` TEXT NOT NULL,
                        `destPath` TEXT NOT NULL,
                        `intervalHours` INTEGER NOT NULL,
                        `enabled` INTEGER NOT NULL,
                        `lastRunAt` INTEGER NOT NULL,
                        `lastStatus` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
            }
        }

        fun get(context: Context): EeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EeDatabase::class.java,
                    "ee.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
