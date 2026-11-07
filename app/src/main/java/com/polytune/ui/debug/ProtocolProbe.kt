package com.polytune.ui.debug

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.polytune.PolyTuneApp
import com.polytune.core.db.DownloadState
import com.polytune.core.db.DownloadTaskEntity
import com.polytune.core.download.DownloadTrace
import com.polytune.core.download.DownloadWorker
import com.polytune.core.proto.ProtocolMiddleware
import com.polytune.core.util.SongCodec
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.Platform
import com.polytune.source.api.Protocol
import com.polytune.source.api.Quality
import com.polytune.source.api.Song
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 协议自检页：把一条 PlayEndpoint 直接喂给中间件，验证 WEBDAV / SMB 两条挂载路径。
 *
 * 单独开一页是因为正常播放/下载入口都先经过聚合器解析直链，而挂载协议没有聚合器
 * 可解析的来源，直链只能手工提供。这里只做协议层验证，不参与业务逻辑。
 */
@Composable
fun ProtocolProbe(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as PolyTuneApp
    val ctx: Context = app
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("http://127.0.0.1:8080/test.wav") }
    var user by remember { mutableStateOf("test") }
    var pass by remember { mutableStateOf("test123") }
    var proto by remember { mutableStateOf(Protocol.WEBDAV) }
    var busy by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("就绪") }
    var probeMode by remember { mutableStateOf(true) }
    var workerMode by remember { mutableStateOf(false) }

    // SMB 的账号密码写在 URL 里；输入框有值就拼进去，省得手打一遍。
    fun realUrl(): String {
        val u = url.trim()
        if (proto != Protocol.SMB || u.contains('@') || user.isBlank()) return u
        val head = if (u.startsWith("smb:\\\\")) "smb:\\\\" else "smb://"
        return head + user + ":" + pass + "@" + u.removePrefix(head)
    }

    /**
     * 按地址自身的前缀纠正协议。
     * 之前把 smb:// 地址粘到 HTTP 标签页里，会拿「HTTP 协议 + smb 地址」去发请求，
     * 报 UnknownHostException: http 这种完全看不出问题的错。地址长什么样就以什么为准。
     */
    fun effectiveProtocol(): Protocol {
        val u = url.trim().lowercase()
        return when {
            u.startsWith("smb://") || u.startsWith("smb:\\\\") -> Protocol.SMB
            u.startsWith("file://") || u.startsWith("/") -> Protocol.LOCAL
            u.contains(".m3u8") -> Protocol.HLS
            u.contains(".mpd") -> Protocol.DASH
            u.startsWith("http://") || u.startsWith("https://") -> proto
            else -> proto
        }
    }

    fun endpoint(): PlayEndpoint {
        val p = effectiveProtocol()
        val headers = if (p == Protocol.WEBDAV) {
            mapOf("user" to user.trim(), "pass" to pass)
        } else {
            emptyMap()
        }
        return PlayEndpoint(
            url = if (p == Protocol.SMB && !url.contains('@') && user.isNotBlank()) {
                "smb://" + user + ":" + pass + "@" + url.trim().removePrefix("smb://")
            } else {
                url.trim()
            },
            protocol = p,
            headers = headers,
            quality = Quality.STANDARD,
        )
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("协议自检", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("关闭") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Protocol.WEBDAV, Protocol.SMB, Protocol.HTTP).forEach { p ->
                    val selected = p == proto
                    Button(
                        onClick = { proto = p },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Text(if (selected) "[" + p.name + "]" else p.name) }
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL / " + proto.name) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleChip("探测", probeMode) { probeMode = true }
                ToggleChip("内部worker", !probeMode) { probeMode = false }
                ToggleChip("自检worker", workerMode) { workerMode = true }
            }
            Text(
                if (probeMode) {
                    "探测：只走中间件 + OkHttp，不经过聚合器，验证挂载协议的取流与 Range 续传。"
                } else {
                    "内部 worker：跑真正的 DownloadWorker。它第一件事就问聚合器要直链，" +
                        "task.url 被丢弃，挂载协议在这条路上必然失败——这一项是出示证据用的。"
                },
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ep = endpoint()
                            log = runCatching {
                                val prepared = withContext(Dispatchers.IO) {
                                    ProtocolMiddleware.prepare(ctx, ep)
                                }
                                DownloadTrace.log(
                                    ctx,
                                    "probe prepare protocol=" + ep.protocol + " in=" + ep.url +
                                        " out=" + prepared.uri +
                                        " headers=" + prepared.headers.keys,
                                )
                                app.graph.engine.prepare(song(), ep, "probe")
                                "播放已提交\nin=" + ep.url + "\nout=" + prepared.uri
                            }.getOrElse { "播放失败 " + it.javaClass.simpleName + ": " + it.message }
                            busy = false
                        }
                    },
                ) { Text("用播放器打开") }

                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ep = endpoint()
                            log = runCatching {
                                withContext(Dispatchers.IO) {
                                    if (workerMode) {
                                        enqueueProbeDownload(app, ep, realWorker = !probeMode)
                                    } else {
                                        directFetch(app, ep)
                                    }
                                }
                            }.getOrElse { "下载失败 " + it.javaClass.simpleName + ": " + it.message }
                            busy = false
                        }
                    },
                ) { Text("用下载器下载") }
            }

            Text(
                "播放看 files/logs/download.log 的 player error / http open 行；" +
                    "下载看 download_task 表的 state 与 downloadedBytes。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(log, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun song(): Song = Song(
    id = "probe",
    platform = Platform.QQ,
    title = "协议自检",
    artists = listOf("polytune"),
    album = "probe",
)

/**
 * 探测下载：中间件给什么就用什么，直接把流写盘，记录状态码 / Content-Length / 实收字节。
 * 不经过聚合器，也不经过 DownloadWorker，所以能单独判断「挂载协议这一层」通不通。
 */
private fun directFetch(app: PolyTuneApp, ep: PlayEndpoint): String {
    var cleanupFun: () -> Unit = {}
    return runCatching {
        val prepared = runBlocking { ProtocolMiddleware.prepare(app, ep) }
        cleanupFun = prepared.cleanup ?: {}
        val headers = ep.headers + prepared.headers
        val dir = File(
            app.getExternalFilesDir("Music") ?: File(app.filesDir, "Music"),
            "PROBE/" + ep.protocol.name,
        ).apply { mkdirs() }
        val name = ep.protocol.name + "_" + File(prepared.uri.substringBefore('?')).name.ifBlank { "probe.bin" }
        val target = File(dir, name)
        val client = OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url(prepared.uri)
        headers.forEach { (k, v) ->
            if (k != "user" && k != "pass") req.header(k, v)
        }
        // 先要 1 字节探 Range：真机播放器与续传都靠它
        runCatching {
            val probe = client.newCall(
                Request.Builder().url(prepared.uri)
                    .apply { headers.forEach { (k, v) -> if (k != "user" && k != "pass") header(k, v) } }
                    .header("Range", "bytes=0-0")
                    .build(),
            ).execute()
            probe.use {
                DownloadTrace.log(
                    app,
                    "probe range code=" + it.code + " cr=" + it.header("Content-Range") +
                        " len=" + it.header("Content-Length"),
                )
            }
        }
        client.newCall(req.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP " + resp.code)
            val body = resp.body ?: error("空响应体")
            val total = body.contentLength()
            val sink = target.outputStream()
            var got = 0L
            sink.use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                    }
                }
            }
            DownloadTrace.log(
                app,
                "probe fetch " + ep.protocol + " uri=" + prepared.uri + " code=" + resp.code +
                    " total=" + total + " got=" + got + " -> " + target.absolutePath,
            )
            "code=" + resp.code + " total=" + total + " got=" + got +
                "\n文件=" + target.absolutePath + " 存在=" + target.exists() + " 大小=" + target.length()
        }
    }.also { runCatching { cleanupFun() } }.getOrThrow()
}

@Composable
private fun ToggleChip(text: String, on: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(text) },
    )
}

/**
 * 直接建 download_task 行再排 worker。下载仓库的 enqueue 会先问聚合器要直链，
 * 挂载协议没有直链可要，所以这里手工建任务，其余字段按工人的要求填。
 *
 * [realWorker] 为 true 时排的是真正的 DownloadWorker——它会忽略 task.url 去解析直链，
 * 于是挂载协议必然失败；为 false 时排 ProbeFetchWorker，走和工人相同的协议栈但不问聚合器。
 */
private fun enqueueProbeDownload(app: PolyTuneApp, ep: PlayEndpoint, realWorker: Boolean): String {
    val id = "probe-" + System.currentTimeMillis()
    val dir = File(
        app.getExternalFilesDir("Music") ?: File(app.filesDir, "Music"),
        "PROBE/" + ep.protocol.name,
    ).apply { mkdirs() }
    val name = ep.protocol.name + "_" + File(ep.url.substringBefore('?')).name.ifBlank { "probe.bin" }
    val target = File(dir, name)
    val s = song()
    val now = System.currentTimeMillis()
    val task = DownloadTaskEntity(
        id = id,
        songId = s.id,
        platform = s.platform.name,
        title = s.title + " " + ep.protocol.name,
        artist = "polytune",
        album = "probe",
        coverUrl = null,
        quality = Quality.STANDARD.name,
        wantQuality = Quality.STANDARD.name,
        protocol = ep.protocol.name,
        url = ep.url,
        headersJson = ep.headers.entries.joinToString("&") { it.key + "=" + it.value },
        savePath = target.absolutePath,
        filePath = target.absolutePath,
        state = DownloadState.PENDING,
        createdAt = now,
        updatedAt = now,
        songJson = SongCodec.encode(s),
    )
    runBlocking { app.graph.db.downloadDao().upsert(task) }
    DownloadTrace.log(
        app,
        "probe enqueue " + ep.protocol + " " + ep.url + " -> " + target.absolutePath +
            " worker=" + (if (realWorker) "DownloadWorker" else "ProbeFetchWorker"),
    )
    if (realWorker) {
        DownloadWorker.enqueue(app, id)
        return "已入队（真 DownloadWorker）" + id + "\n目标=" + target.absolutePath
    }
    ProbeFetchWorker.enqueue(app, id)
    return "已入队（自检 worker）" + id + "\n目标=" + target.absolutePath
}
