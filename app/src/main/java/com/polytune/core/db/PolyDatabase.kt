package com.polytune.core.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.polytune.source.api.Quality
import kotlinx.coroutines.flow.Flow

/* ---------------- 下载任务 ---------------- */

@Entity(
    tableName = "download_task",
    indices = [Index("state"), Index("songId")],
)
data class DownloadTaskEntity(
    @PrimaryKey val id: String,
    val songId: String,
    val platform: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,
    val quality: String,
    val protocol: String,
    val url: String,
    val headersJson: String,
    val savePath: String,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = -1,
    val state: String = DownloadState.PENDING,
    val errMsg: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** 完整 Song 的 JSON，worker 需要它才能重新取直链。 */
    val songJson: String = "",
    /** 实际落盘路径，与 savePath 分离是因为 savePath 在建任务时就定下，这里记录最终文件。 */
    val filePath: String = "",
    /** 期望音质。 */
    val wantQuality: String = Quality.STANDARD.name,
)

object DownloadState {
    const val PENDING = "PENDING"
    const val RUNNING = "RUNNING"
    const val PAUSED = "PAUSED"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
    const val CANCELED = "CANCELED"
}

@Dao
interface DownloadDao {

    @Query("SELECT * FROM download_task ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    /** 一次性全量读取。Flow 的失效通知只认本进程内的写操作，跨进程/异常恢复后的数据要靠它兜底。 */
    @Query("SELECT * FROM download_task ORDER BY createdAt DESC")
    suspend fun loadAll(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE state IN ('PENDING','RUNNING','PAUSED','FAILED') ORDER BY createdAt ASC")
    suspend fun active(): List<DownloadTaskEntity>

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun get(id: String): DownloadTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: DownloadTaskEntity)

    @Query("UPDATE download_task SET state=:state, errMsg=:err, updatedAt=:now WHERE id=:id")
    suspend fun setState(id: String, state: String, err: String? = null, now: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET downloadedBytes=:done, totalBytes=:total, state='RUNNING', updatedAt=:now WHERE id=:id")
    suspend fun progress(id: String, done: Long, total: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE download_task SET url=:url, headersJson=:headers, state='PENDING', errMsg=NULL WHERE id=:id")
    suspend fun refreshUrl(id: String, url: String, headers: String)

    @Query("DELETE FROM download_task WHERE id=:id")
    suspend fun delete(id: String)
}

/* ---------------- 歌单 / 收藏 ---------------- */

@Entity(tableName = "playlist", primaryKeys = ["platform", "id"])
data class PlaylistEntity(
    val platform: String,
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val subscribed: Boolean = false,
    val syncedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "playlist_track",
    primaryKeys = ["playlistKey", "platform", "songId"],
    indices = [Index("playlistKey")],
)
data class PlaylistTrackEntity(
    val playlistKey: String,
    val platform: String,
    val songId: String,
    val title: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val orderIndex: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlist ORDER BY subscribed DESC, syncedAt DESC")
    fun observePlaylists(): Flow<List<PlaylistEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylists(list: List<PlaylistEntity>)

    @Query("SELECT * FROM playlist_track WHERE playlistKey = :key ORDER BY orderIndex ASC")
    fun observeTracks(key: String): Flow<List<PlaylistTrackEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(list: List<PlaylistTrackEntity>)

    @Query("DELETE FROM playlist_track WHERE playlistKey = :key")
    suspend fun clearTracks(key: String)

    @Query("UPDATE playlist SET subscribed = :v WHERE platform = :p AND id = :id")
    suspend fun setSubscribed(p: String, id: String, v: Boolean)
}

/* ---------------- 歌曲缓存（详情与歌词） ---------------- */

@Entity(tableName = "song_cache", primaryKeys = ["platform", "songId"])
data class SongCacheEntity(
    val platform: String,
    val songId: String,
    val json: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

@Dao
interface SongCacheDao {
    @Query("SELECT * FROM song_cache WHERE platform=:p AND songId=:id")
    suspend fun get(p: String, id: String): SongCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: SongCacheEntity)

    @Query("DELETE FROM song_cache WHERE cachedAt < :before")
    suspend fun evict(before: Long)
}

/* ---------------- 数据库 ---------------- */

@Database(
    entities = [DownloadTaskEntity::class, PlaylistEntity::class, PlaylistTrackEntity::class, SongCacheEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PolyDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun songCacheDao(): SongCacheDao

    companion object {
        private const val DB_NAME = "polytune.db"

        /**
         * 建库。开库前先做一次 WAL 自检：WAL 与主库的 salt/校验和对不上时，SQLite 会静默忽略整个 WAL，
         * 表现成「库里明明有数据，查询永远返回空」。这里检测到不一致就把 WAL 帧回放进主库、
         * 落后主库并截断 WAL，再交给 Room 打开，做到只读回数据、不丢账。
         */
        fun build(context: Context): PolyDatabase {
            val app = context.applicationContext
            runCatching { WalRecovery.repairIfNeeded(app, DB_NAME) }
                .onFailure { DownloadTraceWarn("wal recovery 跳过: ${it.javaClass.simpleName} ${it.message}") }
            return Room.databaseBuilder(app, PolyDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun DownloadTraceWarn(msg: String) {
            android.util.Log.w("PolyDatabase", msg)
        }
    }
}
