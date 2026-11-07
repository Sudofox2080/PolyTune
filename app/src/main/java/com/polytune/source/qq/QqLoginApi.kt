package com.polytune.source.qq

import com.polytune.core.util.Jp
import com.polytune.source.api.Credential
import com.polytune.source.api.LoginResult
import com.polytune.source.api.Platform
import java.util.UUID

/**
 * QQ 音乐官方手机验证码登录的两步接口。
 *
 * 第一步 `music.login.LoginServer/SendPhoneAuthCode`，第二步 `music.login.LoginServer/Login`
 * （`loginMode=1`）。两步都必须把平台标成 Android（comm 里 `ct=11 cv=20090008 v=20090008
 * tmeAppID=qqmusic`），网页端那套 ct=24 会被判成其它业务场景直接拒绝。
 *
 * **人机验证**：2026-11 实测，发给任意手机号第一次都会拿到
 * `req_0.code=20276`、`data.errMsg="robot defense"`，
 * 同时 data.securityURL 是 `https://c.y.qq.com/r/fy6U?...` 这样的完整验证页。
 * 这一页加载的是 QQ 音乐自家的 SafetyCaptcha 组件，用户滑完/点完后会回调
 * `qqmusicClient.open('ui','setSecureVerificationStatus',{status:0})`。
 * 所以这个类只负责把请求发出去、把 securityURL 原样交给界面，
 * 由 [com.polytune.ui.account.QqCaptchaSheet] 里的 WebView 完成验证并回调，
 * 验证通过后重新调一次 sendAuthCode 才会真正收到短信。
 *
 * 所有这些都发生在用户自己的账号上，认证过程沿用的是官方的人机验证，
 * 不存在绕过验证码的问题。
 */
object QqLoginApi {

    private const val MUSICU = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private const val UA = "QQMusic 20090008(android 14)"

    /** 设备标识，登录流程里要保持一致；进程级单例即可。 */
    val deviceId: String by lazy {
        UUID.randomUUID().toString().uppercase()
    }

    data class AuthCodeSent(
        /** true 表示发码接口返回了 20276，需要先过人机验证。 */
        val needSecurity: Boolean,
        /** 需要人机验证时的验证页地址。 */
        val securityUrl: String? = null,
        /** 失败说明，成功时为 null。 */
        val error: String? = null,
        /** 原始 req_0.code，便于日志对照。 */
        val rawCode: Int = 0,
    )

    data class LoginSession(
        val musicId: String,
        val musicKey: String,
        val nick: String? = null,
        val avatar: String? = null,
        /** 服务端回的其他字段，原始 JSON 留作诊断。 */
        val raw: String = "",
    )

    /** 发送短信验证码。遇到 20276 时把 securityURL 带回去交界面处理。 */
    suspend fun sendAuthCode(
        post: suspend (String, String) -> String,
        phone: String,
        countryCode: String = "86",
    ): AuthCodeSent {
        val payload = """
            {"comm":${comm()},"req_0":{"module":"music.login.LoginServer",
            "method":"SendPhoneAuthCode","param":{"tmeAppid":"qqmusic",
            "areaCode":"$countryCode","phoneNo":"$phone"}}}
        """.trimIndent().replace("\n", "")
        val body = post(MUSICU, payload)
        val root = Jp.root(body) ?: return AuthCodeSent(needSecurity = false, error = "响应无法解析")
        val code = Jp.int(root, "req_0.code") ?: Jp.int(root, "code") ?: 0
        return when (code) {
            0 -> AuthCodeSent(needSecurity = false, rawCode = 0)
            20276 -> AuthCodeSent(
                needSecurity = true,
                securityUrl = Jp.str(root, "req_0.data.securityURL"),
                rawCode = code,
            )
            else -> AuthCodeSent(
                needSecurity = false,
                error = Jp.str(root, "req_0.data.errMsg")
                    ?: if (code == 10004) "操作过于频繁，稍后再试" else "发码失败（$code）",
                rawCode = code,
            )
        }
    }

    /** 用验证码换票据。成功后直接组装成 Credential，cookie 里塞 qm_keyst 供后续接口用。 */
    suspend fun login(
        post: suspend (String, String) -> String,
        get: suspend (String) -> String,
        phone: String,
        code: String,
    ): Pair<LoginResult, String> {
        val payload = """
            {"comm":${comm(tmeLoginType = 0)},"req_0":{"module":"music.login.LoginServer",
            "method":"Login","param":{"code":"$code","loginMode":1,
            "phoneNo":"$phone","tmeAppid":"qqmusic"}}}
        """.trimIndent().replace("\n", "")
        val body = post(MUSICU, payload)
        val root = Jp.root(body) ?: return LoginResult.Fail("登录响应无法解析") to body
        val codeVal = Jp.int(root, "req_0.code") ?: Jp.int(root, "code") ?: -1
        val musicKey = Jp.str(root, "req_0.data.musickey").orEmpty()
        if (codeVal != 0 || musicKey.isBlank()) {
            val msg = when (codeVal) {
                20271 -> "验证码不正确"
                20274 -> "这个手机号还没绑定 QQ 音乐账号，请先在官方 App 里绑定"
                20276 -> "还需要先完成人机验证"
                20277, 20278, 20450 -> "账号状态受限（$codeVal）"
                20279 -> "登录设备数量超限"
                104604 -> "操作过于频繁，稍后再试"
                else -> "登录失败（$codeVal）"
            }
            return LoginResult.Fail(msg) to body
        }
        val musicId = Jp.str(root, "req_0.data.musicid")
            ?: Jp.str(root, "req_0.data.str_musicid").orEmpty()
        val nick = Jp.str(root, "req_0.data.nick")
        val head = Jp.str(root, "req_0.data.logo")
        // refresh_key / unionid 是后续换 key 与播放接口要用的会话凭据，一起存进 extra。
        val refreshKey = Jp.str(root, "req_0.data.refresh_key").orEmpty()
        val unionId = Jp.str(root, "req_0.data.unionid").orEmpty()
        val keyHires = Jp.str(root, "req_0.data.musickey_hires").orEmpty()
        val cred = Credential(
            platform = Platform.QQ,
            uid = musicId,
            token = musicKey,
            nickname = nick ?: "QQ音乐用户",
            avatar = head.orEmpty(),
            extra = mapOf(
                "cookie" to "qqmusic_key=$musicKey; qm_keyst=$musicKey; uin=$musicId; qqmusic_uin=$musicId",
                "loginType" to (Jp.str(root, "req_0.data.loginType") ?: "0"),
                "refreshKey" to refreshKey,
                "unionId" to unionId,
                "musickeyHires" to keyHires,
            ),
        )
        // 让后续 u.y.qq.com 的请求也用到这份 cookie
        runCatching {
            get("https://y.qq.com/portal/profile.html")
        }
        val session = LoginSession(
            musicId = musicId,
            musicKey = musicKey,
            nick = nick,
            avatar = head,
            raw = body.take(600),
        )
        return LoginResult.Success(cred) to session.raw
    }

    /** 为查询用户信息准备一份登录态 Cookie 串，供 SourceHttp 直接写入 cookie 罐。 */
    fun cookiesFor(musicId: String, musicKey: String): List<Pair<String, String>> = listOf(
        "qqmusic_key" to musicKey,
        "qm_keyst" to musicKey,
        "qqmusic_uin" to musicId,
        "uin" to musicId,
    )

    private fun comm(tmeLoginType: Int? = null): String {
        val sb = StringBuilder()
        sb.append("{\"ct\":\"11\",\"cv\":\"20090008\",\"v\":\"20090008\",")
        sb.append("\"chid\":\"10003505\",\"tmeAppID\":\"qqmusic\",")
        sb.append("\"tmeLoginMethod\":\"3\",")
        if (tmeLoginType != null) sb.append("\"tmeLoginType\":\"$tmeLoginType\",")
        sb.append("\"traceid\":\"10002_${deviceId}_${System.currentTimeMillis() / 1000}\",")
        sb.append("\"OpenUDID\":\"$deviceId\",\"udid\":\"$deviceId\",")
        sb.append("\"os_ver\":\"14\",\"phonetype\":\"PolyTune\"}")
        return sb.toString()
    }

    /** 供界面日志用。 */
    fun describe(sent: AuthCodeSent): String =
        "needSecurity=${sent.needSecurity} code=${sent.rawCode} " +
            "security=${sent.securityUrl?.take(80) ?: "无"} err=${sent.error ?: "无"}"
}
