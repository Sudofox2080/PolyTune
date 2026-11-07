package com.polytune.source.api

import kotlinx.serialization.Serializable

/** 支持的四个音乐源。@property displayName 界面展示名，@property brand 主色，用于角标与占位图。 */
enum class Platform(val displayName: String, val brand: Long) {
    QQ("QQ音乐", 0xFF31C27C),
    NETEASE("网易云", 0xFFC20C0C),
    KUGOU("酷狗", 0xFF2CA2F9);

    // 华为音乐渠道已移除。原因：华为音乐网页版登录成功后不向 cookie 写任何票据，
    // 只能走 OAuth 授权码 + PKCE 换 musicToken；音乐侧 loginex 一旦拿不到 fresh access_token
    // 就返回 990006 User token expired，而 refresh_token 的刷新路径强制要求 client_secret
    // （error 1101 / sub_error 20171），App 侧不可能持有。歌单接口 music-list-service
    // 的所有路径在网关侧一律 404，无法确认服务名与版本。凭证七天必失效且无法续期，
    // 保留这个渠道只会让用户在登录成功后仍然到处遇到鉴权失败，因此整体下线。

    companion object {
        fun of(name: String?): Platform? = entries.firstOrNull { it.name == name }
    }
}

/** 音质档位。bitrate 仅用于排序与展示，实际可用性由账号权限决定。 */
enum class Quality(val label: String, val rank: Int, val suffix: String) {
    STANDARD("标准", 0, "128k"),
    HIGH("高音质", 1, "320k"),
    LOSSLESS("无损", 2, "flac"),
    HIRES("Hi-Res", 3, "hires");

    companion object {
        fun of(name: String?): Quality =
            entries.firstOrNull { it.name == name || it.label == name } ?: STANDARD
    }
}

/** 取不高于 want 的最高档，用于在某个源实际支持的档位里挑一个。 */
fun List<Quality>.bestAtMost(want: Quality): Quality? =
    filter { it.rank <= want.rank }.maxByOrNull { it.rank } ?: minByOrNull { it.rank }

@Serializable
data class Song(
    val id: String,
    val platform: Platform,
    val title: String,
    val artists: List<String> = emptyList(),
    val album: String = "",
    val albumId: String? = null,
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
    val availableQualities: List<Quality> = emptyList(),
    val vipFlag: Boolean = false,
    val explicit: Boolean = false,
    /** md5(标题|歌手|专辑) 归一化指纹，跨源合并同曲用。 */
    val fingerprint: String = "",
    /**
     * 同一首歌在源内不同音质对应的资源 id（酷狗按音质给不同 hash，QQ/网易为空）。
     * 键用 Quality.name，播放时优先取对应档，缺档回落到 [id]。
     */
    val qualityIds: Map<String, String> = emptyMap(),
) {
    val artistsText: String get() = artists.joinToString("/")
    val durationText: String
        get() {
            val s = durationMs / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
}

@Serializable
data class LineLyric(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null,
)

@Serializable
data class Lyric(
    val lines: List<LineLyric> = emptyList(),
    val instrumental: Boolean = false,
    val source: String = "",
) {
    fun indexAt(positionMs: Long): Int =
        lines.indexOfLast { it.timeMs <= positionMs }.coerceAtLeast(0)
}

@Serializable
data class Credential(
    val platform: Platform,
    val uid: String,
    val token: String,
    val nickname: String = "",
    val avatar: String = "",
    val extra: Map<String, String> = emptyMap(),
    val expiresAt: Long = 0L,
) {
    val expired: Boolean get() = expiresAt in 1..System.currentTimeMillis()
}

@Serializable
data class PlayEndpoint(
    val url: String,
    val protocol: Protocol = Protocol.HTTP,
    val headers: Map<String, String> = emptyMap(),
    val quality: Quality = Quality.STANDARD,
    val expiresAt: Long = 0L,
    val sizeBytes: Long = -1L,
    /** 试听片段（未登录或会员曲）。聚合器第一轮只接受完整曲目，全都拿不到时才用它兜底。 */
    val preview: Boolean = false,
)

enum class Protocol { HTTP, HLS, DASH, WEBDAV, SMB, LOCAL }

@Serializable
data class SearchPage(
    val songs: List<Song> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
)

@Serializable
data class BannerItem(
    val id: String,
    val imageUrl: String,
    val title: String,
    val subtitle: String = "",
    val targetType: String = "",
    val targetId: String = "",
    val platform: Platform,
)

@Serializable
data class PlaylistBrief(
    val id: String,
    val name: String,
    val coverUrl: String = "",
    val trackCount: Int = 0,
    val playCount: Long = 0,
    val platform: Platform,
    /** true 表示这是当前登录账号自己创建/收藏的歌单，界面上单独归到「我的歌单」。 */
    val owned: Boolean = false,
)

sealed interface LoginMethod {
    data class QrCode(val ticket: String = "") : LoginMethod
    data class PhonePassword(val phone: String, val password: String) : LoginMethod

    /**
     * 邮箱 + 密码。网易云走 /api/w/login（type=0），华为走 OAuth 密码模式，
     * QQ 与酷狗没有邮箱登录，收到时直接返回不支持。
     */
    data class EmailPassword(val email: String, val password: String) : LoginMethod
    data class SmsCode(val phone: String, val code: String) : LoginMethod
    data class CookieImport(val cookie: String) : LoginMethod
    data class TokenInject(val token: String, val uid: String) : LoginMethod
}

sealed interface LoginResult {
    data class Success(val credential: Credential) : LoginResult
    data class Pending(val ticket: String, val qrImageUrl: String = "", val message: String = "") : LoginResult
    data class Fail(val message: String) : LoginResult
}

/** 一次播放解析的产物：可能落到别的等价源上。 */
data class ResolvedPlay(
    val song: Song,
    val actualPlatform: Platform,
    val endpoint: PlayEndpoint?,
    val tried: List<String> = emptyList(),
)
