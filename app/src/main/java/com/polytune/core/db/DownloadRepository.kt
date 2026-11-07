package com.polytune.core.db

import android.content.Context
import com.polytune.core.download.DownloadWorker
import com.polytune.core.download.DownloadTrace
import com.polytune.core.util.SongCodec
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.Platform
import com.polytune.source.api.Quality
import com.polytune.source.api.Song
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * 下载仓库。把「一首歌 → 一条可执行任务」的转换收在一处：
 * 入库时就先把直链换源解析好（原源拿不到就换别家同曲），这样下载页能立刻显示成功还是失败的原因，
 * 而不是排进队列后一直转圈。WorkManager 的执行体仍会重新取一次直链，因为 vkey 会过期。
 */
class DownloadRepository(
    private val ctx: Context,
    private val db: PolyDatabase,
    private val aggregator: Aggregator,
) {

    fun observeAll(): Flow<List<DownloadTaskEntity>> = db.downloadDao().observeAll()

    /** 直接查一次，不经过 Flow。用于观察链异常（例如 WAL 被判定作废）后手动刷新界面。 */
    suspend fun loadOnce(): List<DownloadTaskEntity> = db.downloadDao().loadAll()

    suspend fun get(id: String): DownloadTaskEntity? = db.downloadDao().get(id)

    fun targetDir(platform: Platform): File =
        File(ctx.getExternalFilesDir("Music") ?: File(ctx.filesDir, "Music"), platform.name)

    fun fileFor(song: Song, quality: Quality): File =
        File(File(targetDir(song.platform), quality.name), baseName(song) + ".mp3")

    private fun baseName(song: Song): String {
        val raw = "${song.artists.firstOrNull().orEmpty()} - ${song.title}"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80)
            .ifBlank { song.id }
    }

    /**
     * 入库并排队。返回值是任务 id，[knownSiblings] 是详情页已经查到的其它源版本，
     * 传进来就不会在换源时再搜一遍对方曲库。
     */
    suspend fun enqueue(
        song: Song,
        quality: Quality,
        knownSiblings: List<Song> = emptyList(),
    ): String {
        val id = newTaskId()
        val plan = runCatching { aggregator.planDownload(song, quality, knownSiblings) }
            .onFailure { DownloadTrace.log(ctx, "enqueue plan 异常 ${it.javaClass.simpleName}: ${it.message}") }
            .getOrNull()

        val now = System.currentTimeMillis()
        if (plan == null) {
            DownloadTrace.log(ctx, "enqueue 解析失败 song=${song.title}/${song.platform.name} q=${quality.name}")
            val fallback = File(File(targetDir(song.platform), quality.name), baseName(song) + ".mp3").absolutePath
            db.downloadDao().upsert(
                DownloadTaskEntity(
                    id = id,
                    songId = song.id,
                    platform = song.platform.name,
                    title = song.title,
                    artist = song.artistsText,
                    album = song.album,
                    coverUrl = song.coverUrl,
                    quality = quality.name,
                    wantQuality = quality.name,
                    protocol = "HTTP",
                    url = "",
                    headersJson = "{}",
                    savePath = fallback,
                    filePath = fallback,
                    downloadedBytes = 0,
                    totalBytes = -1,
                    state = DownloadState.FAILED,
                    errMsg = "四个音源都没有这首歌的可用直链，可点换源重试",
                    createdAt = now,
                    updatedAt = now,
                    songJson = SongCodec.encode(song),
                ),
            )
            return id
        }

        val dir = File(targetDir(plan.actualPlatform), plan.endpoint.quality.name)
        val target = File(dir, "${plan.fileName}.${plan.extension}")
        val entity = DownloadTaskEntity(
            id = id,
            songId = plan.song.id,
            platform = plan.actualPlatform.name,
            title = song.title,
            artist = song.artistsText,
            album = song.album,
            coverUrl = song.coverUrl,
            quality = plan.endpoint.quality.name,
            wantQuality = quality.name,
            protocol = plan.endpoint.protocol.name,
            url = plan.endpoint.url,
            headersJson = "{}",
            savePath = target.absolutePath,
            filePath = target.absolutePath,
            downloadedBytes = 0,
            totalBytes = plan.endpoint.sizeBytes ?: -1,
            state = DownloadState.PENDING,
            errMsg = null,
            createdAt = now,
            updatedAt = now,
            songJson = SongCodec.encode(plan.song),
        )
        db.downloadDao().upsert(entity)
        DownloadTrace.log(
            ctx,
            "enqueue ok id=$id ${song.title} ${plan.actualPlatform.name} ${plan.endpoint.quality.label} ${plan.endpoint.protocol} ${plan.extension} tried=${plan.tried.joinToString(",")}",
        )
        DownloadWorker.enqueue(ctx, id)
        return id
    }

    suspend fun pause(id: String) {
        DownloadWorker.cancel(ctx, id)
        db.downloadDao().setState(id, DownloadState.PAUSED)
    }

    suspend fun resume(id: String) {
        db.downloadDao().setState(id, DownloadState.PENDING)
        DownloadWorker.enqueue(ctx, id)
    }

    suspend fun remove(id: String, deleteFile: Boolean = true) {
        DownloadWorker.cancel(ctx, id)
        val t = db.downloadDao().get(id)
        if (deleteFile && t != null) {
            runCatching { File(t.filePath).delete() }
            runCatching { File(t.filePath + ".part").delete() }
        }
        db.downloadDao().delete(id)
    }

    suspend fun requeueAllUnfinished() {
        db.downloadDao().active().forEach { t ->
            db.downloadDao().setState(t.id, DownloadState.PENDING)
            DownloadWorker.enqueue(ctx, t.id)
        }
    }
}

internal fun String.sanitize(): String =
    replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80).ifBlank { "untitled" }
