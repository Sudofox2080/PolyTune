package com.polytune.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.JavascriptInterface
import com.polytune.core.download.DownloadTrace
import com.polytune.core.download.TraceCtx

/**
 * QQ 音乐的人机验证面板。
 *
 * 为什么需要它：官方发码接口 `music.login.LoginServer/SendPhoneAuthCode` 对任何新设备
 * 第一次都会回 `20276 robot defense`，并在 `data.securityURL` 里给一个
 * `https://c.y.qq.com/r/fy6U?...` 的验证页。那页加载 QQ 音乐自家的 SafetyCaptcha，
 * 用户滑完/点完之后它会调用
 *
 * ```js
 * qqmusicClient.open('ui', 'setSecureVerificationStatus', { status: 0 })
 * qqmusicClient.open('ui', 'closeWebview')
 * ```
 *
 * 这里注入一个同名的 `Music.client` 桩，把这两个调用变成 Android 回调：
 * 收到 `setSecureVerificationStatus` 就认为验证通过，收到 `closeWebview` 就关闭面板。
 * 验证通过后由调用方重新发一次验证码，服务端就会真的下发短信。
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun QqCaptchaSheet(
    verifyUrl: String,
    onVerified: () -> Unit,
    onClose: () -> Unit,
) {
    var progress by remember { mutableStateOf(0f) }
    var title by remember { mutableStateOf("人机验证") }
    var verified by remember { mutableStateOf(false) }
    val webHolder = remember { WebViewHolder() }
    var injected by remember { mutableStateOf(false) }

    fun trace(msg: String) {
        TraceCtx.app?.let { DownloadTrace.log(it, "qq captcha | $msg") }
    }

    BackHandler(enabled = true) { onClose() }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "关闭")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "QQ音乐人机验证",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { webHolder.web?.reload() }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            }
            if (progress < 1f) {
                LinearWavyProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
            }

            Text(
                text = "按页面提示完成滑动或点选，验证通过后会自动继续发短信。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(18.dp)),
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val created = WebView(ctx)
                        created.apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.databaseEnabled = true
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            settings.loadsImagesAutomatically = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.userAgentString =
                                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                                "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            addJavascriptInterface(
                                MusicClientBridge(
                                    onStatus = { status ->
                                        trace("setSecureVerificationStatus=$status")
                                        if (status == 0) {
                                            verified = true
                                            onVerified()
                                        }
                                    },
                                    onClose = { reason ->
                                        trace("closeWebview reason=$reason")
                                        onClose()
                                    },
                                ),
                                "PolyTuneMusic",
                            )
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    progress = newProgress / 100f
                                    // 进度到 60% 以上就把桥注进去，赶在 SafetyCaptcha 初始化之前
                                    if (newProgress >= 60 && !injected) {
                                        injected = true
                                        view?.evaluateJavascript(QQ_CAPTCHA_BRIDGE_JS, null)
                                        trace("bridge injected")
                                    }
                                }
                                override fun onReceivedTitle(view: WebView?, t: String?) {
                                    if (!t.isNullOrBlank()) title = t
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    progress = 0.08f
                                }
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val u = request?.url?.toString() ?: return false
                                    if (u.startsWith("http://") || u.startsWith("https://")) return false
                                    return true
                                }
                            }
                            loadUrl(verifyUrl)
                            webHolder.web = created
                        }
                        created
                    },
                    update = { /* 只加载一次，刷新走顶栏按钮 */ },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onClose) { Text("取消") }
                Spacer(Modifier.weight(1f))
                FilledTonalButton(
                    onClick = {
                        if (verified) onVerified() else {
                            trace("manual continue")
                            onVerified()
                        }
                    },
                ) {
                    Icon(Icons.Rounded.VerifiedUser, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(" 已验证，继续", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                webHolder.web?.stopLoading()
                webHolder.web?.loadUrl("about:blank")
                webHolder.web?.destroy()
            }
            webHolder.web = null
        }
    }
}

/**
 * 页面里 `Music.client.open(service, method, params, cb)` 的桥。
 * JS 侧调用方式通过 [PolyTuneMusic] 暴露，注入的 JS 在页面加载后完成替换。
 */
private class WebViewHolder {
    var web: WebView? = null
}

class MusicClientBridge(
    private val onStatus: (Int) -> Unit,
    private val onClose: (String) -> Unit,
) {
    @JavascriptInterface
    fun uiOpen(method: String, status: Int) {
        when (method) {
            "setSecureVerificationStatus" -> onStatus(status)
            "closeWebview" -> onClose("manual")
        }
    }

    @JavascriptInterface
    fun notifyClose(reason: String) {
        onClose(reason)
    }

    @JavascriptInterface
    fun noop(): String = "{\"ret\":0}"
}

/** 注入到验证页的桥接脚本：把页面的 qqmusicClient.open 转给原生。 */
val QQ_CAPTCHA_BRIDGE_JS: String = listOf(
    "(function () {",
    "  if (window.__polyTuneBridge) return;",
    "  window.__polyTuneBridge = true;",
    "  var send = function (method, status) {",
    "    try { PolyTuneMusic.uiOpen(method, status); } catch (e) {}",
    "  };",
    "  var stub = {",
    "    open: function (service, method, params, cb) {",
    "      if (method === 'setSecureVerificationStatus') {",
    "        var s = params && typeof params.status !== 'undefined' ? params.status : 0;",
    "        send(method, Number(s));",
    "      } else if (method === 'closeWebview') {",
    "        send(method, 0);",
    "      }",
    "      if (typeof cb === 'function') cb({ ret: 0 });",
    "      return true;",
    "    }",
    "  };",
    "  var hook = function () {",
    "    try {",
    "      if (window.Music) { window.Music.client = stub; }",
    "      else { window.Music = { client: stub }; }",
    "    } catch (e) {}",
    "  };",
    "  hook();",
    "  var tries = 0;",
    "  var timer = setInterval(function () {",
    "    hook();",
    "    if (++tries > 60) clearInterval(timer);",
    "  }, 100);",
    "})();",
).joinToString("\n")
