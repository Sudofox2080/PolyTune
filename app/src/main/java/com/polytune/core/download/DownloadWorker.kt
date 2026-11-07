package com.polytune.core.download

import android.app.Application
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.polytune.AppGraph
import com.polytune.PolyTuneApp
import com.polytune.core.db.DownloadState
import com.polytune.core.proto.ProtocolMiddleware
import com.polytune.core.util.SongCodec
import com.polytune.source.api.Platform
import com.polytune.source.api.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 下载执行器。三种协议走三条路：HTTP/HLS/DASH 直接流式写盘；
 * WEBDAV 由中间件补 Basic 头；SMB 由 SmbProxy 转成本地 HTTP 再拉。
 * 断点续传靠 Range + .part 文件长度，服务器不认 206 时回退到整段重下。
 */
class DownloadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK)
        if (taskId.isNullOrBlank()) return@withContext Result.failure()
        val graph: AppGraph = (applicationContext as PolyTuneApp).graph
        val dao = graph.db.downloadDao()
        val task = dao.get(taskId) ?: return@withContext Result.failure()

        dao.setState(taskId, DownloadState.RUNNING, null)
        setProgress(workDataOf(KEY_PROGRESS to 0))

        var cleanup: () -> Unit = {}
        runCatching {
            val song = SongCodec.decode(task.songJson)
            val platform = Platform.of(task.platform) ?: throw IllegalStateException("未知音源")
            // 每次都重新解析直链：vkey/签名有效期通常只有几十分钟，落库的 URL 会过期。
            // 用聚合器而不是单源，是为了在源站不给直链时自动换到别家同曲版本。
            val plan = graph.aggregator.planDownload(song, Quality.of(task.wantQuality))
                ?: throw IllegalStateException("该音质无可用直链")
            val ep = plan.endpoint
            DownloadTrace.log(
                applicationContext,
                "worker resolve ${task.title} platform=$platform actual=${plan.actualPlatform.name} ${ep.quality.label} ${ep.protocol} tried=${plan.tried.joinToString(",")}",
            )

            val prepared = ProtocolMiddleware.prepare(applicationContext, ep)
            cleanup = prepared.cleanup ?: {}
            // 音频文件不走 SourceHttp，UA/Referer 得自己补；来源按聚合器实际换到的平台算，
            // 不是歌单里那首歌原本的平台（换源后 CDN 已经变了）。
            val headers: Map<String, String> =
                com.polytune.core.net.MediaHttpHeaders.forPlatform(plan.actualPlatform) +
                    ep.headers +
                    prepared.headers
            val uri: String = prepared.uri

            val outFile = File(task.filePath.ifBlank { task.savePath })
            outFile.parentFile?.mkdirs()
            val part = File(outFile.absolutePath + ".part")

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()

            var done: Long = if (part.exists()) part.length() else 0L
            var finished = false
            var guard = 0

            while (!finished && guard < 8) {
                guard++
                if (isStopped) {
                    dao.setState(taskId, DownloadState.PAUSED, null)
                    return@runCatching
                }
                val builder = Request.Builder().url(uri)
                headers.forEach { (k, v) -> builder.header(k, v) }
                if (done > 0) builder.header("Range", "bytes=$done-")

                client.newCall(builder.build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val append = done > 0 && resp.code == 206
                    if (!append) done = 0L
                    val body = resp.body ?: throw IllegalStateException("空响应体")
                    val total: Long =
                        if (body.contentLength() > 0) body.contentLength() + done else -1L
                    val input = body.byteStream()
                    try {
                        val raf = RandomAccessFile(part, "rw")
                        try {
                            raf.seek(done)
                            val buf = ByteArray(64 * 1024)
                            var lastPct = -1
                            while (true) {
                                if (isStopped) break
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val pct = (done * 100 / total).toInt()
                                    if (pct >= lastPct + 2) {
                                        lastPct = pct
                                        dao.progress(taskId, done, total)
                                        setProgress(workDataOf(KEY_PROGRESS to pct))
                                    }
                                }
                            }
                        } finally {
                            raf.close()
                        }
                    } finally {
                        input.close()
                    }
                    if (total > 0 && done >= total) finished = true
                }
                if (!finished && part.length() == done && done > 0L && guard >= 2) break
                if (!finished && done == 0L) break
            }

            if (isStopped) {
                dao.setState(taskId, DownloadState.PAUSED, null)
                return@runCatching
            }
            if (!part.exists() || part.length() == 0L) throw IllegalStateException("未写入任何数据")
            if (!part.renameTo(outFile)) {
                part.copyTo(outFile, overwrite = true)
                part.delete()
            }
            dao.progress(taskId, outFile.length(), outFile.length())
            dao.refreshUrl(taskId, uri, headers.entries.joinToString("&") { "${it.key}=${it.value}" })
            dao.setState(taskId, DownloadState.DONE, null)
            DownloadTrace.log(applicationContext, "worker done ${task.title} ${outFile.length()}B -> ${outFile.absolutePath}")
        }.onFailure { e ->
            DownloadTrace.log(applicationContext, "worker fail ${task.title}: ${e.javaClass.simpleName} ${e.message}")
            dao.setState(
                taskId,
                if (isStopped) DownloadState.PAUSED else DownloadState.FAILED,
                e.message ?: e.javaClass.simpleName,
            )
        }
        runCatching { cleanup() }
        if (isStopped) Result.retry() else Result.success()
    }

    companion object {
        const val KEY_TASK = "taskId"
        const val KEY_PROGRESS = "progress"

        fun enqueue(ctx: Context, taskId: String) {
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInputData(workDataOf(KEY_TASK to taskId))
                .addTag("download-$taskId")
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("download-$taskId", ExistingWorkPolicy.KEEP, req)
        }

        fun cancel(ctx: Context, taskId: String) {
            WorkManager.getInstance(ctx).cancelUniqueWork("download-$taskId")
        }
    }
}
