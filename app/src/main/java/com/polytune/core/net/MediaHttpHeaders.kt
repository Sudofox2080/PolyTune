package com.polytune.core.net

import com.polytune.source.api.Platform

/**
 * 音频直链要带的请求头。
 *
 * 为什么单独放一份：接口请求都过 [SourceHttp]，那里的拦截器会补 UA / Referer / dfid，
 * 但音频文件不进 SourceHttp——播放走 Media3 的 DataSource，下载走 DownloadWorker 自己的
 * OkHttp。这条路径上如果不带头，网易云的 CDN 会直接回 403，而播放器之所以能放，
 * 只是因为它自己塞了一个浏览器 UA。把 UA 抽到这里，播放和下载用同一套，才不会出现
 * 「能听不能下」。
 */
object MediaHttpHeaders {

    private const val BROWSER =
        "Mozilla/5.0 (Linux; Android 14; PolyTune) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36"

    /** 按来源给一份默认头；调用方自己的头放在后面覆盖即可（同 key 后者生效）。 */
    fun forPlatform(platform: Platform): Map<String, String> = when (platform) {
        Platform.QQ -> mapOf(
            "User-Agent" to "$BROWSER QQMusic/13.5",
            "Referer" to "https://y.qq.com/",
        )

        Platform.NETEASE -> mapOf(
            "User-Agent" to "NeteaseMusic/9.1.20.240603200340(9012004);Dalvik/2.1.0 (Linux; U; Android 14)",
            "Referer" to "https://music.163.com/",
        )

        Platform.KUGOU -> mapOf(
            "User-Agent" to "Android712-AndroidPhone-11451-2-0-1010000101-0-0",
            "Referer" to "https://m.kugou.com/",
        )
    }
}
