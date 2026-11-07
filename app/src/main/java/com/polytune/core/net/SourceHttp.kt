package com.polytune.core.net

import com.polytune.source.api.Credential
import com.polytune.source.api.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 每个音乐源一个实例，自带 cookie 罐与登录态。共用底�?OkHttpClient 的连接池，但拦截器链独立�? */
class SourceHttp(val platform: Platform) {

    private val cookies = ConcurrentHashMap<String, MutableList<Cookie>>()
    @Volatile private var credential: Credential? = null

    fun attach(c: Credential) {
        credential = c
        // 凭证里若�?cookie 串，解析�?cookie 罐，后续请求自动携带
        c.extra["cookie"]?.takeIf { it.isNotBlank() }?.let { raw ->
            val url = baseUrl() ?: return@let
            parseCookies(raw, url.host).forEach { addCookie(it) }
        }
        c.extra["MUSIC_U"]?.let { addCookie(simpleCookie("MUSIC_U", it, ".music.163.com")) }
    }

    fun detach() {
        credential = null
        cookies.clear()
    }

    private fun baseUrl(): HttpUrl? = defaultOrigin().toHttpUrlOrNull()

    private fun defaultOrigin(): String = when (platform) {
        Platform.QQ -> "https://c.y.qq.com"
        Platform.NETEASE -> "https://music.163.com"
        Platform.KUGOU -> "https://mobilecdn.kugou.com"
    }

    private fun simpleCookie(name: String, value: String, domain: String): Cookie =
        Cookie.Builder().name(name).value(value).domain(domain).path("/").build()

    private fun parseCookies(raw: String, host: String): List<Cookie> =
        raw.split(";").mapNotNull { part ->
            val kv = part.trim().split("=", limit = 2)
            if (kv.size != 2) return@mapNotNull null
            runCatching { Cookie.Builder().name(kv[0].trim()).value(kv[1].trim()).domain(host).path("/").build() }
                .getOrNull()
        }

    fun addCookie(c: Cookie) {
        cookies.computeIfAbsent(c.domain, { mutableListOf() }).apply {
            removeAll { it.name == c.name }
            add(c)
        }
    }

    fun cookieString(host: String): String =
        cookies.entries.filter { host.endsWith(it.key.removePrefix(".")) }
            .flatMap { it.value }
            .joinToString("; ") { "${it.name}=${it.value}" }

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookieList: List<Cookie>) =
            cookieList.forEach { addCookie(it) }

        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.entries.filter { url.host.endsWith(it.key.removePrefix(".")) }
                .flatMap { it.value }
    }

    private val signInterceptor = Interceptor { chain ->
        val origin = chain.request()
        val req = origin.newBuilder().apply {
            header("User-Agent", userAgent())
            val ck = cookieString(origin.url.host)
            if (ck.isNotEmpty()) header("Cookie", ck)
            when (platform) {
                Platform.QQ -> {
                    header("Referer", "https://y.qq.com/portal/player.html")
                    header("Origin", "https://y.qq.com")
                }
                Platform.NETEASE -> {
                    header("Referer", "https://music.163.com")
                    header("Origin", "https://music.163.com")
                }
                Platform.KUGOU -> {
                    val dfid = credential?.extra?.get("dfid") ?: "poly-default-dfid"
                    header("dfid", dfid)
                    header("mid", com.polytune.core.crypto.Crypt.md5("${dfid}kugou${credential?.uid ?: "0"}$dfid"))
                }
            }
        }.build()
        chain.proceed(req)
    }

    private fun userAgent(): String = when (platform) {
        Platform.QQ -> "Mozilla/5.0 (Linux; Android 14; PolyTune) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36 QQMusic/13.5"
        Platform.NETEASE -> "NeteaseMusic/9.1.20.240603200340(9012004);Dalvik/2.1.0 (Linux; U; Android 14)"
        Platform.KUGOU -> "Android712-AndroidPhone-11451-2-0-1010000101-0-0"
    } + " PolyTune/1.0"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(signInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (com.polytune.BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                    else HttpLoggingInterceptor.Level.NONE
                },
            )
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String =
        call(Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build())

    suspend fun getBytes(url: String, headers: Map<String, String> = emptyMap()): ByteArray =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code} @ $url")
                r.body?.bytes() ?: ByteArray(0)
            }
        }

    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val body = okhttp3.FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        return call(
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body).build(),
        )
    }

    suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String> = emptyMap()): String {
        val body = okhttp3.RequestBody.create("application/json".toMediaType(), jsonBody)
        return call(
            Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.post(body).build(),
        )
    }

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        client.newCall(req).execute().use { r: Response ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("HTTP ${r.code}: ${text.take(200)}")
            text
        }
    }

    /** "a=1&b=2" → LinkedHashMap。保留原文顺序，重复键取最后一个，与 FormBody 追加行为一致。 */
    private fun parseForm(params: String): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            params.split("&").forEach { seg ->
                if (seg.isBlank()) return@forEach
                val eq = seg.indexOf('=')
                if (eq <= 0) put(seg, "") else put(seg.substring(0, eq), seg.substring(eq + 1))
            }
        }
}
