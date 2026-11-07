package com.polytune.ui.account

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.polytune.source.api.LoginKind
import com.polytune.source.api.Platform
import com.polytune.source.qq.CaptchaChallenge
import com.polytune.ui.common.PlatformBadge

/**
 * 账号页。四个源并列，登录方式由各源自己声明（[LoginKind]），界面只渲染它支持的那几项：
 * 网页登录是共通的兜底入口（密码、短信、滑块都由源自己的网页处理），
 * 网易云另有邮箱密码与扫码，酷狗与 QQ 有手机号验证码与密码。
 */
@Composable
fun AccountScreen(
    vm: AccountViewModel,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(Platform.QQ) }
    var webLogin by remember { mutableStateOf<WebLoginTarget?>(null) }

    Column(
        modifier
            .fillMaxSize()
            // 底部 dock 现在浮在内容之上，账号页是纵向可滚的，最后一张卡片要能滚出 dock 底下，
            // 所以自己留一段底部空间；navigationBars 由外层 dock 已经让开，这里不重复加。
            .padding(bottom = 96.dp),
    ) {
        Text(
            "账号",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        )

        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInHorizontally { it / 8 })
                    .togetherWith(fadeOut(tween(140)) + slideOutHorizontally { -it / 8 })
            },
            label = "acctSwitch",
        ) { p ->
            AccountCard(
                platform = p,
                state = s.perPlatform[p] ?: AccountViewModel.SourceState(),
                supported = vm.methodsOf(p),
                onLoginSms = { phone, code -> vm.loginSms(p, phone, code) },
                onSendSms = { phone -> vm.sendSms(p, phone) },
                onLoginPassword = { a, b -> vm.loginPassword(p, a, b) },
                onLoginPasswordWithCaptcha = { code -> vm.loginPasswordWithCaptcha(p, code) },
                onRefreshCaptcha = { vm.refreshCaptcha(p, vm.captchaOf(p)?.password.orEmpty()) },
                captcha = vm.captchaOf(p),
                onLoginEmail = { mail, pass -> vm.loginEmail(p, mail, pass) },
                onLoginCookie = { cookie -> vm.loginCookie(p, cookie) },
                onQr = { vm.startQr(p) },
                onQrPoll = { vm.pollQr(p) },
                onWebLogin = { WebLoginTarget.ofOrNull(p)?.let { webLogin = it } },
                onLogout = { vm.logout(p) },
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Platform.entries.forEach { p ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (p == selected) Color(p.brand).copy(alpha = 0.2f)
                    else MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    onClick = { selected = p },
                ) {
                    Column(
                        Modifier.padding(vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        PlatformBadge(p)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (s.perPlatform[p]?.loggedIn == true) "已登录" else "未登录",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (s.perPlatform[p]?.loggedIn == true) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            "登录后各源按你自己账号的权限取直链与歌词，不做任何越权；未登录时只走公开搜索与试听档位。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp),
        )
    }

    webLogin?.let { target ->
        WebLoginSheet(
            target = target,
            busy = s.perPlatform[target.platform]?.busy == true,
            onClose = { webLogin = null },
            onHarvest = { cookie -> vm.loginCookie(target.platform, cookie) },
        )
    }

    // QQ 的人机验证：发码接口回 20276 时会带回一个 c.y.qq.com/r/fy6U 的验证页，
    // 这里用 WebView 打开它，用户滑完验证后重新发一次短信。
    val security by vm.securityUrlState.collectAsStateWithLifecycle()
    security?.let { (plat, url) ->
        QqCaptchaSheet(
            verifyUrl = url,
            onVerified = { vm.resendAfterVerify(plat, "") },
            onClose = { vm.clearSecurityUrl() },
        )
    }
}

@Composable
private fun AccountCard(
    platform: Platform,
    state: AccountViewModel.SourceState,
    supported: Set<LoginKind>,
    onLoginSms: (String, String) -> Unit,
    onSendSms: (String) -> Unit,
    onLoginPassword: (String, String) -> Unit,
    onLoginPasswordWithCaptcha: (String) -> Unit,
    onRefreshCaptcha: () -> Unit,
    captcha: CaptchaChallenge?,
    onLoginEmail: (String, String) -> Unit,
    onLoginCookie: (String) -> Unit,
    onQr: () -> Unit,
    onQrPoll: () -> Unit,
    onWebLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    var mode by remember(platform) { mutableStateOf(0) }
    var account by remember(platform) { mutableStateOf("") }
    var pwd by remember(platform) { mutableStateOf("") }
    var smsCode by remember(platform) { mutableStateOf("") }
    var email by remember(platform) { mutableStateOf("") }
    var emailPwd by remember(platform) { mutableStateOf("") }
    var showPaste by remember(platform) { mutableStateOf(false) }
    var pasted by remember(platform) { mutableStateOf("") }
    var capCode by remember(platform) { mutableStateOf("") }

    // 只有源真的支持才出这些 tab，避免点了才报「不支持」
    val modes = buildList {
        if (LoginKind.PhoneSms in supported) add(LoginKind.PhoneSms)
        if (LoginKind.PhonePassword in supported) add(LoginKind.PhonePassword)
        if (LoginKind.EmailPassword in supported) add(LoginKind.EmailPassword)
    }
    val safeMode = mode.coerceIn(0, (modes.size - 1).coerceAtLeast(0))

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(Color(platform.brand).copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            platform.displayName.take(1),
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(platform.brand),
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(platform.displayName, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.loggedIn) state.nickname.ifBlank { "已登录" } else "未登录",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.loggedIn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (state.loggedIn) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (state.loggedIn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = onLogout, enabled = !state.busy) {
                            Icon(Icons.Rounded.Logout, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("退出登录")
                        }
                    }
                } else {
                    if (LoginKind.Web in supported) {
                        Button(
                            onClick = onWebLogin,
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Rounded.OpenInBrowser, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("打开${platform.displayName}网页登录")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            webHint(platform),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(14.dp))
                    }

                    if (modes.isNotEmpty()) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            modes.forEachIndexed { i, kind ->
                                SegmentedButton(
                                    selected = safeMode == i,
                                    onClick = { mode = i },
                                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                                ) { Text(shortLabel(kind)) }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                    }

                    when (modes.getOrNull(safeMode)) {
                        // 手机号 + 短信验证码
                        LoginKind.PhoneSms -> {
                            OutlinedTextField(
                                value = account,
                                onValueChange = { account = it },
                                label = { Text("手机号") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = smsCode,
                                    onValueChange = { smsCode = it },
                                    label = { Text("短信验证码") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.size(8.dp))
                                FilledTonalButton(
                                    onClick = { onSendSms(account) },
                                    enabled = !state.busy && account.length >= 6,
                                ) { Text("发送验证码") }
                            }
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onLoginSms(account, smsCode) },
                                enabled = !state.busy && account.length >= 6 && smsCode.length >= 4,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonLabel(state.busy, "登录") }
                        }

                        // 手机号 + 密码
                        LoginKind.PhonePassword -> {
                            OutlinedTextField(
                                value = account,
                                onValueChange = { account = it },
                                label = { Text("手机号 / 账号") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pwd,
                                onValueChange = { pwd = it },
                                label = { Text("密码") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onLoginPassword(account, pwd) },
                                enabled = !state.busy && account.isNotBlank() && pwd.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonLabel(state.busy, if (captcha != null) "重新获取验证图" else "登录") }

                            // ptlogin2 的第二段握手：图在这里，4 位字符填完才发票据
                            if (captcha != null) {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color.White,
                                        modifier = Modifier
                                            .width(150.dp)
                                            .height(56.dp)
                                            .clickable { onRefreshCaptcha() },
                                    ) {
                                        AsyncImage(
                                            model = captcha.imageBytes,
                                            contentDescription = "图形验证码，点击换一张",
                                            contentScale = ContentScale.Fit,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(4.dp),
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    OutlinedTextField(
                                        value = capCode,
                                        onValueChange = { capCode = it.filter(Char::isLetterOrDigit).take(4) },
                                        label = { Text("验证码") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                Spacer(Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        onLoginPasswordWithCaptcha(capCode)
                                        capCode = ""
                                    },
                                    enabled = !state.busy && capCode.length >= 4,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { ButtonLabel(state.busy, "验证并登录") }
                            }
                        }

                        // 邮箱 + 密码（网易云 /api/w/login）
                        LoginKind.EmailPassword -> {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text("邮箱") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = emailPwd,
                                onValueChange = { emailPwd = it },
                                label = { Text("密码") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onLoginEmail(email, emailPwd) },
                                enabled = !state.busy && email.contains("@") && emailPwd.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonLabel(state.busy, "登录") }
                        }

                        else -> Unit
                    }

                    if (LoginKind.QR in supported) {
                        Spacer(Modifier.height(10.dp))
                        FilledTonalButton(
                            onClick = { if (state.qrTicket.isBlank()) onQr() else onQrPoll() },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (state.qrTicket.isBlank()) "扫码登录" else "我已扫码，检查结果") }
                        if (state.qrImageUrl.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = Color.White,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp),
                            ) {
                                AsyncImage(
                                    model = state.qrImageUrl,
                                    contentDescription = "登录二维码",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    // 网页登录抓取失败时的兜底：手工粘贴浏览器里的 cookie
                    if (LoginKind.Cookie in supported) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = { showPaste = !showPaste },
                                enabled = !state.busy,
                            ) { Text(if (showPaste) "收起手动粘贴" else "手动粘贴 Cookie") }
                        }
                        if (showPaste) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pasted,
                                onValueChange = { pasted = it },
                                label = { Text("Cookie") },
                                supportingText = { Text(hintCookie(platform)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = { onLoginCookie(pasted) },
                                enabled = !state.busy && pasted.length > 8,
                                modifier = Modifier.fillMaxWidth(),
                            ) { ButtonLabel(state.busy, "用这条 Cookie 登录") }
                        }
                    }
                }

                if (state.message.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.busy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("正在请求…", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ButtonLabel(busy: Boolean, text: String) {
    if (busy) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.onPrimary,
        )
        Spacer(Modifier.size(8.dp))
    }
    Text(text)
}

private fun shortLabel(kind: LoginKind): String = when (kind) {
    LoginKind.PhoneSms -> "验证码"
    LoginKind.PhonePassword -> "密码"
    LoginKind.EmailPassword -> "邮箱"
    else -> kind.label
}

private fun webHint(p: Platform): String = when (p) {
    Platform.QQ -> "在网页里用 QQ 或微信登录，页面右上角出现头像后回来点「已登录，读取凭证」。"
    Platform.NETEASE -> "在网页里用手机号或邮箱登录，出现你的昵称后回来点「已登录，读取凭证」。"
    Platform.KUGOU -> "在网页右上角登录酷狗账号，回来点「已登录，读取凭证」。"
}

private fun hintCookie(p: Platform): String = when (p) {
    Platform.QQ -> "含 qqmusic_key 与 uin，例如 qqmusic_key=xxx; uin=1234"
    Platform.NETEASE -> "含 MUSIC_U 与 __csrf，例如 MUSIC_U=xxx; __csrf=yyy"
    Platform.KUGOU -> "含 KuGoo 与 kg_uid，例如 KuGoo=xxx; kg_uid=1234"
}