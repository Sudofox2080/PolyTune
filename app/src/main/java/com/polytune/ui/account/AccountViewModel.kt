package com.polytune.ui.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.LoginKind
import com.polytune.source.api.LoginMethod
import com.polytune.source.api.LoginResult
import com.polytune.source.api.MusicSource
import com.polytune.source.api.Platform
import com.polytune.source.qq.CaptchaChallenge
import com.polytune.source.qq.QqSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 账号页状态机。每个源一份独立的 [SourceState]，UI 只读不写，
 * 所有网络动作都在这里跑，失败信息沿用源的原始报错文案，方便定位是风控还是密码错。
 */
class AccountViewModel(private val agg: Aggregator) : ViewModel() {

    data class SourceState(
        val loggedIn: Boolean = false,
        val nickname: String = "",
        val avatar: String = "",
        val busy: Boolean = false,
        val message: String = "",
        val error: Boolean = false,
        val qrTicket: String = "",
        val qrImageUrl: String = "",
    )

    data class UiState(val perPlatform: Map<Platform, SourceState> = Platform.entries.associateWith { SourceState() })

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** QQ 人机验证页地址，非空时账号页弹 WebView。 */
    private val _securityUrl = MutableStateFlow<Pair<Platform, String>?>(null)
    val securityUrlState: StateFlow<Pair<Platform, String>?> = _securityUrl.asStateFlow()

    /** 上次发码的手机号，验证通过后要拿它重发。 */
    private var lastPhone: String = ""

    init {
        agg.sources.forEach { (p, src) ->
            val c = src.credential.value
            _state.update {
                it.copy(
                    perPlatform = it.perPlatform + (p to SourceState(
                        loggedIn = c != null && !c.expired,
                        nickname = c?.nickname.orEmpty(),
                        avatar = c?.avatar.orEmpty(),
                    )),
                )
            }
        }
    }

    private fun patch(p: Platform, block: (SourceState) -> SourceState) {
        _state.update { s -> s.copy(perPlatform = s.perPlatform + (p to block(s.perPlatform[p] ?: SourceState()))) }
    }

    private fun run(p: Platform, block: suspend (MusicSource) -> LoginResult) {
        val src = agg.source(p) ?: return
        patch(p) { it.copy(busy = true, message = "", error = false) }
        viewModelScope.launch {
            val r = runCatching { block(src) }.getOrElse { LoginResult.Fail(it.message ?: "请求异常") }
            when (r) {
                is LoginResult.Success -> {
                    patch(p) {
                        it.copy(
                            loggedIn = true,
                            busy = false,
                            nickname = r.credential.nickname,
                            avatar = r.credential.avatar,
                            message = "登录成功",
                            error = false,
                            qrTicket = "",
                            qrImageUrl = "",
                        )
                    }
                    agg.refreshLoginState()
                }

                is LoginResult.Pending -> patch(p) {
                    it.copy(
                        busy = false,
                        message = r.message.ifBlank { "等待扫码确认" },
                        error = false,
                        qrTicket = r.ticket,
                        qrImageUrl = r.qrImageUrl,
                    )
                }

                is LoginResult.Fail -> patch(p) { it.copy(busy = false, message = r.message, error = true) }
            }
        }
    }

    fun loginCookie(p: Platform, cookie: String) =
        run(p) { it.login(LoginMethod.CookieImport(cookie.trim())) }

    fun loginToken(p: Platform, token: String, uid: String) =
        run(p) { it.login(LoginMethod.TokenInject(token.trim(), uid.trim())) }

    /** QQ 的图形验证码挑战：图字节、cap_cd 与下一步要用的密码摘要。 */
    private val captcha = MutableStateFlow<Map<Platform, CaptchaChallenge>>(emptyMap())

    fun captchaOf(p: Platform): CaptchaChallenge? = captcha.value[p]

    /**
     * 密码登录。对声明了图形验证码能力的源（目前只有 QQ），
     * 先探一次图：探到就直接把挑战挂到状态里等用户填，探不到再走普通登录。
     * 这样用户点一次「登录」即可，不需要先点「发送验证码」再看有没有图。
     */
    fun loginPassword(p: Platform, account: String, password: String) {
        val src = agg.source(p)
        if (src is QqSource) {
            patch(p) { it.copy(busy = true, message = "正在获取图形验证码…", error = false) }
            viewModelScope.launch {
                val ch = runCatching { src.fetchCaptcha(account.trim(), password) }.getOrNull()
                if (ch != null) {
                    captcha.update { it + (p to ch) }
                    patch(p) { it.copy(busy = false, message = "请输入图中的 4 位验证码", error = false) }
                } else {
                    val r = runCatching { src.login(LoginMethod.PhonePassword(account.trim(), password)) }
                        .getOrElse { LoginResult.Fail(it.message ?: "请求异常") }
                    finish(p, r)
                }
            }
            return
        }
        run(p) { it.login(LoginMethod.PhonePassword(account.trim(), password)) }
    }

    /** 用户填完图形验证码后的第二次握手。 */
    fun loginPasswordWithCaptcha(p: Platform, code: String) {
        val src = agg.source(p) as? QqSource ?: return
        val ch = captcha.value[p] ?: return
        patch(p) { it.copy(busy = true, message = "", error = false) }
        viewModelScope.launch {
            val r = runCatching { src.loginWithCaptcha(ch.phone, ch.password, code) }
                .getOrElse { LoginResult.Fail(it.message ?: "请求异常") }
            if (r is LoginResult.Success) captcha.update { it - p }
            finish(p, r)
        }
    }

    /** 点图换一张：重新探一次挑战。 */
    fun refreshCaptcha(p: Platform, password: String) {
        val src = agg.source(p) as? QqSource ?: return
        val ch = captcha.value[p] ?: return
        viewModelScope.launch {
            val next = runCatching { src.fetchCaptcha(ch.phone, password) }.getOrNull()
            if (next != null) captcha.update { it + (p to next) }
            patch(p) { it.copy(message = if (next != null) "已换一张" else "换图失败，稍后再试") }
        }
    }

    /** loginPassword 内部用：把结果写进状态，逻辑与 run() 的分支保持一致。 */
    private fun finish(p: Platform, r: LoginResult) {
        when (r) {
            is LoginResult.Success -> {
                patch(p) {
                    it.copy(
                        loggedIn = true,
                        busy = false,
                        nickname = r.credential.nickname,
                        avatar = r.credential.avatar,
                        message = "登录成功",
                        error = false,
                    )
                }
                agg.refreshLoginState()
            }

            is LoginResult.Pending -> patch(p) {
                it.copy(busy = false, message = r.message.ifBlank { "等待确认" }, error = false)
            }

            is LoginResult.Fail -> {
                // captcha: 开头说明服务端又换了图，把新图地址直接刷进状态
                val raw = r.message
                if (raw.startsWith("captcha:")) {
                    val url = raw.removePrefix("captcha:")
                    val cur = captcha.value[p]
                    patch(p) { it.copy(busy = false, message = "验证码不对，请按新图重填", error = true) }
                    viewModelScope.launch {
                        val next = runCatching { src2(p)?.fetchCaptcha(cur?.phone ?: "", "") }.getOrNull()
                        if (next != null) captcha.update { it + (p to next) }
                    }
                } else {
                    patch(p) { it.copy(busy = false, message = raw, error = true) }
                }
            }
        }
    }

    private fun src2(p: Platform): QqSource? = agg.source(p) as? QqSource

    fun loginSms(p: Platform, phone: String, code: String) =
        run(p) { it.login(LoginMethod.SmsCode(phone.trim(), code.trim())) }

    /** 邮箱 + 密码：网易云 /api/w/login，其它源在各自的 login() 里直接返回不支持。 */
    fun loginEmail(p: Platform, email: String, password: String) =
        run(p) { it.login(LoginMethod.EmailPassword(email.trim(), password)) }

    /** 源把自己声明的能力报上来，账号页据此决定摆哪些入口。 */
    fun methodsOf(p: Platform): Set<LoginKind> = agg.source(p)?.supportedMethods() ?: emptySet()

    /**
     * 发短信和登录分开跑：发短信返回的不是凭证，只要把提示语显示出来。
     * 用 run() 会让 Pending 分支把二维码 ticket 写进状态，这里单独走一段。
     */
    fun sendSms(p: Platform, phone: String) {
        val src = agg.source(p) ?: return
        lastPhone = phone.trim()
        patch(p) { it.copy(busy = true, message = "", error = false) }
        viewModelScope.launch {
            val r = runCatching { src.sendSmsCode(phone.trim()) }
                .getOrElse { LoginResult.Fail(it.message ?: "发送失败") }
            when (r) {
                is LoginResult.Fail -> patch(p) { it.copy(busy = false, message = r.message, error = true) }
                is LoginResult.Pending -> {
                    // QQ 的 Pending 可能带回一个人机验证页地址（20276 robot defense），
                    // 这种时候把地址交给界面弹 WebView，验证通过后再重新发一次。
                    val isUrl = r.ticket.startsWith("http")
                    if (isUrl) {
                        _securityUrl.value = p to r.ticket
                    }
                    patch(p) {
                        it.copy(
                            busy = false,
                            message = if (isUrl) "请先完成人机验证，通过后会自动重发短信"
                            else r.message.ifBlank { "验证码已发送" },
                            error = false,
                        )
                    }
                }

                is LoginResult.Success -> patch(p) {
                    it.copy(busy = false, message = "验证码已发送", error = false)
                }
            }
        }
    }

    /** 人机验证完成后重发短信：验证通过后服务端不再要求验证，就会真的下发。 */
    fun resendAfterVerify(p: Platform, phone: String) {
        _securityUrl.value = null
        sendSms(p, phone.ifBlank { lastPhone })
    }

    fun securityUrlOf(p: Platform): String? = _securityUrl.value?.takeIf { it.first == p }?.second

    fun clearSecurityUrl() {
        _securityUrl.value = null
    }

    fun startQr(p: Platform) = run(p) { it.qrCreate() }

    fun pollQr(p: Platform) {
        val ticket = _state.value.perPlatform[p]?.qrTicket.orEmpty()
        if (ticket.isBlank()) return startQr(p)
        run(p) { it.qrPoll(ticket) }
    }

    fun logout(p: Platform) {
        viewModelScope.launch {
            agg.source(p)?.logout()
            agg.refreshLoginState()
            patch(p) { SourceState(message = "已退出") }
        }
    }
}
