package com.polytune.player

import com.polytune.source.api.Protocol
import com.polytune.source.api.Quality

/**
 * 一种容器/编码格式的能力描述。
 *
 * 之所以要给每种格式写死 [bySystem] 与 [byExtension] 两列，而不是运行时去问 MediaCodec：
 * 问 MediaCodec 只能知道「解码器在不在」，不知道「容器能不能被拆分器认出来」。
 * 例如 ALAC 在 m4a 容器里，系统 MediaExtractor 认 m4a，但 PCM 编码要找 ffmpeg；
 * DSD 则是容器拆分器和解码器两头都没有。分开两列才能在界面上说清楚缺的是哪一环。
 *
 * @param ext 归一化扩展名（小写、不带点）
 * @param mime 正常喂给 Media3 时它会推导出的 MIME 类型，仅用于排查日志
 * @param label 给人看的名字
 * @param bySystem 系统（Android 自带 MediaCodec + MediaExtractor）能否直接解
 * @param byExtension 需要 ffmpeg 扩展渲染器才能解，ffmpeg 没装进去时这类格式必然失败
 */
data class AudioFormat(
    val ext: String,
    val mime: String,
    val label: String,
    val bySystem: Boolean,
    val byExtension: Boolean,
)

/**
 * 格式清单。
 *
 * 清单里每一条都对应一个真实会被遇到的来源：QQ/网易给 flac/m4a，酷狗给 m4a/ogg，
 * 用户自建的 WebDAV/SMB 库里常见 ape/dsd/mka 这类系统解码器不覆盖的格式。
 * 不在清单里的扩展名不会被拒绝——[canPlay] 只是给界面用，播放链路仍然交给 Media3 嗅探。
 */
val AudioFormats: Formats = Formats

/** 以 object 承载格式表，是为了让 [AudioFormats.all] 这种扩展属性写法有实际接收者。 */
object Formats {
    val all: List<AudioFormat> = listOf(
    AudioFormat("mp3", "audio/mpeg", "MP3", bySystem = true, byExtension = false),
    AudioFormat("aac", "audio/mp4a-latm", "AAC", bySystem = true, byExtension = false),
    AudioFormat("m4a", "audio/mp4", "M4A/AAC", bySystem = true, byExtension = false),
    AudioFormat("flac", "audio/flac", "FLAC", bySystem = true, byExtension = false),
    AudioFormat("ogg", "audio/ogg", "OGG/Vorbis", bySystem = true, byExtension = false),
    AudioFormat("opus", "audio/opus", "Opus", bySystem = true, byExtension = false),
    AudioFormat("wav", "audio/wav", "WAV", bySystem = true, byExtension = false),
    // WMA 从 Android 起就没有系统解码器，只能靠 ffmpeg
    AudioFormat("wma", "audio/x-ms-wma", "WMA", bySystem = false, byExtension = true),
    // APE 同理：系统拆分器都不认这个容器
    AudioFormat("ape", "audio/x-ape", "APE", bySystem = false, byExtension = true),
    // ALAC 装在 m4a/mp4 里，容器认得出，PCM 编码要 ffmpeg
    AudioFormat("alac", "audio/mp4", "ALAC", bySystem = false, byExtension = true),
    AudioFormat("mka", "audio/x-matroska", "MKA", bySystem = false, byExtension = true),
    // DSD 两头都缺：Android 没有 DSF/DFF 拆分器，ffmpeg 模块也没编进 dsd 解码
    AudioFormat("dsf", "audio/x-dsf", "DSD(DSF)", bySystem = false, byExtension = false),
    AudioFormat("dff", "audio/x-dff", "DSD(DFF)", bySystem = false, byExtension = false),
    AudioFormat("m3u8", "application/x-mpegURL", "HLS", bySystem = true, byExtension = false),
    AudioFormat("mpd", "application/dash+xml", "DASH", bySystem = true, byExtension = false),
    )
}

/** 以列表而非 when 表达式暴露，方便界面直接渲染完整的「能播/不能播」表。 */
fun List<AudioFormat>.byExtension(ext: String): AudioFormat? {
    val key = ext.replace(".", "").trim().lowercase()
    if (key.isEmpty()) return null
    return firstOrNull { it.ext == key }
}

/**
 * 音频之外的 m4a 是特例：有的源给的地址是 .mp4，内容其实是音频轨道，
 * 直接归到 m4a 上，界面显示才不至于空白。
 */
private fun normalizeExt(ext: String): String {
    val key = ext.replace(".", "").trim().lowercase()
    return when (key) {
        "mp4" -> "m4a"
        "oga" -> "ogg"
        "m4b" -> "m4a"
        else -> key
    }
}

/** 从 URL 或本地路径里取扩展名。查询串与分号参数都要剥掉，CDN 地址经常带 ?vkey=...。 */
fun extOfUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    val path = url.substringBefore('?').substringBefore('#').substringBefore(';')
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return normalizeExt(name.substring(dot + 1))
}

fun formatOfUrl(url: String?): AudioFormat? = AudioFormats.all.byExtension(extOfUrl(url))

/**
 * 界面侧判定「这个扩展名现在能不能真的放出来」。
 *
 * [ffmpegLoaded] 由 [PlaybackEngine.isFfmpegRendererLoaded] 给出，是真实探测结果而不是常量，
 * 这样界面上的徽标与应用的实际能力永远一致，不会出现「显示可播、点了报错」。
 */
fun canPlay(ext: String, ffmpegLoaded: Boolean = PlaybackEngine.isFfmpegRendererLoaded): Boolean {
    val f = AudioFormats.all.byExtension(ext) ?: return true // 不在清单里就交给 Media3 嗅探，不提前判死
    if (f.bySystem) return true
    if (f.byExtension && ffmpegLoaded) return true
    return false
}

/**
 * 按 URL 判定应该用哪种 MediaSource。
 *
 * 播放源返回的 [Protocol] 经常一律写成 HTTP，而地址本身是 .m3u8/.mpd 的分片清单：
 * 拿 ProgressiveMediaSource 去解 m3u8 只会得到 400ms 后的一行 player error。
 * 所以判定要看两层：协议字段优先，其次才看后缀。
 * WebDAV 的认证已由中间件准备，本地文件由 DefaultDataSource 读取，均可选择清单源。
 * SMB 代理只提供单个文件的 /stream，保留原协议，不推断分片清单。
 */
fun protocolFor(url: String, declared: Protocol = Protocol.HTTP): Protocol {
    if (declared == Protocol.HLS || declared == Protocol.DASH) return declared
    if (declared == Protocol.SMB) return declared
    val e = extOfUrl(url)
    return when (e) {
        "m3u8" -> Protocol.HLS
        // 保留 HTTP 的既有识别；本地/WebDAV 的普通 M3U 也可能只是歌曲列表。
        "m3u" -> if (declared == Protocol.HTTP) Protocol.HLS else declared
        "mpd" -> Protocol.DASH
        else -> declared
    }
}

/** 徽标的展示文本：[Quality] 给出「标准/高音质/无损/Hi-Res」，格式给「MP3/FLAC」。 */
fun formatBadge(url: String?, quality: Quality): String {
    val f = formatOfUrl(url)
    return if (f != null) "${f.label} · ${quality.label}" else quality.label}
