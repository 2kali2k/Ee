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

    @Query("INSERT INTO connections (name, fsType, host, port, path, username, authRef, createdAt) VALUES (:name, :fsType, :host, :port, :path, :username, :authRef, :createdAt)")
    suspend fun add(connection: ConnectionEntity): Long

    @Query("DELETE FROM connections WHERE id = :id")
    suspend fun remove(id: Long)
}

class FsTypeConverter {
    @TypeConverter
    fun fromType(value: FsType): String = value.name

    @TypeConverter
    fun toType(value: String): FsType = runCatching { FsType.valueOf(value) }.getOrDefault(FsType.LOCAL)
}

@Database(
    entities = [RecentFileEntity::class, FavoriteEntity::class, ConnectionEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(FsTypeConverter::class)
abstract class EeDatabase : RoomDatabase() {
    abstract fun recentFiles(): RecentFileDao
    abstract fun favorites(): FavoriteDao
    abstract fun connections(): ConnectionDao

    companion object {
        @Volatile
        private var instance: EeDatabase? = null

        fun get(context: Context): EeDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    EeDatabase::class.java,
                    "ee.db",
                ).build().also { instance = it }
            }
    }
}
