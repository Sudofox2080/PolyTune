package com.polytune.source.netease

import com.polytune.core.crypto.Crypt
import com.polytune.core.download.DownloadTrace
import com.polytune.core.download.TraceCtx
import com.polytune.core.net.SourceHttp
import com.polytune.core.util.Jp
import com.polytune.core.util.LrcParser
import com.polytune.source.api.BannerItem
import com.polytune.source.api.BaseMusicSource
import com.polytune.source.api.Credential
import com.polytune.source.api.LineLyric
import com.polytune.source.api.LoginKind
import com.polytune.source.api.LoginMethod
import com.polytune.source.api.LoginResult
import com.polytune.source.api.Lyric
import com.polytune.source.api.Platform
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.PlaylistBrief
import com.polytune.source.api.Protocol
import com.polytune.source.api.Quality
import com.polytune.source.api.SearchPage
import com.polytune.source.api.Song
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * 网易云音乐源。
 *
 * 主通道是 eapi：AES-128-ECB（key=e82ckenh8dichen8），请求体形如
 * `path-36cd479b6b5-{json}-36cd479b6b5-{md5("nobody"+path+"use"+json+"md5forencrypt")}`，
 * 且 json 里要嵌入 header 对象（os/deviceId/appver/versioncode/channel/requestId 等），
 * 同一批 header 还要在 Cookie 里以 key=value 形式再出现一次。这套组合实测能稳定拿到
 * 搜索、歌词、详情、banner 与 playUrl；旧 weapi 通道在线上对 cloudsearch 返回 0 字节，
 * 只保留给登录后的少数字段做兜底。
 *
 * 登录：手机号密码 / 短信验证码 / 二维码 / cookie 导入，统一走 /api/w/login/cellphone。
 * 音质：standard/exhigh/lossless/hires 映射到 level 参数，实际拿到的档位由账号权限决定，
 * 服务端会静默降级，这里按响应里的 type/level/br 反推真实档位，不做任何权限绕过。
 */
class NeteaseSource(net: SourceHttp) : BaseMusicSource(Platform.NETEASE, net) {

    private val api = "https://music.163.com"
    private val eapiHost = "https://interface.music.163.com"
    private val aesKey = "e82ckenh8dichen8"
    private val presetKey = "0CoJUm6Qyw8W8jud"
    private val iv = "0102030405060708"
    private val pubKey = "010001"
    private val modulus =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    /** 设备标识，形如 4_4_4_4_4_4_4_5 位的十六进制分段。 */
    private val deviceId: String by lazy {
        val g = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        listOf(4, 4, 4, 4, 4, 4, 4, 5).scan(0) { acc, n -> acc + n }
            .zipWithNext()
            .joinToString("_") { (a, b) -> g.substring(a, minOf(b, g.length)) }
    }

    private fun log(msg: String) {
        TraceCtx.app?.let { DownloadTrace.log(it, "netease | $msg") }
    }

    /* ---------------- 登录 ---------------- */

    override suspend fun login(method: LoginMethod): LoginResult = when (method) {
        is LoginMethod.PhonePassword -> cellphoneLogin(method.phone, password = method.password)
        is LoginMethod.SmsCode -> cellphoneLogin(method.phone, captcha = method.code)
        is LoginMethod.EmailPassword -> emailLogin(method.email, method.password)
        is LoginMethod.CookieImport -> cookieLogin(method.cookie)
        is LoginMethod.TokenInject -> tokenLogin(method.token, method.uid)
        is LoginMethod.QrCode -> qrCreate()
    }

    override fun supportedMethods(): Set<LoginKind> = setOf(
        LoginKind.Web,
        LoginKind.PhoneSms, LoginKind.PhonePassword, LoginKind.EmailPassword, LoginKind.QR,
        LoginKind.Cookie, LoginKind.Token,
    )

    /** 邮箱登录：/api/w/login，type=0，用户名直接是邮箱。 */
    private suspend fun emailLogin(email: String, password: String): LoginResult = runCatching {
        val payload = buildJsonObject {
            put("type", "0")
            put("https", "true")
            put("username", email)
            put("password", Crypt.md5(password))
            put("rememberLogin", "true")
        }
        val res = parseLogin(weapi("/api/w/login", payload))
        if (res is LoginResult.Fail) {
            log("email login fail ${res.message}")
        }
        res
    }.getOrElse { LoginResult.Fail("邮箱登录失败：${it.message}") }

    /** 用已存的 MUSIC_U 续期，服务端会回一个新 cookie。 */
    override suspend fun refreshLogin(): LoginResult? = runCatching {
        val cred = credential.value ?: return@runCatching null
        val body = weapi("/api/login/token/refresh", buildJsonObject {})
        log("refresh len=${body.length}")
        if (body.isBlank()) return@runCatching null
        val root = Jp.root(body)
        if (Jp.int(root, "code") != 200) return@runCatching null
        val ck = Jp.str(root, "cookie").orEmpty()
        val musicU = Regex("MUSIC_U=([^;]+)").find(ck)?.groupValues?.get(1) ?: cred.token
        val csrf = Regex("__csrf=([^;]+)").find(ck)?.groupValues?.get(1) ?: cred.uid
        setCredential(cred.copy(uid = csrf, token = musicU, extra = cred.extra + ("cookie" to ck)))
        LoginResult.Success(credential.value ?: cred)
    }.getOrNull()

    override suspend fun sendSmsCode(phone: String): LoginResult = runCatching {
        val body = weapi(
            "/api/sms/captcha/sent",
            buildJsonObject {
                put("ctcode", "86")
                put("secrete", "music_middleuser_pclogin")
                put("cellphone", phone)
            },
        )
        log("sms sent phone=${phone.take(3)}**** len=${body.length}")
        if (body.isBlank()) return@runCatching LoginResult.Fail("验证码发送失败（空响应）")
        val root = Jp.root(body)
        val code = Jp.int(root, "code") ?: -1
        val msg = Jp.str(root, "message") ?: Jp.str(root, "msg").orEmpty()
        when {
            code == 200 -> LoginResult.Pending("", message = "验证码已发送到 $phone")
            code == 400 -> LoginResult.Fail(msg.ifBlank { "手机号格式不正确" })
            code == 503 -> LoginResult.Fail("发送过于频繁，请稍后再试")
            else -> LoginResult.Fail("验证码发送失败($code)：$msg")
        }
    }.getOrElse { LoginResult.Fail("验证码发送失败：${it.message}") }

    private suspend fun cellphoneLogin(
        phone: String,
        password: String? = null,
        captcha: String? = null,
    ): LoginResult = runCatching {
        val payload = buildJsonObject {
            put("type", "1")
            put("https", "true")
            put("phone", phone)
            put("countrycode", "86")
            if (captcha != null) put("captcha", captcha) else put("password", Crypt.md5(password.orEmpty()))
            put("remember", "true")
        }
        parseLogin(weapi("/api/w/login/cellphone", payload))
    }.getOrElse { LoginResult.Fail("登录失败：${it.message}") }

    private suspend fun cookieLogin(cookie: String): LoginResult {
        val cookieStr = cookie.trim().removePrefix("Cookie:").trim()
        val musicU = Regex("MUSIC_U=([^;]+)").find(cookieStr)?.groupValues?.get(1)
            ?: return LoginResult.Fail("cookie 里没找到 MUSIC_U")
        val csrf = Regex("__csrf=([^;]+)").find(cookieStr)?.groupValues?.get(1).orEmpty()
        return tokenLogin(musicU, csrf)
    }

    private suspend fun tokenLogin(token: String, csrf: String): LoginResult = runCatching {
        val cred = Credential(
            platform = Platform.NETEASE,
            uid = csrf.ifBlank { "web" },
            token = token,
            extra = mapOf("cookie" to "MUSIC_U=$token; __csrf=$csrf"),
        )
        setCredential(cred)
        val info = runCatching { account() }.getOrNull()
        val nick = info?.let { Jp.str(Jp.root(it), "profile.nickname") }.orEmpty()
        val av = info?.let { Jp.str(Jp.root(it), "profile.avatarUrl") }.orEmpty()
        if (nick.isNotBlank()) setCredential(cred.copy(nickname = nick, avatar = av))
        LoginResult.Success(credential.value ?: cred)
    }.getOrElse { LoginResult.Fail("凭证校验失败：${it.message}") }

    private suspend fun account(): String = weapi("/api/w/nuser/account/get", buildJsonObject {})

    override suspend fun qrCreate(): LoginResult = runCatching {
        val body = eapi("/api/login/qrcode/unikey", buildJsonObject { put("type", 3) })
        val unikey = Jp.str(Jp.root(body), "unikey").orEmpty()
        if (unikey.isBlank()) return@runCatching LoginResult.Fail("二维码获取失败")
        log("qr key=${unikey.take(8)}")
        LoginResult.Pending(
            ticket = unikey,
            qrImageUrl = "https://music.163.com/login?codekey=$unikey",
            message = "用网易云 App 扫码",
        )
    }.getOrElse { LoginResult.Fail(it.message ?: "二维码获取失败") }

    override suspend fun qrPoll(ticket: String): LoginResult = runCatching {
        val body = eapi(
            "/api/login/qrcode/client/login",
            buildJsonObject { put("key", ticket); put("type", 3) },
        )
        val root = Jp.root(body)
        when (Jp.int(root, "code")) {
            803 -> cookieLogin(Jp.str(root, "cookie").orEmpty())
            802 -> LoginResult.Pending(ticket, message = "已扫码，等待确认")
            801 -> LoginResult.Pending(ticket, message = "等待扫码")
            else -> LoginResult.Fail("二维码已过期")
        }
    }.getOrElse { LoginResult.Fail(it.message ?: "轮询失败") }

    private fun parseLogin(body: String): LoginResult {
        val root = Jp.root(body)
        val code = Jp.int(root, "code") ?: -1
        if (code == 200 || code == 502) {
            val ck = Jp.str(root, "cookie").orEmpty()
            val musicU = Regex("MUSIC_U=([^;]+)").find(ck)?.groupValues?.get(1)
                ?: return LoginResult.Fail("返回体里没有 MUSIC_U，可能是新设备风控，改用 cookie 导入")
            val csrf = Regex("__csrf=([^;]+)").find(ck)?.groupValues?.get(1).orEmpty()
            val cred = Credential(
                Platform.NETEASE,
                csrf.ifBlank { "web" },
                musicU,
                nickname = Jp.str(root, "profile.nickname").orEmpty(),
                avatar = Jp.str(root, "profile.avatarUrl").orEmpty(),
                extra = mapOf("cookie" to ck),
            )
            setCredential(cred)
            log("login ok uid=${Jp.long(root, "account.id")}")
            return LoginResult.Success(cred)
        }
        val msg = Jp.str(root, "message") ?: Jp.str(root, "msg") ?: "未知错误"
        return LoginResult.Fail(when (code) {
            501 -> "账号不存在"
            502 -> "密码错误"
            503 -> "需要图片验证码（$msg）"
            509 -> "请求过于频繁，请稍后"
            else -> "登录失败($code)：$msg"
        })
    }

    /* ---------------- 加密通道 ---------------- */

    private fun eapiHeader(): Map<String, String> = linkedMapOf(
        "osver" to "15",
        "deviceId" to deviceId,
        "os" to "android",
        "appver" to "9.1.20",
        "versioncode" to "140",
        "mobilename" to "PolyTune",
        "buildver" to (System.currentTimeMillis() / 1000).toString(),
        "resolution" to "1080x1920",
        "__csrf" to credential.value?.uid.orEmpty(),
        "channel" to "xiaomi",
        "requestId" to "${System.currentTimeMillis()}_${(1000..9999).random()}",
    )

    private fun cookieOf(header: Map<String, String>): String =
        header.entries.joinToString("; ") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    /** eapi：AES-128-ECB → 大写 hex，params 里只放这一个字段。 */
    private suspend fun eapi(path: String, payload: JsonObject): String {
        val header = eapiHeader()
        val data = buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
            put("header", buildJsonObject { header.forEach { (k, v) -> put(k, v) } })
        }
        val text = data.toString()
        val digest = Crypt.md5("nobody${path}use${text}md5forencrypt")
        val raw = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
        val params = Crypt.aesEcbHex(raw, aesKey.toByteArray())
        return net.postForm(
            "$eapiHost/eapi/${path.removePrefix("/api/")}",
            mapOf("params" to params),
            linkedMapOf(
                "Cookie" to cookieOf(header),
                "User-Agent" to "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)",
                "Referer" to "https://music.163.com/",
            ),
        )
    }

    /** weapi：AES-CBC 双层 + RSA，登录与账号信息仍用它。 */
    private suspend fun weapi(path: String, payload: JsonObject): String {
        val text = payload.toString()
        val secretKey = (1..16)
            .map { "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".random() }
            .joinToString("")
        val params = Crypt.aesCbcBase64(
            Crypt.aesCbcBase64(text.toByteArray(), presetKey.toByteArray()).let { Crypt.base64Decode(it) },
            secretKey.toByteArray(),
        )
        val encSecKey = Crypt.rsaNoPadding(secretKey.toByteArray().reversedArray(), modulus, pubKey)
        val csrf = credential.value?.uid.orEmpty()
        val clean = path.removePrefix("/").removePrefix("weapi/").removePrefix("api/")
        return net.postForm("$api/weapi/$clean?csrf_token=$csrf", mapOf("params" to params, "encSecKey" to encSecKey))
    }

    /* ---------------- 搜索 ---------------- */

    override suspend fun search(keyword: String, page: Int, pageSize: Int): SearchPage = runCatching {
        val body = eapi(
            "/api/cloudsearch/pc",
            buildJsonObject {
                put("s", keyword)
                put("type", "1")
                put("limit", pageSize.toString())
                put("offset", ((page - 1) * pageSize).toString())
                put("total", "true")
            },
        )
        log("search kw=$keyword page=$page len=${body.length}")
        if (body.isBlank()) return@runCatching SearchPage()
        parseSearch(body, page, pageSize)
    }.getOrElse {
        log("search fail ${it.javaClass.simpleName}: ${it.message}")
        SearchPage()
    }

    private fun parseSearch(body: String, page: Int, pageSize: Int): SearchPage {
        val root = Jp.root(body)
        val songs = Jp.objects(root, "result.songs").mapNotNull { o ->
            val id = Jp.long(o, "id")?.toString() ?: return@mapNotNull null
            val artists = Jp.objects(o, "ar").mapNotNull { Jp.str(it, "name") }
            val albumName = Jp.str(o, "al.name") ?: ""
            val fee = Jp.int(o, "fee") ?: 0
            val maxbr = Jp.int(o, "h.br") ?: Jp.int(o, "sq.br") ?: Jp.int(o, "l.br")
            Song(
                id = id,
                platform = Platform.NETEASE,
                title = Jp.str(o, "name") ?: return@mapNotNull null,
                artists = artists,
                album = albumName,
                albumId = Jp.long(o, "al.id")?.toString(),
                durationMs = Jp.long(o, "dt") ?: 0,
                coverUrl = Jp.str(o, "al.picUrl"),
                availableQualities = qualitiesForFee(fee, maxbr),
                vipFlag = fee == 1 || fee == 4,
                fingerprint = Crypt.fingerprint(Jp.str(o, "name").orEmpty(), artists.firstOrNull().orEmpty(), albumName),
            )
        }
        val total = Jp.int(root, "result.songCount") ?: songs.size
        return SearchPage(songs, (page * pageSize) < total, total)
    }

    private fun qualitiesForFee(fee: Int, maxbr: Int?): List<Quality> {
        val pool = mutableListOf(Quality.STANDARD)
        if (maxbr == null || maxbr >= 192000) pool += Quality.HIGH
        if (maxbr == null || maxbr >= 999000) pool += Quality.LOSSLESS
        if (maxbr != null && maxbr >= 1900000) pool += Quality.HIRES
        // 付费曲目在未登录时只落到标准档，登录后按账号实际权限返回
        return if (fee == 1 && !isLoggedIn()) listOf(Quality.STANDARD) else pool
    }

    /* ---------------- 详情 ---------------- */

    override suspend fun songDetail(id: String): Song? = runCatching {
        val body = eapi("/api/v3/song/detail", buildJsonObject { put("c", """[{"id":$id}]""") })
        if (body.isBlank()) return@runCatching null
        val o = Jp.objects(Jp.root(body), "songs").firstOrNull() ?: return@runCatching null
        val privileges = Jp.objects(Jp.root(body), "privileges").firstOrNull()
        val artists = Jp.objects(o, "ar").mapNotNull { Jp.str(it, "name") }
        Song(
            id = id,
            platform = Platform.NETEASE,
            title = Jp.str(o, "name") ?: "",
            artists = artists,
            album = Jp.str(o, "al.name") ?: "",
            albumId = Jp.long(o, "al.id")?.toString(),
            durationMs = Jp.long(o, "dt") ?: 0,
            coverUrl = Jp.str(o, "al.picUrl"),
            availableQualities = qualitiesForFee(
                privileges?.let { Jp.int(it, "fee") } ?: 0,
                privileges?.let { Jp.int(it, "maxbr") },
            ),
            vipFlag = (privileges?.let { Jp.int(it, "fee") } ?: 0) in listOf(1, 4),
            fingerprint = Crypt.fingerprint(
                Jp.str(o, "name").orEmpty(),
                artists.firstOrNull().orEmpty(),
                Jp.str(o, "al.name").orEmpty(),
            ),
        )
    }.getOrNull()

    /* ---------------- 歌词 ---------------- */

    override suspend fun lyric(id: String): Lyric = runCatching {
        val body = eapi(
            "/api/song/lyric",
            buildJsonObject {
                put("id", id); put("lv", -1); put("kv", -1); put("tv", -1); put("rv", -1)
            },
        )
        log("lyric id=$id len=${body.length}")
        if (body.isBlank()) return@runCatching Lyric(source = "netease")
        val root = Jp.root(body)
        val lrc = Jp.str(root, "lrc.lyric").orEmpty()
        val tlyric = Jp.str(root, "tlyric.lyric")?.takeIf { it.isNotBlank() && it != "null" }
        val roma = Jp.str(root, "romalrc.lyric")?.takeIf { it.isNotBlank() && it != "null" }
        if (lrc.isBlank()) {
            val yrcRaw = Jp.str(root, "yrc.lyric").orEmpty()
            val fromLrc = LrcParser.parse(yrcRaw)
            if (fromLrc.lines.isNotEmpty()) { log("lyric id=$id via yrc-lrc lines=${fromLrc.lines.size}"); return@runCatching fromLrc.copy(source = "netease") }
            val parsed = parseYrcText(yrcRaw)
            log("lyric id=$id via yrc lines=${parsed.size}")
            return@runCatching Lyric(lines = parsed, source = "netease")
        }
        val parsed = LrcParser.parse(lrc, tlyric, roma).copy(source = "netease")
        log("lyric id=$id lines=${parsed.lines.size} trans=${tlyric != null} roma=${roma != null}")
        parsed
    }.getOrElse { Lyric() }

    /** yrc 是 `[start,dur](t,d,d)字` 的逐字文本，这里只取每行首字的时间与整行文本。 */
    private fun parseYrcText(raw: String): List<LineLyric> {
        if (raw.isBlank()) return emptyList()
        val head = Regex("""^\[(\d+),(\d+)\](.*)$""")
        val word = Regex("""\(\d+,\d+,\d+\)""")
        return raw.lineSequence().mapNotNull { line ->
            val m = head.find(line.trim()) ?: return@mapNotNull null
            val t = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
            val text = word.replace(m.groupValues[3], "").trim()
            if (text.isBlank()) null else LineLyric(t, text)
        }.toList()
    }

    /* ---------------- 播放 ---------------- */

    /**
     * 网易失败形态宽松：未登录或无版权时可能 200 + 空 body，也可能 code=404 / url=null，
     * 所以逐档尝试，拿不到就返回 null 交给聚合器换源或降档。
     */
    override suspend fun playUrl(id: String, quality: Quality): PlayEndpoint? {
        val order = (Quality.entries.sortedByDescending { it.rank }.filter { it.rank <= quality.rank } +
            Quality.entries.sortedByDescending { it.rank }).distinct()
        for (q in order) {
            val ep = runCatching { fetchPlayUrl(id, q) }.getOrNull()
            if (ep != null) return ep
        }
        return null
    }

    private suspend fun fetchPlayUrl(id: String, quality: Quality): PlayEndpoint? {
        val body = eapi(
            "/api/song/enhance/player/url/v1",
            buildJsonObject {
                put("ids", "[$id]")
                put("level", quality.resParam())
                put("encodeType", if (quality.rank >= Quality.LOSSLESS.rank) "flac" else "mp3")
            },
        )
        if (body.isBlank()) return null
        val root = Jp.root(body) ?: return null
        val item = Jp.objects(root, "data").firstOrNull() ?: return null
        val url = Jp.str(item, "url")?.takeIf { it.startsWith("http") } ?: return null
        val type = Jp.str(item, "type") ?: "mp3"
        val br = Jp.long(item, "br") ?: 0
        val level = Jp.str(item, "level").orEmpty()
        val realQuality = when {
            type.contains("flac") && level.contains("hires") -> Quality.HIRES
            type.contains("flac") -> Quality.LOSSLESS
            br > 200_000 -> Quality.HIGH
            else -> Quality.STANDARD
        }
        val trial = Jp.root(Jp.str(item, "freeTrialInfo") ?: "null") != null
        log("play id=$id q=${quality.name} -> ${realQuality.name} br=$br trial=$trial type=$type")
        return PlayEndpoint(
            url = url,
            protocol = if (url.contains(".m3u8")) Protocol.HLS else Protocol.HTTP,
            headers = com.polytune.core.net.MediaHttpHeaders.forPlatform(Platform.NETEASE),
            quality = realQuality,
            expiresAt = System.currentTimeMillis() + 20 * 60 * 1000,
            sizeBytes = Jp.long(item, "size") ?: -1,
            preview = trial,
        )
    }

    private fun Quality.resParam(): String = when (this) {
        Quality.STANDARD -> "standard"
        Quality.HIGH -> "exhigh"
        Quality.LOSSLESS -> "lossless"
        Quality.HIRES -> "hires"
    }

    /* ---------------- 推荐 ---------------- */

    override suspend fun recommendBanners(): List<BannerItem> = runCatching {
        val body = eapi("/api/v2/banner/get", buildJsonObject { put("clientType", "pc") })
        Jp.objects(Jp.root(body), "banners").mapNotNull { o ->
            val img = Jp.str(o, "imageUrl") ?: Jp.str(o, "pic") ?: return@mapNotNull null
            BannerItem(
                id = Jp.long(o, "targetId")?.toString() ?: img.hashCode().toString(),
                imageUrl = img.replace("http://", "https://"),
                title = Jp.str(o, "typeTitle") ?: Jp.str(o, "title") ?: "",
                subtitle = Jp.str(o, "subTitle").orEmpty(),
                targetType = Jp.str(o, "targetType").orEmpty(),
                targetId = Jp.long(o, "targetId")?.toString().orEmpty(),
                platform = Platform.NETEASE,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 推荐歌单改走明文 /api/ 接口。eapi 版 /api/personalized/playlist 现在对匿名请求
     * 只回空（服务端改成要 csrf 与登录态），而明文接口照样开放，返回结构一模一样是
     * result[]，字段也没变，所以没必要为了它再折腾一套签名。
     */
    override suspend fun recommendPlaylists(): List<PlaylistBrief> = runCatching {
        val body = net.get(
            "https://music.163.com/api/personalized/playlist?limit=12&total=true",
            mapOf("Referer" to "https://music.163.com/"),
        )
        if (body.isBlank()) return@runCatching emptyList()
        Jp.objects(Jp.root(body), "result").mapNotNull { o ->
            PlaylistBrief(
                id = Jp.long(o, "id")?.toString() ?: return@mapNotNull null,
                name = Jp.str(o, "name").orEmpty(),
                coverUrl = Jp.str(o, "picUrl").orEmpty(),
                trackCount = Jp.int(o, "trackCount") ?: 0,
                playCount = Jp.long(o, "playCount") ?: 0,
                platform = Platform.NETEASE,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 歌单曲目。走 v6/playlist/detail，一次给全量 tracks（个人推荐歌单都在千首以内）。
     * tracks 里的字段和搜索结果的 songs 同构，但少了 privileges，所以档位只能按 song 自带的
     * h/sq/br 推断，拿不到的就留空——真正取直链时还会再逐档降级一次，不影响播放。
     *
     * 这里刻意用明文 /api/ 而不是 eapi：同一个接口两种口径返回结构一致，而 eapi 需要
     * 每次现算加密头，出问题时的失败面比明文大一倍；读接口不值得为它冒这个风险。
     */
    override suspend fun playlistDetail(id: String): List<Song> = runCatching {
        val body = net.get(
            "https://music.163.com/api/v6/playlist/detail?id=$id&n=1000&s=8",
            mapOf("Referer" to "https://music.163.com/"),
        )
        if (body.isBlank()) return@runCatching emptyList()
        val root = Jp.root(body)
        Jp.objects(root, "playlist.tracks").mapNotNull { o ->
            val sid = Jp.long(o, "id")?.toString() ?: return@mapNotNull null
            val title = Jp.str(o, "name") ?: return@mapNotNull null
            val artists = Jp.objects(o, "ar").mapNotNull { Jp.str(it, "name") }
            val album = Jp.str(o, "al.name") ?: ""
            val fee = Jp.int(o, "fee") ?: 0
            val maxbr = Jp.int(o, "h.br") ?: Jp.int(o, "sq.br") ?: Jp.int(o, "l.br")
            Song(
                id = sid,
                platform = Platform.NETEASE,
                title = title,
                artists = artists,
                album = album,
                albumId = Jp.long(o, "al.id")?.toString(),
                durationMs = Jp.long(o, "dt") ?: 0,
                coverUrl = Jp.str(o, "al.picUrl"),
                availableQualities = qualitiesForFee(fee, maxbr),
                vipFlag = fee == 1 || fee == 4,
                fingerprint = Crypt.fingerprint(title, artists.firstOrNull().orEmpty(), album),
            )
        }
    }.getOrElse {
        log("playlist detail fail id=$id ${it.javaClass.simpleName}: ${it.message}")
        emptyList()
    }
}
