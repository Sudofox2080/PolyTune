package com.polytune.ui.debug

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.polytune.PolyTuneApp
import com.polytune.core.db.DownloadState
import com.polytune.core.download.DownloadTrace
import com.polytune.core.proto.ProtocolMiddleware
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.Protocol
import com.polytune.source.api.Quality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * 自检下载工人：和 [com.polytune.core.download.DownloadWorker] 走同一套协议栈与 Range 续传写法，
 * 唯一区别是不问聚合器要直链，直接用任务行里存的 url。
 *
 * 之所以要这一层：正式工人第一步就是 `aggregator.planDownload`，挂载协议（WEBDAV/SMB）
 * 不是任何音源给出来的直链，聚合器必然返回 null，于是 url 字段形同摆设。
 * 用它来单独确认「中间件 + Range 续传」这段逻辑本身是对的。
 */
class ProbeFetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val taskId = inputData.getString(KEY_TASK) ?: return@withContext Result.failure()
        val app = applicationContext as PolyTuneApp
        val dao = app.graph.db.downloadDao()
        val task = dao.get(taskId) ?: return@withContext Result.failure()
        dao.setState(taskId, DownloadState.RUNNING, null)

        var cleanup: () -> Unit = {}
        runCatching {
            val protocol = Protocol.entries.firstOrNull { it.name == task.protocol }
                ?: throw IllegalStateException("未知协议 " + task.protocol)
            val headers = task.headersJson.split("&")
                .filter { it.contains("=") }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            val ep = PlayEndpoint(
                url = task.url,
                protocol = protocol,
                headers = headers,
                quality = Quality.of(task.quality),
            )
            val prepared = ProtocolMiddleware.prepare(applicationContext, ep)
            cleanup = prepared.cleanup ?: {}
            val out = File(task.filePath.ifBlank { task.savePath })
            out.parentFile?.mkdirs()
            val part = File(out.absolutePath + ".part")
            var done = if (part.exists()) part.length() else 0L

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
            val all = ep.headers + prepared.headers
            val builder = Request.Builder().url(prepared.uri)
            all.forEach { (k, v) -> if (k != "user" && k != "pass") builder.header(k, v) }
            if (done > 0) builder.header("Range", "bytes=" + done + "-")
            var code = 0
            var total = -1L
            client.newCall(builder.build()).execute().use { resp ->
                code = resp.code
                if (!resp.isSuccessful) throw IllegalStateException("HTTP " + code)
                val append = done > 0 && code == 206
                if (!append) done = 0L
                val body = resp.body ?: throw IllegalStateException("空响应体")
                if (body.contentLength() > 0) total = body.contentLength() + done
                RandomAccessFile(part, "rw").use { raf ->
                    raf.seek(done)
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                        }
                    }
                }
            }
            if (!part.exists() || part.length() == 0L) throw IllegalStateException("未写入任何数据")
            if (!part.renameTo(out)) {
                part.copyTo(out, overwrite = true)
                part.delete()
            }
            dao.progress(taskId, out.length(), out.length())
            dao.setState(taskId, DownloadState.DONE, null)
            DownloadTrace.log(
                applicationContext,
                "probe done " + task.protocol + " code=" + code + " total=" + total +
                    " bytes=" + out.length() + " -> " + out.absolutePath,
            )
        }.onFailure { e ->
            DownloadTrace.log(
                applicationContext,
                "probe fail " + task.protocol + ": " + e.javaClass.simpleName + " " + e.message,
            )
            dao.setState(taskId, DownloadState.FAILED, e.message ?: e.javaClass.simpleName)
        }
        runCatching { cleanup() }
        Result.success()
    }

    companion object {
        const val KEY_TASK = "taskId"

        fun enqueue(ctx: Context, taskId: String) {
            val req = OneTimeWorkRequestBuilder<ProbeFetchWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .setInputData(workDataOf(KEY_TASK to taskId))
                .addTag("probe-" + taskId)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork("probe-" + taskId, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
