package com.polytune.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.polytune.core.download.DownloadTrace
import kotlinx.coroutines.delay

private fun jsStr(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/**
 * 登录态探针要巡检的域。这是排查网页登录时唯一能看清浏览器里到底落了什么的窗口，
 * 对四个源的域都巡检；华为渠道已下线，它的域只留作历史参考，点击时会一并打印。
 */
private val ProbeHosts = listOf(
    "y.qq.com",
    "music.163.com",
    "kugou.com",
    "login.kugou.com",
    "login-user.kugou.com",
    "passport.kugou.com",
)

/**
 * 网页登录容器。用 [WebView] 打开源的官方登录页，
 * 用户在网页里自己完成登录（密码、短信、滑块、第三方授权都走源自己的流程），
 * 完成后我们把 CookieManager 里的 cookie 读出来交给源的 CookieImport 分支。
 *
 * 不注入脚本、不读页面 DOM、不保存密码：
 * 只依赖「登录成功后浏览器必然写入了会话 cookie」这一个事实。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WebLoginSheet(
    target: WebLoginTarget,
    busy: Boolean,
    onClose: () -> Unit,
    onHarvest: (String) -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var pageTitle by remember { mutableStateOf("正在打开…") }
    var lastHarvest by remember { mutableStateOf("") }

    BackHandler(enabled = true) {
        val wv = webView
        // 回退只处理网页内的历史；已经在首页时退到外层（关闭面板），
        // 不把返回事件继续传给下层，否则会被父级的「返回首页」再吃一次。
        if (wv?.canGoBack() == true) {
            wv.goBack()
        } else {
            val cm = CookieManager.getInstance()
            target.cookieHosts.forEach { cm.flush() }
            onClose()
        }
    }

    /** 把 CookieManager 里配置过的域全部拼成一条 Cookie 串。 */
    fun harvest(): String {
        val cm = CookieManager.getInstance()
        val parts = target.cookieHosts.mapNotNull { host ->
            cm.getCookie("https://$host/")?.takeIf { it.isNotBlank() }
        }
        // 同一个 cookie 名可能在多个域重复，按名去重但保留第一次出现的值
        val merged = LinkedHashMap<String, String>()
        for (chunk in parts) {
            for (pair in chunk.split(';')) {
                val name = pair.substringBefore('=', "").trim()
                val value = pair.substringAfter('=', "").trim()
                if (name.isNotEmpty() && !merged.containsKey(name)) merged[name] = value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        while (true) {
            delay(1200)
            // 轮询 cookie 而不是等回调：各源登录成功后的跳转路径不统一，cookie 才是唯一可靠信号
            val cookie = harvest()
            if (cookie != lastHarvest) {
                lastHarvest = cookie
                if (target.looksLoggedIn(cookie)) {
                    DownloadTrace.log(
                        webView!!.context,
                        "weblogin ${target.platform.name} looked-signed-in keys=${target.cookieKeys.joinToString()}"
                    )
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭网页登录")
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    Text(
                        text = "${target.platform.displayName}网页登录",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = pageTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                IconButton(onClick = { webView?.reload() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "重新加载")
                }
            }

            AnimatedVisibility(visible = progress < 1f) {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Text(
                text = target.doneHint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    // 吃掉所有落在网页区的触摸：否则点空处会穿透到下层 Scaffold，
                    // 把整块登录面板连父界面一起关掉
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            // 用手机版 UA：桌面 UA 会触发 QQ 音乐的「请下载客户端」整页遮罩，
                            // 手机 UA 才走 m.y.qq.com 那套网页登录
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 PolyTune/1.0"
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            // QQ/微信登录都跑在 iframe 里（wlogin 的 qq 登录与 wechat 登录两个 tab
                            // 各是一个跨域 iframe），不打开混合内容与跨域 allowFileAccess 就只看到白屏
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.javaScriptCanOpenWindowsAutomatically = true
                            settings.setSupportMultipleWindows(true)
                            settings.setSupportZoom(true)
                            settings.loadsImagesAutomatically = true
                            settings.blockNetworkImage = false
                            webViewClient = object : WebViewClient() {
                                private fun probe(tag: String, url: String?) {
                                    val ctx = context
                                    DownloadTrace.log(ctx, "probe $tag url=$url")
                                    // 每次跳转都把 CookieManager 里所有相关域的 cookie 原文打出来：
                                    // 只有原文才能看出「登录态到底落在哪个键上」
                                    ProbeHosts.forEach { h ->
                                        val c = runCatching { CookieManager.getInstance().getCookie("https://$h/") }.getOrNull()
                                        if (!c.isNullOrBlank()) {
                                            DownloadTrace.log(ctx, "probe $tag cookie[$h]=$c")
                                        }
                                    }
                                    // localStorage / sessionStorage 全量 dump，DOM 里的可读文本也带一段，
                                    // 网页登录如果走的是 JS 存 token，这里一定能看见
                                    evaluateJavascript(
                                        "JSON.stringify({ls:Object.assign({},localStorage),ss:Object.assign({},sessionStorage)," +
                                            "ck:document.cookie,title:document.title," +
                                            "u:(window.__POLY_LAST_URL__||location.href)})",
                                    ) { raw ->
                                        DownloadTrace.log(context, "probe $tag js[$url]=${raw.take(4000)}")
                                    }
                                }

                                override fun onPageStarted(v: WebView?, url: String?, favicon: Bitmap?) {
                                    progress = 0.1f
                                    pageTitle = url?.substringAfter("://")?.substringBefore('?').orEmpty()
                                    probe("start", url)
                                }

                                override fun onPageFinished(v: WebView?, url: String?) {
                                    progress = 1f
                                    v?.evaluateJavascript("window.__POLY_LAST_URL__=" + jsStr(url.orEmpty()), null)
                                    probe("finish", url)
                                }

                                /**
                                 * 主资源加载失败必须落盘：WebView 只在页面里显示「网页无法打开」，
                                 * 不写这条日志就分不清是 DNS、TLS 还是站点 4xx/5xx。
                                 */
                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: android.webkit.WebResourceError?,
                                ) {
                                    // iframe 内的错误（isForMainFrame 为 false）也要记，
                                    // 因为 QQ 登录框整体是嵌在 wlogin 的 iframe 里的，
                                    // 之前只记主框架，iframe 白屏时一点线索都没有。
                                    DownloadTrace.log(
                                        context,
                                        "probe ERROR main=${request?.isForMainFrame} url=${request?.url} " +
                                            "code=${error?.errorCode} desc=${error?.description}",
                                    )
                                    if (request?.isForMainFrame != true) return
                                    probe("error", request.url.toString())
                                    evaluateJavascript(
                                        "JSON.stringify({t:document.title,b:(document.body&&document.body.innerText||'').slice(0,600)});",
                                    ) { raw -> DownloadTrace.log(context, "probe errpage[$url]=$raw") }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    response: android.webkit.WebResourceResponse?,
                                ) {
                                    DownloadTrace.log(
                                        context,
                                        "probe HTTP ${response?.statusCode} main=${request?.isForMainFrame} url=${request?.url}",
                                    )
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: android.webkit.SslErrorHandler?,
                                    error: android.net.http.SslError?,
                                ) {
                                    DownloadTrace.log(context, "probe SSL err=${error?.primaryError} url=${error?.url}")
                                    // 只记录不放过：凭据页面的证书校验该失败就失败，探针只负责把原因写下来
                                    handler?.cancel()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString().orEmpty()
                                    DownloadTrace.log(context, "probe redirect url=$u")
                                    // 微信授权完成后会跳 weixin:// 或 wxpay:// 这类自定义协议，
                                    // WebView 打不开它们，而一旦交给系统处理，页面就停在一个
                                    // 「无法加载，ERR_UNKNOWN_URL_SCHEME」的空白错误页上。
                                    // 这些协议对取 cookie 没有意义，直接拦掉，并把当前页面留着不动。
                                    val scheme = u.substringBefore(':', "").lowercase()
                                    val okScheme = scheme == "http" || scheme == "https" || scheme == "wlogin" ||
                                        scheme == "about" || scheme == "data" || scheme == "blob" ||
                                        scheme == "ws" || scheme == "wss"
                                    if (!okScheme) {
                                        DownloadTrace.log(context, "weblogin blocked scheme=$scheme url=${u.take(120)}")
                                        val hint = "该跳转需要客户端 App（$scheme），已拦下；" +
                                            "如果此时网页里已显示你的头像，直接点下面的「已登录，读取凭证」。"
                                        pageTitle = hint
                                        return true
                                    }
                                    return false
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(v: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                }

                                override fun onReceivedTitle(v: WebView?, title: String?) {
                                    if (!title.isNullOrBlank()) pageTitle = title
                                }

                                /**
                                 * 让 wlogin 的弹窗走本 WebView，而不是交给系统浏览器。
                                 *
                                 * QQ 的登录入口在 wlogin 页的 iframe 里，点「QQ 登录」后前端会
                                 * window.open 一个 ptlogin2.qq.com 的登录窗。不接管的话弹出的是另一个
                                 * 窗口，票据写进那个窗口的 cookie 罐，父页面读不到；统一转成同一
                                 * CookieManager 才拿得到 qqmusic_key。
                                 */
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?,
                                ): Boolean {
                                    DownloadTrace.log(context, "weblogin new window isDialog=$isDialog")
                                    val transport =
                                        resultMsg?.obj as? android.webkit.WebView.WebViewTransport ?: return false
                                    val ua = view?.settings?.userAgentString
                                    val child = WebView(context).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.userAgentString = ua
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(
                                                v: WebView?,
                                                r: WebResourceRequest?,
                                            ): Boolean {
                                                val u = r?.url?.toString().orEmpty()
                                                DownloadTrace.log(context, "weblogin popup nav url=$u")
                                                // 弹窗登录成功后常跳回主站；此时票据已写进 CookieManager，
                                                // 让主窗口接着走，用户能在主窗口看到登录后的头像
                                                if (u.startsWith("http")) webView?.loadUrl(u)
                                                return true
                                            }
                                        }
                                        webChromeClient = object : android.webkit.WebChromeClient() {}
                                    }
                                    transport.webView = child
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }
                            loadUrl(target.loginUrl)
                            webView = this
                        }
                    },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("取消") }
                Spacer(Modifier.weight(1f))
                Surface(
                    onClick = {
                        val cookie = webView?.let { harvest() }.orEmpty()
                        DownloadTrace.log(
                            webView!!.context,
                            "weblogin ${target.platform.name} harvest len=${cookie.length} keys=${
                                cookie.split(';').take(8).joinToString("|") { it.substringBefore('=').trim() }
                            }"
                        )
                        onHarvest(cookie)
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) {
                    Text(
                        text = if (busy) "读取凭证…" else "已登录，读取凭证",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            CookieManager.getInstance().flush()
            webView?.apply {
                stopLoading()
                destroy()
            }
            webView = null
        }
    }
}
