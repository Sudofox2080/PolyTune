package com.polytune.core.proto

import android.content.Context
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.Protocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI

/**
 * 多协议中间件：把任意 PlayEndpoint 归一化成「Media3 能直接喂的 URI + 请求头」。
 *
 * - HTTP / HLS / DASH：原样返回，附带源需要的头（Referer、Cookie）。
 * - WEBDAV：要求 endpoint 里有 baseUser/basePass，返回原 URL 加 Basic 认证头。
 * - SMB：smb:// 地址在本地起一个 127.0.0.1 的 HTTP 代理，Media3 走代理读流，支持 Range。
 * - LOCAL：file:// URI 直接返回。
 */
object ProtocolMiddleware {

    private val insecure: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    data class Prepared(
        val uri: String,
        val headers: Map<String, String>,
        val protocol: Protocol,
        val cleanup: (() -> Unit)? = null,
    )

    /**
     * 解析 SMB 地址里的账号密码，形如 smb://user:pass@host/share/path。
     * 返回去掉 userinfo 的地址与账号密码。
     *
     * 密码里带 @ 或 / 时 URL 作者通常是不转义的，java.net.URI 会直接抛异常；
     * 所以先按最后一个 '@' 手工切，切不出来再退回 URI 解析。
     */
    fun splitSmb(raw: String): Triple<String, String?, String?> {
        val schemeLen = when {
            raw.startsWith("smb://") -> 6
            raw.startsWith("smb:\\\\") -> 6
            else -> 0
        }
        val rest = raw.substring(schemeLen)
        val at = rest.lastIndexOf('@')
        if (at < 0) return Triple(raw, null, null)
        val userInfo = rest.substring(0, at)
        val head = raw.substring(0, schemeLen)
        val clean = head + rest.substring(at + 1)
        val user = userInfo.substringBefore(':')
        val pass = if (userInfo.contains(':')) userInfo.substringAfter(':') else null
        return Triple(
            clean,
            android.net.Uri.decode(user).ifBlank { null },
            pass?.let { android.net.Uri.decode(it) },
        )
    }

    fun basicAuth(user: String, pass: String): String =
        "Basic " + com.polytune.core.crypto.Crypt.base64("$user:$pass")

    suspend fun prepare(ctx: Context, ep: PlayEndpoint): Prepared = when (ep.protocol) {
        Protocol.HTTP, Protocol.HLS, Protocol.DASH -> Prepared(ep.url, ep.headers, ep.protocol)

        Protocol.WEBDAV -> {
            // 账号可能三种姿势传进来：headers["user"/"pass"]、URL 里的 userinfo
            // (http://u:p@host/x)、或已经写好的 Authorization 头。旧的只认 headers，
            // 缺省还会补一个 guest 分支，等于把真实账号覆盖成 guest，服务端必然 401。
            val urlInfo = runCatching { URI(ep.url).rawUserInfo }.getOrNull()
            val user = ep.headers["user"] ?: urlInfo?.substringBefore(':') ?: "guest"
            val pass = ep.headers["pass"] ?: urlInfo?.substringAfter(':', "") ?: ""
            val auth = ep.headers["Authorization"]
                ?: ep.headers.keys.firstOrNull { it.equals("Authorization", true) }?.let { ep.headers[it] }
                ?: basicAuth(user, pass)
            // user/pass 这两个是仓库内部约定，不是 HTTP 头，漏给网络层会被当成畸形头。
            val clean = ep.headers.filterKeys { it != "user" && it != "pass" }
            Prepared(ep.url, clean + ("Authorization" to auth), Protocol.WEBDAV)
        }

        Protocol.SMB -> {
            val (clean, user, pass) = splitSmb(ep.url)
            val proxy = SmbProxy.start(ctx, clean, user, pass)
            Prepared(proxy.url, emptyMap(), Protocol.SMB, cleanup = { proxy.stop() })
        }

        Protocol.LOCAL -> Prepared(
            // 已有 file URI 必须保留转义与路径；只有原始路径才交给 File。
            if (ep.url.startsWith("file:", ignoreCase = true)) {
                android.net.Uri.parse(ep.url).normalizeScheme().toString()
            } else {
                android.net.Uri.fromFile(File(ep.url)).toString()
            },
            emptyMap(),
            Protocol.LOCAL,
        )
    }

    /** 探测远端是否支持 Range，用于断点续传决策。 */
    suspend fun acceptsRange(url: String, headers: Map<String, String>): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .header("Range", "bytes=0-0")
                .head()
                .build()
            insecure.newCall(req).execute().use { it.code == 206 }
        }.getOrDefault(false)
    }
}
