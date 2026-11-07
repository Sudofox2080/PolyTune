package com.polytune.source.qq

import com.polytune.core.crypto.Crypt
import com.polytune.core.net.SourceHttp
import com.polytune.core.util.Jp
import com.polytune.core.util.LrcParser
import com.polytune.source.api.BannerItem
import com.polytune.source.api.BaseMusicSource
import com.polytune.source.api.Credential
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

/**
 * QQ 音乐源。
 *
 * 搜索走 soso 的 client_search_cp（明文 JSON，未登录可用）。
 * musicu.fcg 的 music.search.SearchCgiService.DoSearchForQQMusicDesktop 在当前线上环境
 * 对普通关键词返回 sum=0 的空列表（HTTP 200、code 0），仅作详情/播放/歌词的通道。
 * 这里刻意把 comm 精简成 {uin, format, ct, cv} 四个键：实测把 inCharset / platform /
 * needNewCode 一并塞进去会让服务端把 meta.query 回成 "??"，列表空但 HTTP 200 code 0，
 * 属于最难排查的静默失败。
 *
 * 播放文件名是「前缀 + songId + songId + 后缀」，songId 要重复两遍而不是 songmid；
 * 未登录一律 purl 为空，只能退到同名试听样本（testfile2g）。
 */
class QqSource(net: SourceHttp) : BaseMusicSource(Platform.QQ, net) {

    private val musicu = "https://u.y.qq.com/cgi-bin/musicu.fcg"
    private val lyricHost = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg"
    private val QQ_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    /* ---------------- 登录 ---------------- */

    override suspend fun login(method: LoginMethod): LoginResult = when (method) {
        is LoginMethod.CookieImport -> cookieLogin(method.cookie)
        is LoginMethod.TokenInject -> tokenLogin(method.token, method.uid)
        is LoginMethod.PhonePassword -> passwordLogin(method.phone, method.password)
        is LoginMethod.SmsCode -> officialSmsLogin(method.phone, method.code)
        is LoginMethod.EmailPassword -> LoginResult.Fail("QQ音乐没有邮箱登录")
        is LoginMethod.QrCode -> LoginResult.Fail("QQ 二维码需要 ptlogin2 授权，请用手机验证码或网页登录")
    }

    /**
     * 登录方式。
     *
     * 手机验证码走的是官方 `music.login.LoginServer`（见 [QqLoginApi]），这是唯一一条
     * 能真正换到 musickey 的免客户端通道；网页登录由 WebView 承接；
     * Cookie 粘贴留给已经手上有票据的人。
     * 钱密码（PhonePassword）与邮箱都不再暴露：ptlogin2 主站对 QQ 音乐的资源一律回
     * `ptuiCB('23001')`、带 daid=383 直接 403，走不通的东西不该挂在界面上。
     */
    override fun supportedMethods(): Set<LoginKind> = setOf(
        LoginKind.PhoneSms, LoginKind.Web, LoginKind.Cookie,
    )

    /** 发短信：先走官方发码接口，若服务端要求人机验证就把 securityURL 交回界面。 */
    override suspend fun sendSmsCode(phone: String): LoginResult = runCatching {
        val sent = QqLoginApi.sendAuthCode({ url, body -> net.postJson(url, body, musicuHeaders()) }, phone)
        log("sms send ${QqLoginApi.describe(sent)}")
        when {
            sent.error != null -> LoginResult.Fail(sent.error)
            sent.needSecurity && sent.securityUrl != null ->
                LoginResult.Pending(
                    ticket = sent.securityUrl,
                    message = "需要先完成一次人机验证",
                )
            else -> LoginResult.Pending("", message = "验证码已发送到 $phone")
        }
    }.getOrElse { LoginResult.Fail("发送失败：${it.message}") }

    /** 官方 LoginServer 换票据。 */
    private suspend fun officialSmsLogin(phone: String, code: String): LoginResult = runCatching {
        val (result, raw) = QqLoginApi.login(
            post = { url, body -> net.postJson(url, body, musicuHeaders()) },
            get = { url -> net.get(url, musicuHeaders()) },
            phone = phone,
            code = code.trim(),
        )
        log("sms login phone=${phone.take(3)}**** resp=${raw.take(900)}")
        if (result is LoginResult.Success) {
            setCredential(result.credential)
            runCatching {
                val info = userInfo()
                val nick = info?.let {
                    Jp.str(Jp.root(it), "req_0.data.nick") ?: Jp.str(Jp.root(it), "req_0.data.nickname")
                }
                val head = info?.let {
                    Jp.str(Jp.root(it), "req_0.data.headpic") ?: Jp.str(Jp.root(it), "req_0.data.avatarUrl")
                }
                if (nick != null || head != null) {
                    val c = result.credential.copy(
                        nickname = nick ?: result.credential.nickname,
                        avatar = head ?: result.credential.avatar,
                    )
                    setCredential(c)
                    LoginResult.Success(c)
                } else result
            }.getOrElse { result }
        } else result
    }.getOrElse { LoginResult.Fail("登录失败：${it.message}") }

    private fun musicuHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://y.qq.com/",
        "User-Agent" to "QQMusic 20090008(android 14)",
        "Content-Type" to "application/json",
    )

    /**
     * 手机号 + 密码。QQ 音乐 Web 登录走 ptlogin2，返回的是一个 302 到
     * 「登录成功跳转页」的重定向，真正的票据在 Set-Cookie 里的 p_skey / skey，
     * 拿到后拼成 qqmusic_key 就能当凭证用。
     *
     * 密码摘要的算法是 sha1(明文).upper() 后面直接拼手机号，**不是**手机号的十六进制。
     * 早前把它写成 phoneToHex 是个错误命名，实测 ptlogin2 会直接回「密码错误」。
     * 另外 ptlogin2 对未带登录态的设备几乎必然先要图形验证码，纯接口登录在这个
     * 环境下成功率极低，所以失败时把话说明白，不要让用户反复试。
     */
    /**
     * 手机号 + 密码。ptlogin2 是两段握手：
     * 第一次不带 verifycode 打过去，服务端认为设备可疑就回 ptuiCB('1',...,'https://ptlogin2.qq.com/verify?...')，
     * 那个地址就是图形验证码本体。这里把图地址原样交回界面，用户在 App 里填完后调
     * [loginWithCaptcha] 再打一次（带 pt_vcode_v1=1 与 pt_verifysession_v1）。
     * 只有确实不需要验证码时才会直接拿到 code=0 的票据。
     */
    private suspend fun passwordLogin(phone: String, password: String): LoginResult = runCatching {
        val pwd = sha1(password).uppercase() + phone
        val body = net.get(ptLoginUrl(phone, pwd, "0", ""), ptLoginHeaders())
        log("password login phone=${phone.take(3)}**** resp=${body.take(200)}")
        parsePtuiCallback(body)
    }.getOrElse { LoginResult.Fail("登陆失败：${it.message}") }

    /**
     * 逼服务端下发图形验证码，把它下载成字节交给界面直接渲染。
     *
     * 关键点是不能自己找验证码图片接口——那需要 cap_cd 与 pt_verifysession，
     * 二者都是这次「故意失败」的响应里才带出来的。所以先用空 verifycode 打一次 /login，
     * 从 ptuiCB 的第三段取图地址，再 getBytes 把图拉回来。
     */
    suspend fun fetchCaptcha(phone: String, password: String): CaptchaChallenge? = runCatching {
        val pwd = sha1(password).uppercase() + phone
        val body = net.get(ptLoginUrl(phone, pwd, "0", ""), ptLoginHeaders())
        log("captcha probe phone=${phone.take(3)}**** resp=${body.take(240)}")
        val f = parsePtuiFields(body) ?: return@runCatching null
        if (f.code != 1) {
            // code=0 说明这一轮根本不需要验证码；别的码留着给调用方显示原因
            log("captcha probe code=${f.code} msg=${f.message}")
            return@runCatching null
        }
        val capCd = Regex("[?&]cap_cd=([^&]+)").find(f.url)?.groupValues?.get(1).orEmpty()
        val session = Regex("(?:^|;\\s*)(?:pt_verifysession|verifysession)=([^;]+)")
            .find(net.cookieString("ptlogin2.qq.com"))?.groupValues?.get(1).orEmpty()
        val bytes = runCatching { net.getBytes(f.url, ptLoginHeaders()) }.getOrNull()
        log(
            "captcha got url=${f.url.take(120)} capCd=${capCd.take(8)} session=${session.take(6)} " +
                "bytes=${bytes?.size ?: 0}",
        )
        if (bytes == null || bytes.isEmpty()) return@runCatching null
        CaptchaChallenge(
            imageUrl = f.url,
            capCd = capCd,
            verifySession = session,
            imageBytes = bytes,
            phone = phone,
            password = pwd,
        )
    }.getOrElse {
        log("captcha fail ${it.javaClass.simpleName}: ${it.message}")
        null
    }

    /** 用户填完图形验证码后的第二次握手。 */
    suspend fun loginWithCaptcha(phone: String, password: String, code: String): LoginResult = runCatching {
        val pwd = sha1(password).uppercase() + phone
        val body = net.get(ptLoginUrl(phone, pwd, "1", code.trim()), ptLoginHeaders())
        log("captcha login phone=${phone.take(3)}**** code=${code.trim()} resp=${body.take(200)}")
        val f = parsePtuiFields(body)
        if (f != null && f.code == 1) {
            return LoginResult.Fail("验证码不对，重新输一次或点图刷新")
        }
        parsePtuiCallback(body)
    }.getOrElse { LoginResult.Fail("登陆失败：${it.message}") }

    private fun ptLoginHeaders(): Map<String, String> = mapOf(
        "Referer" to "https://xui.ptlogin2.qq.com/",
        "User-Agent" to QQ_UA,
    )

    /**
     * 拼 ptlogin2 /login 的查询串。
     * [vcodeOn] 为 "1" 表示这一轮带图形验证码；cap_cd 与 pt_verifysession_v1 留空也能过，
     * 因为服务端认的是 CookieJar 里那次握手留下的会话。
     */
    private fun ptLoginUrl(phone: String, md5Pwd: String, vcodeOn: String, code: String): String =
        "https://ptlogin2.qq.com/login?" + listOf(
            "u" to phone,
            "p" to md5Pwd,
            "verifycode" to code,
            "pt_vcode_v1" to vcodeOn,
            "pt_verifysession_v1" to "",
            "pt_randsalt" to "0",
            "u1" to "https://y.qq.com/portal/player.html",
            "ptredirect" to "1",
            "h" to "1",
            "t" to "1",
            "g" to "1",
            "from_ui" to "1",
            "ptlang" to "2052",
            // daid=383 会让 ptlogin2 直接回 403（无 body），去掉它才拿得到 ptuiCB 响应；
            // 实测 aid=716027609/1006101 只能得到 23001「该应用未获得QQ登录权限」，
            // 说明 QQ 音乐没有向 ptlogin2 主站开放密码登录，图形验证码分支永远不会到达。
            // 保留这一行会连错误原因都吞掉，所以移除。
            "aid" to "716027609",
            "pt_3rd_aid" to "100497308",
            "pttype" to "1",
            "dname" to "",
            "ptui_loginuin" to phone,
            "js_ver" to "22022610",
            "js_type" to "1",
            "login_sig" to (Regex("(?:^|;\\s*)pt_login_sig=([^;]+)")
                .find(net.cookieString("ptlogin2.qq.com"))?.groupValues?.get(1).orEmpty()),
            "pt_uistyle" to "40",
        ).joinToString("&") { "${it.first}=${enc(it.second)}" }

    /** 把 ptuiCB('code','sub','url','0','msg','nick') 拆成结构化字段。 */
    private fun parsePtuiFields(body: String): PtuiFields? {
        val m = Regex("ptuiCB\\(([^)]*)\\)").find(body) ?: return null
        val parts = m.groupValues[1].split(",").map { it.trim().trim('\'', '"') }
        return PtuiFields(
            code = parts.getOrNull(0)?.toIntOrNull() ?: -1,
            url = parts.getOrNull(2).orEmpty(),
            message = parts.getOrNull(4).orEmpty(),
            nick = parts.getOrNull(5).orEmpty(),
        )
    }

    /**
     * 旧的 ptlogin2 发码通道。线上对 QQ 音乐只会回「需要图形验证」，
     * 保留成私有方法仅作诊断对照，不再对外暴露。
     */
    @Suppress("unused")
    private suspend fun legacySendSms(phone: String): LoginResult = runCatching {
        val url = "https://ssl.ptlogin2.qq.com/sendsms?uin=${enc(phone)}&aid=1006101" +
            "&auth_token=&mobile=${enc(phone)}&area=86&ptlang=2052"
        val body = net.get(url, mapOf("Referer" to "https://xui.ptlogin2.qq.com/"))
        val ret = Regex("\\(\\s*(\\d+)\\s*,\\s*\"([^\"]*)\"").find(body)
        val code = ret?.groupValues?.get(1)?.toIntOrNull()
        val msg = ret?.groupValues?.get(2).orEmpty()
        if (code == 0 || body.contains("短信已发送") || body.isBlank()) {
            LoginResult.Pending("", message = "验证码已发送到 $phone")
        } else {
            LoginResult.Fail(msg.ifBlank { "验证码发送失败（可能需要图形验证）" })
        }
    }.getOrElse { LoginResult.Fail("发送失败：${it.message}") }

    /** ptlogin2 的回调形如 ptuiCB('0','130','https://...','0','登录成功','昵称'); */
    private suspend fun parsePtuiCallback(body: String): LoginResult {
        val f = parsePtuiFields(body) ?: return LoginResult.Fail("登录响应无法解析")
        if (f.code != 0) {
            // code=1 是「要图形验证码」，把地址带回去，账号页能直接显示
            if (f.code == 1 && f.url.startsWith("http")) {
                return LoginResult.Fail("captcha:${f.url}")
            }
            return LoginResult.Fail(f.message.ifBlank { "账号或验证码不正确" })
        }
        val nick = f.nick
        // 回调只是门票，真正的票据要再请求一次 u1，让服务端把 skey 写进 cookie。
        val u1 = f.url
        if (u1.isNotBlank()) runCatching { net.get(u1, mapOf("Referer" to "https://y.qq.com/")) }
        val jar = net.cookieString("qq.com")
        val key = Regex("(?:qqmusic_key|qm_keyst|p_skey|skey)=([^;]+)").find(jar)?.groupValues?.get(1)
            ?: return LoginResult.Fail("登录成功但没有拿到票据，请改用二维码")
        return tokenLogin(key, phone0(jar), jar).let {
            if (it is LoginResult.Success && nick.isNotBlank()) {
                val c = it.credential.copy(nickname = nick)
                setCredential(c)
                LoginResult.Success(c)
            } else it
        }
    }

    private fun phone0(jar: String): String =
        Regex("(?:^|;\\s*)(?:uin|p_uin|ptui_loginuin)=o?(\\d+)").find(jar)?.groupValues?.get(1) ?: "0"

    /** 登录链路唯一可靠的日志出口：小米这类 ROM 会吞掉自定义 tag 的 logcat。 */
    private fun log(msg: String) {
        com.polytune.core.download.TraceCtx.app?.let {
            com.polytune.core.download.DownloadTrace.log(it, "qq login | $msg")
        }
    }

    private fun sha1(s: String): String = Crypt.sha1(s)

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    private suspend fun cookieLogin(cookie: String): LoginResult {
        val raw = cookie.trim().removePrefix("Cookie:").trim()
        val key = Regex("(?:qqmusic_key|qm_keyst)=([^;]+)").find(raw)?.groupValues?.get(1)
            ?: return LoginResult.Fail(
                "cookie 里没有 qqmusic_key。这条 cookie 只有 ${raw.split(';').size} 个键：" +
                    raw.split(';').mapNotNull { it.substringBefore('=', "").trim().ifBlank { null } }
                        .take(12).joinToString("|") +
                    "。请先在网页里点右上角头像完成登录，再回来读取。"
            )
        val uin = Regex("(?:^|;\\s*)(?:uin|wxuin|p_uin)=o?(\\d+)").find(raw)?.groupValues?.get(1).orEmpty()
        log("cookie login keys=${raw.split(';').size} hasKey=true uin=${uin.ifBlank { "空" }}")
        return tokenLogin(key, uin.ifBlank { "0" }, raw)
    }

    private suspend fun tokenLogin(token: String, uin: String, rawCookie: String? = null): LoginResult = runCatching {
        val cookie = rawCookie ?: "qqmusic_key=$token; uin=$uin"
        val cred = Credential(
            platform = Platform.QQ,
            uid = uin,
            token = token,
            extra = mapOf("cookie" to cookie),
        )
        setCredential(cred)
        val info = runCatching { userInfo() }.getOrNull()
        val nick = info?.let { Jp.str(Jp.root(it), "req_0.data.nick") ?: Jp.str(Jp.root(it), "req_0.data.nickname") }
        val head = info?.let { Jp.str(Jp.root(it), "req_0.data.headpic") ?: Jp.str(Jp.root(it), "req_0.data.avatarUrl") }
        log("token login uin=$uin nick=${nick ?: "空"} info=${(info ?: "").take(160)}")
        // 票据已经拿到就算登录成功，昵称只是展示用。
        // 早前把「拿不到昵称」判成失败，会让已经可用的账号在界面上显示未登录。
        val final = cred.copy(nickname = nick ?: "QQ音乐用户", avatar = head.orEmpty())
        setCredential(final)
        LoginResult.Success(final)
    }.getOrElse { LoginResult.Fail("登陆校验失败：${it.message}") }

    private suspend fun userInfo(): String {
        val payload = "{\"comm\":${comm()},\"req_0\":{\"module\":\"music.UserInfo.userInfo\"," +
            "\"method\":\"GetLoginUserInfo\"}}"
        return net.postJson(musicu, payload, mapOf("Referer" to "https://y.qq.com/"))
    }

    /** 精简 comm：只保留实测能稳定出结果的四个键。 */
    private fun comm(): String {
        val uin = credential.value?.uid ?: "0"
        return "{\"uin\":\"$uin\",\"format\":\"json\",\"ct\":24,\"cv\":0}"
    }

    /**
     * 已登录时的 comm。
     *
     * 关键点是 ct 必须跟着票据的来源走：手机验证码登录拿回来的是 **Android 端**的
     * musickey（LoginServer 的 comm 是 ct=11 cv=20090008），播放/详情接口也要用同一套
     * ct=11/cv=20090008 才会认它。之前这里填的是网页端 ct=24 却塞 Android 票据，
     * 服务端把请求当成未知来源，CgiGetVkey 一律只回 testfile2g，看上去就像「登录了也没用」。
     */
    private fun commWithAuth(): String {
        val c = credential.value ?: return comm()
        if (c.token.isBlank()) return comm()
        val loginType = c.extra["loginType"] ?: "1"
        val dev = QqLoginApi.deviceId
        return "{\"ct\":\"11\",\"cv\":\"20090008\",\"v\":\"20090008\"," +
            "\"chid\":\"10003505\",\"tmeAppID\":\"qqmusic\"," +
            "\"qq\":\"${c.uid}\",\"uin\":\"${c.uid}\",\"authst\":\"${c.token}\"," +
            "\"tmeLoginType\":\"$loginType\",\"tmeLoginMethod\":\"3\"," +
            "\"traceid\":\"10002_${c.uid}_${System.currentTimeMillis() / 1000}\"," +
            "\"OpenUDID\":\"$dev\",\"udid\":\"$dev\"," +
            "\"os_ver\":\"14\",\"phonetype\":\"PolyTune\",\"format\":\"json\"}"
    }

    /* ---------------- 搜索 ---------------- */

    /**
     * 搜索走 soso 的 client_search_cp：实测在未登录、无签名、无 cookie 的情况下直接返回明文 JSON，
     * 是新版 musicu DoSearchForQQMusicDesktop（同期对同样的关键词返回 sum=0 空列表）之外唯一稳定的入口。
     * 首选用 new_json=1 的富结构（file 里有四档 size，能算出真实音质池），失败再退回老结构。
     */
    override suspend fun search(keyword: String, page: Int, pageSize: Int): SearchPage = runCatching {
        val rich = runCatching { sosoSearch(keyword, page, pageSize, rich = true) }.getOrDefault(SearchPage())
        if (rich.songs.isNotEmpty()) return@runCatching rich
        sosoSearch(keyword, page, pageSize, rich = false)
    }.getOrElse { SearchPage() }

    private suspend fun sosoSearch(keyword: String, page: Int, pageSize: Int, rich: Boolean): SearchPage {
        val url = "https://c.y.qq.com/soso/fcgi-bin/client_search_cp?format=json&n=$pageSize&p=$page" +
            "&w=${enc(keyword)}&new_json=1&cr=1&t=0&aggr=1&lossless=1&catZhida=1&platform=yqq.json"
        val raw = net.get(url, mapOf("Referer" to "https://y.qq.com/portal/player.html"))
        val root = Jp.root(stripCallback(raw))
        val items = Jp.objects(root, "data.song.list")
        val songs = items.mapNotNull { el ->
            val title = Jp.str(el, "title") ?: Jp.str(el, "songname") ?: Jp.str(el, "name")
                ?: return@mapNotNull null
            val mid = Jp.str(el, "mid") ?: Jp.str(el, "songmid") ?: return@mapNotNull null
            val singers = if (rich) {
                Jp.objects(el, "singer").mapNotNull { Jp.str(it, "name") }.ifEmpty { splitSinger(Jp.str(el, "singer").orEmpty()) }
            } else {
                splitSinger(Jp.str(el, "singer").orEmpty())
            }
            val album = Jp.str(el, "album.name") ?: Jp.str(el, "albumname") ?: ""
            val albumMid = Jp.str(el, "album.mid") ?: Jp.str(el, "albummid")
            val pay = Jp.int(el, "pay.pay_play") ?: (if (Jp.bool(el, "isonly") == true) 1 else 0)
            Song(
                id = mid,
                platform = Platform.QQ,
                title = title,
                artists = singers,
                album = album,
                albumId = Jp.long(el, "album.id")?.toString() ?: Jp.long(el, "albumid")?.toString(),
                durationMs = (Jp.long(el, "interval") ?: 0) * 1000,
                coverUrl = albumCover(albumMid),
                availableQualities = qqQualitiesFromFile(el, pay),
                vipFlag = pay > 0,
                fingerprint = Crypt.fingerprint(title, singers.firstOrNull().orEmpty(), album),
            )
        }
        val total = Jp.int(root, "data.song.totalnum") ?: songs.size
        return SearchPage(songs, page * pageSize < total, total)
    }

    private fun splitSinger(s: String): List<String> =
        s.split('、', '&', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun enc2(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /**
     * 音质池按 file.size_* 是否存在推断（size 为 0 表示该档位没有资源），
     * 未登录时 VVIP/Hi-Res 不进池，避免点了必然降级。
     */
    private fun qqQualitiesFromFile(el: kotlinx.serialization.json.JsonElement, payPlay: Int): List<Quality> {
        val sizes = mapOf(
            Quality.STANDARD to (Jp.long(el, "file.size_128mp3") ?: Jp.long(el, "file.size_128") ?: 0L),
            Quality.HIGH to (Jp.long(el, "file.size_320mp3") ?: Jp.long(el, "file.size_320") ?: 0L),
            Quality.LOSSLESS to (Jp.long(el, "file.size_flac") ?: 0L),
            Quality.HIRES to (Jp.long(el, "file.size_hires") ?: 0L),
        )
        val out = Quality.entries.filter { q ->
            val size = sizes[q] ?: 0L
            val allowedByAccount = q.rank <= Quality.HIGH.rank || isLoggedIn() || payPlay == 0
            (size > 0 || q == Quality.STANDARD) && allowedByAccount
        }
        return out.ifEmpty { listOf(Quality.STANDARD) }
    }

    /** 老接口没有 file 字段时的兜底：按付费标记给一个保守的音质池。 */
    private fun qqQualities(payPlay: Int): List<Quality> {
        val base = mutableListOf(Quality.STANDARD)
        if (payPlay == 0 || isLoggedIn()) base += Quality.HIGH
        if (isLoggedIn()) {
            base += Quality.LOSSLESS
            if (payPlay == 0) base += Quality.HIRES
        }
        return base
    }

    fun albumCover(albumMid: String?): String? {
        if (albumMid.isNullOrBlank()) return null
        return "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    /* ---------------- 详情 ---------------- */

    override suspend fun songDetail(id: String): Song? = runCatching {
        val payload = "{\"comm\":${comm()},\"req_0\":{\"module\":\"music.pf_song_detail_svr\"," +
            "\"method\":\"get_song_detail_yqq\",\"param\":{\"song_type\":0,\"song_mid\":\"$id\"}}}"
        val body = net.postJson(musicu, payload, mapOf("Referer" to "https://y.qq.com/"))
        val root = Jp.root(body)
        val track = Jp.get(root, "req_0.data.track_info") ?: return@runCatching null
        val title = Jp.str(track, "name") ?: return@runCatching null
        val singers = Jp.objects(track, "singer").mapNotNull { Jp.str(it, "name") }
        val album = Jp.str(track, "album.name") ?: ""
        val pay = Jp.int(track, "pay.pay_play") ?: 0
        Song(
            id = id,
            platform = Platform.QQ,
            title = title,
            artists = singers,
            album = album,
            albumId = Jp.long(track, "album.id")?.toString(),
            durationMs = (Jp.long(track, "interval") ?: 0) * 1000,
            coverUrl = albumCover(Jp.str(track, "album.mid")),
            availableQualities = qqQualities(pay),
            vipFlag = pay > 0,
            fingerprint = Crypt.fingerprint(title, singers.firstOrNull().orEmpty(), album),
        )
    }.getOrNull()

    /* ---------------- 歌词 ---------------- */

    /**
     * 用老接口并带 nobase64=1，直接拿明文 LRC，省掉一层解密；
     * 翻译与罗马音在新版 PlayLyricInfo 里，未登录返回空，所以这里以原文为主。
     */
    override suspend fun lyric(id: String): Lyric = runCatching {
        val url = "$lyricHost?songmid=$id&format=json&nobase64=1&g_tk=5381&loginUin=${credential.value?.uid ?: 0}"
        val body = net.get(url, mapOf("Referer" to "https://y.qq.com/portal/player.html"))
        val root = Jp.root(stripCallback(body))
        val lrc = Jp.str(root, "lyric")?.let { decodeMaybeBase64(it) }.orEmpty()
        val trans = Jp.str(root, "trans")?.let { decodeMaybeBase64(it) }
        val roma = Jp.str(root, "roma")?.let { decodeMaybeBase64(it) }
        if (lrc.isBlank()) return@runCatching Lyric(instrumental = true, source = "qq")
        LrcParser.parse(lrc, trans, roma).copy(source = "qq")
    }.getOrElse { Lyric() }

    private fun stripCallback(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("{")) t else t.substringAfter('(', "").substringBeforeLast(')').trim()
    }

    private fun decodeMaybeBase64(s: String): String = runCatching {
        if (s.startsWith("W3") || s.startsWith("Wz")) Crypt.base64Decode(s).toString(Charsets.UTF_8) else s
    }.getOrDefault(s)

    /* ---------------- 播放 ---------------- */

    override suspend fun playUrl(id: String, quality: Quality): PlayEndpoint? = runCatching {
        val (prefix, ext) = prefixFor(quality)
        val guid = (System.currentTimeMillis() % 10000000000L).toString()
        // 文件名里 id 要连写两遍
        val filename = "$prefix${id}${id}$ext"
        val payload = "{\"comm\":${commWithAuth()},\"req_0\":{\"module\":\"vkey.GetVkeyServer\"," +
            "\"method\":\"CgiGetVkey\",\"param\":{\"guid\":\"$guid\",\"songmid\":[\"$id\"],\"songtype\":[0]," +
            "\"uin\":\"${credential.value?.uid ?: "0"}\",\"loginflag\":${if (isLoggedIn()) 1 else 0}," +
            "\"platform\":\"20\",\"filename\":[\"$filename\"]}}}"
        val body = net.postJson(musicu, payload, mapOf("Referer" to "https://y.qq.com/"))
        com.polytune.core.download.TraceCtx.app?.let { c ->
            com.polytune.core.download.DownloadTrace.log(
                c,
                "qq play id=$id q=${quality.name} logged=${isLoggedIn()} body=${body.take(400).replace('\n', ' ')}",
            )
        }
        val root = Jp.root(body)
        val purl = Jp.str(root, "req_0.data.midurlinfo[0].purl").orEmpty()
        // 登录了也拿不到 purl 时，服务端给的是 result=104003（这首歌对你的账号无播放权）。
        // 这时别把登录态当成「能放全曲」去猜，直接返回 null，让聚合器换到别家源或退到试听档。
        val resultCode = Jp.int(root, "req_0.data.midurlinfo[0].result")
        if (purl.isBlank() && resultCode != null && resultCode != 0) {
            com.polytune.core.download.TraceCtx.app?.let { c ->
                com.polytune.core.download.DownloadTrace.log(
                    c,
                    "qq play denied id=$id q=${quality.name} result=$resultCode",
                )
            }
            null
        } else if (purl.isNotBlank()) {
            val sip = Jp.array(root, "req_0.data.sip")
                .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                .firstOrNull { it.startsWith("http") }
                ?: "https://ws.stream.qqmusic.qq.com/"
            return@runCatching PlayEndpoint(
                url = sip + purl,
                protocol = if (purl.contains(".m3u8")) Protocol.HLS else Protocol.HTTP,
                headers = mapOf("Referer" to "https://y.qq.com/"),
                quality = quality,
                expiresAt = System.currentTimeMillis() + 6 * 60 * 60 * 1000,
                sizeBytes = Jp.long(root, "req_0.data.midurlinfo[0].size") ?: -1,
            )
        }
        // 未登录 / 无版权时 QQ 只下发 testfile2g 试听样本（C400xxx.m4a）。早期把它直接丢掉，
        // 结果是一首 VIP 曲在三个源上都拿不到直链时用户什么也听不到。现在把它作为
        // preview 端点交回去：Aggregator 第一轮只认正式曲目，全部落空后才退到它，
        // 界面据此显示「试听片段」。file:// 与 https:// 两种前缀都拼一遍，取第一个能用的。
        // testfile2g 实测是占位响应而不是可播放的试听：pos=0 回 200 只给 100 字节，
        // pos=48 回 206 只给 52 字节，随后 EOF。拿它当预览端点只会让播放器停在 0:00，
        // 反倒挡掉了聚合器去别家源找全曲的退路，所以保持返回 null。
        null
    }.getOrNull()

    private fun prefixFor(q: Quality): Pair<String, String> = when {
        q.rank >= Quality.HIRES.rank -> "RS01" to ".flac"
        q.rank >= Quality.LOSSLESS.rank -> "F000" to ".flac"
        q.rank >= Quality.HIGH.rank -> "M800" to ".mp3"
        else -> "M500" to ".mp3"
    }

    /* ---------------- 推荐 ---------------- */

    override suspend fun recommendBanners(): List<BannerItem> = runCatching {
        val payload = "{\"comm\":${comm()},\"req_1\":{\"module\":\"music.playlist.PlayListInfoServer\"," +
            "\"method\":\"GetBanner\",\"param\":{\"banner_type\":1}}}"
        val body = net.postJson(musicu, payload, mapOf("Referer" to "https://y.qq.com/"))
        val items = Jp.objects(Jp.root(body), "req_1.data.list").ifEmpty { Jp.get(Jp.root(body), "req_1.data.v_hot")?.let { emptyList() } ?: emptyList() }
        items.mapNotNull { o ->
            val img = Jp.str(o, "picurl") ?: Jp.str(o, "pic_url") ?: Jp.str(o, "cover") ?: return@mapNotNull null
            BannerItem(
                id = Jp.str(o, "id") ?: Jp.str(o, "content_id") ?: img.takeLast(24),
                imageUrl = img.replace("http://", "https://"),
                title = Jp.str(o, "title").orEmpty(),
                subtitle = Jp.str(o, "subtitle").orEmpty(),
                targetId = Jp.str(o, "jump_id") ?: Jp.str(o, "content_id").orEmpty(),
                platform = Platform.QQ,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 当前 QQ 账号自建/收藏的歌单。接口是网页侧的 fcg_user_created_diss：
     * 只需要 hostuin，不要求 musicu 的登录态，匿名也能拿到公开歌单，
     * 登录后带上的 cookie 会让私有歌单一起出现；这里不做任何额外鉴权绕过。
     */
    override suspend fun myPlaylists(): List<PlaylistBrief> = runCatching {
        if (!isLoggedIn()) return@runCatching emptyList()
        val uin = credential.value?.uid.orEmpty()
        if (uin.isBlank()) return@runCatching emptyList()
        val url = "https://c.y.qq.com/rsc/fcgi-bin/fcg_user_created_diss" +
            "?hostuin=$uin&sin=0&size=200&format=json"
        val body = net.get(url, mapOf("Referer" to "https://y.qq.com/"))
        if (body.isBlank()) return@runCatching emptyList()
        val root = Jp.root(stripCallback(body))
        Jp.objects(root, "data.disslist").mapNotNull { o ->
            val id = Jp.str(o, "dissid") ?: return@mapNotNull null
            PlaylistBrief(
                id = id,
                name = Jp.str(o, "dissname").orEmpty(),
                coverUrl = Jp.str(o, "disspic").orEmpty(),
                trackCount = Jp.int(o, "song_cnt") ?: Jp.int(o, "songnum") ?: 0,
                playCount = Jp.long(o, "listennum") ?: 0,
                platform = Platform.QQ,
                owned = true,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 推荐歌单走网页的分类歌单接口，不用 musicu 的 get_hot_recommend：
     * 后者现在匿名调用直接回 500003（要登录态），而分类歌单列表对匿名完全开放，
     * 正好是「发现」页要的东西。sortId=5 是按热度排。
     */
    override suspend fun recommendPlaylists(): List<PlaylistBrief> = runCatching {
        val url = "https://c.y.qq.com/splcloud/fcgi-bin/fcg_get_diss_by_tag.fcg" +
            "?picmid=1&rnd=0.1&g_tk=5381&loginUin=0&hostUin=0&format=json&inCharset=utf8" +
            "&outCharset=utf-8&notice=0&platform=yqq.json&needNewCode=0" +
            "&categoryId=10000000&sortId=5&sin=0&ein=19"
        val body = net.get(url, mapOf("Referer" to "https://y.qq.com/"))
        if (body.isBlank()) return@runCatching emptyList()
        Jp.objects(Jp.root(body), "data.list").mapNotNull { o ->
            val dissId = Jp.str(o, "dissid") ?: return@mapNotNull null
            PlaylistBrief(
                id = dissId,
                name = Jp.str(o, "dissname").orEmpty(),
                coverUrl = Jp.str(o, "imgurl")?.replace("http://", "https://").orEmpty(),
                // 这个接口只给歌单信息不给曲目数，宁可不显示也不编一个假数字。
                trackCount = Jp.int(o, "song_count") ?: 0,
                playCount = Jp.long(o, "listennum") ?: 0,
                platform = Platform.QQ,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 歌单曲目走 musicu 的 music.srfDissInfo.DissInfo / CgiGetDiss。
     * 网页那套 fcg_ucc_getcdinfo 现在对匿名请求回 `{"code":0,"subcode":4000,"msg":"check privacy error!"}`，
     * 拿不到 songlist；musicu 这个模块匿名就能分页取，一次 100 条，dirinfo 里带歌单总曲目数。
     */
    override suspend fun playlistDetail(id: String): List<Song> = runCatching {
        val out = mutableListOf<Song>()
        var begin = 0
        val page = 100
        while (out.size < 1000) {
            val payload = "{\"comm\":${comm()},\"req_0\":{\"module\":\"music.srfDissInfo.DissInfo\"," +
                "\"method\":\"CgiGetDiss\",\"param\":{\"disstid\":$id,\"dirid\":0,\"tag\":1,\"userinfo\":0," +
                "\"song_begin\":$begin,\"song_num\":$page,\"orderlist\":1,\"enc_host_uin\":\"\"}}}"
            val body = net.postJson(musicu, payload, mapOf("Referer" to "https://y.qq.com/"))
            val root = Jp.root(body)
            val chunk = Jp.objects(root, "req_0.data.songlist")
            if (chunk.isEmpty()) break
            chunk.forEach { o ->
                val mid = Jp.str(o, "mid") ?: Jp.str(o, "songmid") ?: return@forEach
                val title = Jp.str(o, "name") ?: Jp.str(o, "title") ?: return@forEach
                val artists = Jp.objects(o, "singer").mapNotNull { Jp.str(it, "name") }
                    .ifEmpty { Jp.str(o, "singername")?.split("/")?.map { it.trim() }.orEmpty() }
                val album = Jp.str(o, "album.name") ?: Jp.str(o, "albumname")
                    ?: Jp.str(o, "album").orEmpty()
                val albumMid = Jp.str(o, "album.mid") ?: Jp.str(o, "albummid")
                val pay = Jp.int(o, "pay.pay_play") ?: 0
                out += Song(
                    id = mid,
                    platform = Platform.QQ,
                    title = title,
                    artists = artists,
                    album = album,
                    albumId = albumMid,
                    durationMs = (Jp.long(o, "interval") ?: 0L) * 1000,
                    coverUrl = albumCover(albumMid),
                    availableQualities = qqQualitiesFromFile(o, pay),
                    vipFlag = pay == 1,
                    fingerprint = Crypt.fingerprint(title, artists.firstOrNull().orEmpty(), album),
                )
            }
            begin += chunk.size
            if (chunk.size < page) break
        }
        out
    }.getOrElse { emptyList() }
}
