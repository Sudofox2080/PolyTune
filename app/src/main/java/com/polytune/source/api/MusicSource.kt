package com.polytune.source.api

import kotlinx.coroutines.flow.StateFlow

/**
 * 所有音乐源实现的统一能力面。聚合器只面向这个接口编程，新增一个平台只需实现它并注册到 AppGraph。
 */
interface MusicSource {

    val platform: Platform

    /** 当前登录态，未登录为 null。用于界面展示与请求签名。 */
    val credential: StateFlow<Credential?>

    /** 是否已经具备可用凭证（cookie/token 未过期）。 */
    fun isLoggedIn(): Boolean = credential.value?.expired == false

    suspend fun login(method: LoginMethod): LoginResult
    suspend fun logout()

    /** 关键字搜索。page 从 1 开始。 */
    suspend fun search(keyword: String, page: Int = 1, pageSize: Int = 20): SearchPage

    suspend fun songDetail(id: String): Song?

    /** 歌词；没有则返回空 Lyric，不要抛异常。 */
    suspend fun lyric(id: String): Lyric

    /**
     * 取播放直链。quality 只是期望，源内部按账号权限降级；拿不到返回 null 由聚合器换源。
     */
    suspend fun playUrl(id: String, quality: Quality): PlayEndpoint?

    /**
     * 带整首歌信息的播放解析。默认丢掉 Song 只传 id；酷狗这类按音质给不同资源 id 的源
     * 需要重写它才能命中 320k / 无损 / Hi-Res。
     */
    suspend fun playUrl(song: Song, quality: Quality): PlayEndpoint? = playUrl(song.id, quality)

    suspend fun recommendBanners(): List<BannerItem> = emptyList()
    suspend fun recommendPlaylists(): List<PlaylistBrief> = emptyList()

    /** 当前登录账号的「我的歌单」（自建 + 收藏）。未登录或该源没接时返回空。 */
    suspend fun myPlaylists(): List<PlaylistBrief> = emptyList()

    /**
     * 歌单曲目。默认空实现：没接这个接口的源在界面上会被标成「暂不支持」，
     * 而不是拿推荐歌单的名字去搜一堆不相干的结果充数。
     */
    suspend fun playlistDetail(id: String): List<Song> = emptyList()

    /** 扫码登录第一步：取二维码。返回 Pending 时 ticket 用于轮询。 */
    suspend fun qrCreate(): LoginResult = LoginResult.Fail("该源不支持扫码")
    /** 扫码登录轮询。 */
    suspend fun qrPoll(ticket: String): LoginResult = LoginResult.Fail("该源不支持扫码")

    /**
     * 手机号验证码登录的第一步：向 phone 发短信。
     * 返回 Success 表示短信已发出（不携带真实凭证），Fail 是接口拒绝。
     */
    suspend fun sendSmsCode(phone: String): LoginResult = LoginResult.Fail("该源不支持短信登录")

    /**
     * 用已存的 refreshToken 续期。网易云是 /api/login/token/refresh，华为是 OAuth refresh_token，
     * QQ 与酷狗没有独立刷新接口（cookie 到期需重新登录），默认返回 null 表示不处理。
     */
    suspend fun refreshLogin(): LoginResult? = null

    /** 该源支持的登录方式，界面据此渲染按钮，避免给不支持的源摆一个点了报错的入口。 */
    fun supportedMethods(): Set<LoginKind> = setOf(
        LoginKind.PhoneSms, LoginKind.PhonePassword, LoginKind.QR, LoginKind.Cookie,
        LoginKind.Web, LoginKind.Token,
    )
}

/**
 * 登录方式分类，用于界面开关，与 LoginMethod 的载荷分开。
 *
 * [Web] 指用内置 WebView 打开该源官方登录页：密码、短信、滑块、第三方授权全部由源自己在网页里完成，
 * 应用只从 CookieManager 取回会话 cookie，再走 CookieImport 的解析路径。
 * 这是华为这类只认授权页的源唯一可行的入口，也是其余三源最不容易被风控拦的方式。
 */
enum class LoginKind(val label: String) {
    PhoneSms("手机验证码"),
    PhonePassword("手机密码"),
    EmailPassword("邮箱密码"),
    QR("扫码"),
    Web("网页登录"),
    Cookie("Cookie 粘贴"),
    Token("Token 注入"),
}

/** 各源共用的空实现基类，减少样板。 */
abstract class BaseMusicSource(
    final override val platform: Platform,
    protected val net: com.polytune.core.net.SourceHttp,
) : MusicSource {

    protected var cache: Credential? = null
        private set

    private val _credential = kotlinx.coroutines.flow.MutableStateFlow<Credential?>(null)
    override val credential: StateFlow<Credential?> = _credential

    fun restore(c: Credential?) {
        cache = c
        _credential.value = c
        if (c != null) net.attach(c)
    }

    protected fun setCredential(c: Credential?) {
        cache = c
        _credential.value = c
        if (c != null) {
            net.attach(c)
            com.polytune.core.crypto.CredentialVault.get().put(c)
        } else {
            net.detach()
            com.polytune.core.crypto.CredentialVault.get().remove(platform)
        }
    }

    override suspend fun logout() = setCredential(null)
}
