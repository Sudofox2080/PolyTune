package com.polytune

import android.app.Instrumentation
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.polytune.core.proto.ProtocolMiddleware
import com.polytune.player.protocolFor
import com.polytune.source.api.Platform
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.Protocol
import com.polytune.source.api.Quality
import com.polytune.source.api.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 直接走真实 PlaybackService + ExoPlayer 的设备烟测，不依赖额外测试框架。
 * adb shell am instrument -w -e url '<url>' -e protocol LOCAL com.polytune.test/com.polytune.FormatSmokeInstrumentation
 */
class FormatSmokeInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle

    override fun onCreate(arguments: Bundle?) {
        this.arguments = arguments ?: Bundle.EMPTY
        super.onCreate(arguments)
        start()
    }

    override fun onStart() = runBlocking {
        val url = arguments.getString("url").orEmpty()
        val protocol = runCatching {
            Protocol.valueOf(arguments.getString("protocol") ?: "HTTP")
        }.getOrDefault(Protocol.HTTP)
        if (url.isBlank()) {
            finish(2, Bundle().apply { putString("report", "missing -e url") })
            return@runBlocking
        }
        try {
            val context = targetContext.applicationContext
            val app = context as PolyTuneApp
            repeat(30) {
                if (runCatching { app.graph }.isSuccess) return@repeat
                delay(100)
            }
            startActivitySync(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            val engine = app.graph.engine
            withContext(Dispatchers.IO) {
                val prepared = ProtocolMiddleware.prepare(context, PlayEndpoint(url, protocol))
                check(prepared.protocol == protocol || protocol != Protocol.SMB) {
                    "middleware protocol mismatch: ${prepared.protocol}"
                }
                val resolved = protocolFor(prepared.uri, prepared.protocol)
                val sample = Song(
                    id = "format-smoke",
                    platform = Platform.NETEASE,
                    title = "Format smoke: $url",
                    artists = listOf("PolyTune"),
                    availableQualities = listOf(Quality.LOSSLESS),
                )
                withContext(Dispatchers.Main) {
                    engine.prepare(sample, PlayEndpoint(url, protocol), "format-smoke")
                }
                var advanced = false
                for (i in 1..75) {
                    delay(200)
                    val s = engine.state.value
                    if (s.error != null) error("player error: ${s.error}")
                    if (s.isPlaying && s.positionMs >= 500) {
                        advanced = true
                        break
                    }
                }
                check(advanced) { "position did not reach 500ms: ${engine.state.value}" }
                withContext(Dispatchers.Main) {
                    engine.currentPlayer?.pause()
                    engine.currentPlayer?.seekTo(1000)
                    engine.currentPlayer?.play()
                }
                // 网络流在 seek 后会重新缓冲，不能固定等 700ms 就断言在播；
                // 这里给到 5 秒，仍然要求位置确实越过 1 秒，避免把“停在原地”误判成通过。
                var resumed = false
                for (i in 1..25) {
                    delay(200)
                    val s = engine.state.value
                    if (s.error != null) error("player error after resume: ${s.error}")
                    if (s.isPlaying && s.positionMs >= 1000) {
                        resumed = true
                        break
                    }
                }
                val state = engine.state.value
                check(resumed) { "resume failed: $state" }
                withContext(Dispatchers.Main) { engine.currentPlayer?.pause() }
                val report = "PASS url=$url prepared=${prepared.uri} protocol=$resolved position=${state.positionMs} duration=${state.durationMs}"
                Log.i("FormatSmoke", report)
                finish(Activity.RESULT_OK, Bundle().apply { putString("report", report) })
            }
        } catch (t: Throwable) {
            Log.e("FormatSmoke", "FAIL", t)
            finish(Activity.RESULT_CANCELED, Bundle().apply {
                putString("report", "FAIL ${t.javaClass.simpleName}: ${t.message}")
            })
        }
    }
}

