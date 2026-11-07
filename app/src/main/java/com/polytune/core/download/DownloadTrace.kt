package com.polytune.core.download

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 下载与播放链路的行为日志。小米这类 ROM 会把应用自定义 tag 的 logcat 吞掉，
 * 调试时只能靠落盘文件看：files/logs/download.log，按行追加，超过 256KB 自动截断前半。
 */
object DownloadTrace {

    private const val TAG = "PolyTuneDownload"
    private const val MAX_BYTES = 256L * 1024
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun log(ctx: Context, msg: String) {
        Log.i(TAG, msg)
        runCatching {
            synchronized(lock) {
                val dir = File(ctx.filesDir, "logs").apply { mkdirs() }
                val f = File(dir, "download.log")
                if (f.length() > MAX_BYTES) {
                    val keep = f.readText().takeLast((MAX_BYTES / 2).toInt())
                    f.writeText(keep)
                }
                f.appendText("${fmt.format(Date())} $msg\n")
            }
        }
    }

    fun tail(ctx: Context, lines: Int = 200): String = runCatching {
        File(File(ctx.filesDir, "logs"), "download.log").readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")
}

/**
 * 全局 application context 的落脚点。音源这类不持有 Context 的类要写日志时从这里取，
 * 由 PolyTuneApp.onCreate 填一次，之后永远有效（application context 不会泄漏）。
 */
object TraceCtx {
    @Volatile
    var app: android.content.Context? = null
}
