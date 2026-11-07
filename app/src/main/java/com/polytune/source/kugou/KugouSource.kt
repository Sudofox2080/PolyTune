package com.polytune.source.kugou

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
 * 酷狗音乐源。
 *
 * 搜索走 mobilecdn.kugou.com 的 songsearch（md5 签名），播放走 trackercdn.
 * 登录态依赖三个 cookie：`kg_mid`（设备 id，即 dfid）、`kg_dfid`、`KuGoo`（token）与 `kg_uk`/`kg_uid`。
 * 支持 cookie 导入与扫码（二维码接口返回 qr_id，轮询 sign）。未登录时仅能播放免费曲的标准档。
 */
class KugouSource(net: SourceHttp) : BaseMusicSource(Platform.KUGOU, net) {

    companion object {
        /** web 端固定盐，签名一律 md5(盐 + 排序参数 + 盐)。 */
        private const val SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt"
        /** 密码二次摘要用的客户端盐，和 Web 登录页脚本里的一致。 */
        private const val PWD_SALT = "kugouPassword"
    }

    /* ---------------- 登录 ---------------- */

    override suspend fun login(method: LoginMethod): LoginResult = when (method) {
        is LoginMethod.CookieImport -> cookieLogin(method.cookie)
        is LoginMethod.TokenInject -> tokenLogin(method.token, method.uid)
        is LoginMethod.PhonePassword -> passwordLogin(method.phone, method.password)
        is LoginMethod.SmsCode -> smsLogin(method.phone, method.code)
        is LoginMethod.EmailPassword -> LoginResult.Fail("酷狗没有邮箱登录，用手机号或网页登录")
        is LoginMethod.QrCode -> qrCreate()
    }

    override fun supportedMethods(): Set<LoginKind> = setOf(
        LoginKind.PhoneSms, LoginKind.PhonePassword, LoginKind.Cookie, LoginKind.Web,
    )

    /**
     * 手机号 + 密码登录。酷狗 Web 端把密码按 md5(md5(pwd) + salt) 处理，
     * 同一套用户体系与 App 登录共用，登录成功返回 token + userid。
     */
    private suspend fun passwordLogin(phone: String, password: String): LoginResult = runCatching {
        val pwd = Crypt.md5(Crypt.md5(password) + PWD_SALT)
        val form = mapOf(
            "appid" to "1001",
            "clientver" to "2000",
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            "plat" to "4",
            "userid" to "0",
            "username" to phone,
            "password" to pwd,
            "type" to "1",
            "mobile" to phone,
        )
        val body = net.postForm(
            "https://login-user.kugou.com/v2/login/",
            form,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Referer" to "https://www.kugou.com/"),
        )
        parseLogin(body)
    }.getOrElse { LoginResult.Fail("登陆失败：${it.message}") }

    /** 手机号 + 短信验证码。短信先由 sendSmsCode 发出。 */
    private suspend fun smsLogin(phone: String, code: String): LoginResult = runCatching {
        val form = mapOf(
            "appid" to "1001",
            "clientver" to "2000",
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            "plat" to "4",
            "userid" to "0",
            "mobile" to phone,
            "code" to code,
            "type" to "2",
        )
        val body = net.postForm(
            "https://login-user.kugou.com/v2/login/",
            form,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Referer" to "https://www.kugou.com/"),
        )
        parseLogin(body)
    }.getOrElse { LoginResult.Fail("登陆失败：${it.message}") }

    override suspend fun sendSmsCode(phone: String): LoginResult = runCatching {
        val form = mapOf("appid" to "1001", "clientver" to "2000", "plat" to "4", "mobile" to phone, "type" to "1")
        val body = net.postForm(
            "https://login-user.kugou.com/v2/sms/send",
            form,
            mapOf("Content-Type" to "application/x-www-form-urlencoded", "Referer" to "https://www.kugou.com/"),
        )
        val root = Jp.root(body)
        val status = Jp.int(root, "status")
        val msg = Jp.str(root, "error") ?: Jp.str(root, "errmsg") ?: Jp.str(root, "msg")
        if (status == 1 || status == 0 && msg.isNullOrBlank()) {
            // 这里没有凭证，只表示短信已发出；UI 靠 Success 类型显示「已发送」。
            LoginResult.Pending("", message = "验证码已发送到 $phone")
        } else {
            LoginResult.Fail(msg ?: "验证码发送失败")
        }
    }.getOrElse { LoginResult.Fail("发送失败：${it.message}") }

    /** 账号接口统一返回结构：status=1 成功，data.token + data.userid。 */
    private suspend fun parseLogin(body: String): LoginResult {
        val root = Jp.root(body)
        val status = Jp.int(root, "status")
        if (status != 1) {
            val msg = Jp.str(root, "error") ?: Jp.str(root, "errmsg") ?: "账号或密码不正确"
            return LoginResult.Fail(msg)
        }
        val token = Jp.str(root, "data.token").orEmpty()
        val uid = Jp.long(root, "data.userid")?.toString().orEmpty()
        if (token.isBlank()) return LoginResult.Fail("登录成功但没有拿到 token")
        return tokenLogin(token, uid.ifBlank { "0" })
    }

    /**
     * 网页登录与手动粘贴都走这里。
     *
     * 酷狗网页端下发的会话键名这几年换过好几套：老页面是 `KuGoo`，
     * 新版用户中心是 `token` + `userid`，App 端是 `kg_uid`。只认其中一套是之前
     * 「网页登录后仍显示未登录」的直接原因，所以这里按优先级把三套都试一遍，
     * 并把最终认到的是哪套写进日志，方便下次接口再变时快速定位。
     */
    private suspend fun cookieLogin(cookie: String): LoginResult {
        val raw = cookie.trim().removePrefix("Cookie:").trim()
        fun pick(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
            Regex("(?:^|;\\s*)" + Regex.escape(n) + "=([^;]+)").find(raw)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
        }
        val token = pick("KuGoo", "kugoou", "token", "KuGooToken")
            ?: return LoginResult.Fail(
                "这条 cookie 里没有能用的酷狗 token。实际拿到 ${raw.split(';').size} 个键：" +
                    raw.split(';').mapNotNull { it.substringBefore('=', "").trim().ifBlank { null } }
                        .take(12).joinToString("|") +
                    "。请先在网页右上角点登录完成登录，再回来读取。"
            )
        val uid = pick("kg_uid", "userid", "uid", "kg_uk") ?: "0"
        val dfid = pick("kg_mid", "kg_dfid", "dfid") ?: randomDfid()
        log("cookie login picked token=${token.take(8)}… uid=$uid dfid=${dfid.take(8)}… keys=${raw.split(';').size}")
        return tokenLogin(token, uid, raw, dfid)
    }

    /** 登录链路唯一可靠的日志出口。 */
    private fun log(msg: String) {
        com.polytune.core.download.TraceCtx.app?.let {
            com.polytune.core.download.DownloadTrace.log(it, "kugou login | $msg")
        }
    }

    private suspend fun tokenLogin(
        token: String,
        uid: String,
        rawCookie: String? = null,
        dfid: String = randomDfid(),
    ): LoginResult = runCatching {
        val cookie = rawCookie ?: "KuGoo=$token; kg_uid=$uid; kg_mid=$dfid"
        val cred = Credential(
            platform = Platform.KUGOU,
            uid = uid,
            token = token,
            extra = mapOf("cookie" to cookie, "dfid" to dfid),
        )
        setCredential(cred)
        // 昵称拿不到不算登录失败：token 有效就说明会话成立，
        // 早前这里把「取不到昵称」当成出错，会把已登录的账号显示成未登录。
        val body = runCatching { userDetail() }.getOrNull()
        val nick = body?.let { Jp.str(Jp.root(it), "data.nickname") }
        val pic = body?.let { Jp.str(Jp.root(it), "data.pic") }.orEmpty()
        log("token login uid=$uid nick=${nick ?: "空"} resp=${(body ?: "").take(160)}")
        val final = cred.copy(nickname = nick ?: "酷狗用户", avatar = pic)
        setCredential(final)
        LoginResult.Success(final)
    }.getOrElse { LoginResult.Fail("登陆失败：${it.message}") }

    private fun randomDfid(): String {
        val chars = "0123456789abcdef"
        return (1..32).map { chars.random() }.joinToString("")
    }

    private suspend fun userDetail(): String {
        val dfid = credential.value?.extra?.get("dfid") ?: randomDfid()
        val url = "https://kugou.com/youth/api/v1/user/detail?dfid=$dfid&token=${credential.value?.token}"
        return net.get(url, mapOf("Referer" to "https://kugou.com/"))
    }

    /** 签名：md5(salt + 排序后的 k=v 直接拼接 + salt)。 */
    private fun signature(data: String, salt: String = SALT): String = Crypt.md5(salt + data + salt)

    private suspend fun qrPollLegacy(): LoginResult = runCatching {
        val url = "https://login-user.kugou.com/v2/qrcode?appid=1001&type=1&plat=4"
        val body = net.get(url)
        val qrId = Jp.str(Jp.root(body), "data.qrcode") ?: Jp.str(Jp.root(body), "data.qr_id")
        if (qrId.isNullOrBlank()) return@runCatching LoginResult.Fail("二维码获取失败")
        LoginResult.Pending(qrId, message = "用酷狗 App 扫码")
    }.getOrElse { LoginResult.Fail(it.message ?: "二维码获取失败") }

    override suspend fun qrPoll(ticket: String): LoginResult = runCatching {
        val url = "https://login-user.kugou.com/v2/get_userinfo_qrcode?plat=4&appid=1001&qr_id=$ticket"
        val body = net.get(url)
        val status = Jp.int(Jp.root(body), "data.status")
        when (status) {
            4 -> {
                val token = Jp.str(Jp.root(body), "data.token").orEmpty()
                val uid = Jp.long(Jp.root(body), "data.userid")?.toString().orEmpty()
                tokenLogin(token, uid)
            }
            2 -> LoginResult.Pending(ticket, message = "已扫码，等待确认")
            else -> LoginResult.Pending(ticket, message = "等待扫码")
        }
    }.getOrElse { LoginResult.Fail(it.message ?: "轮询失败") }

    // 酷狗的二维码是接口直接给的一张 PNG（data.qrcode 里既可能是裸 qr_id，也可能是 base64 图），
    // 所以先请求，再判断拿到的是 id 还是图片本身。
    override suspend fun qrCreate(): LoginResult = runCatching {
        val url = "https://login-user.kugou.com/v2/qrcode?appid=1001&type=1&plat=4"
        val body = net.get(url)
        val root = Jp.root(body)
        val qrId = Jp.str(root, "data.qrcode") ?: Jp.str(root, "data.qr_id")
        if (qrId.isNullOrBlank()) return@runCatching LoginResult.Fail("二维码获取失败")
        val imageUrl = if (qrId.startsWith("data:") || qrId.length > 200) {
            qrId
        } else {
            "https://login-user.kugou.com/v2/qrcode_img?appid=1001&plat=4&qr_id=$qrId"
        }
        LoginResult.Pending(qrId, qrImageUrl = imageUrl, message = "用酷狗 App 扫码")
    }.getOrElse { LoginResult.Fail(it.message ?: "二维码获取失败") }

    /* ---------------- 搜索 ---------------- */

    override suspend fun search(keyword: String, page: Int, pageSize: Int): SearchPage = runCatching {
        // 新版 songsearch 接口一次返回四个音质各自的 hash，这正是 trackercdn 取直链需要的键
        val params = "platform=WebFilter&userid=0&clientver=2000&iscorrection=1&privilege_filter=0" +
            "&filter=10&keyword=${enc(keyword)}&page=$page&pagesize=$pageSize"
        val sig = signature(params)
        val url = "https://songsearch.kugou.com/song_search_v2?$params&signature=$sig"
        val body = net.get(url, mapOf("Referer" to "https://www.kugou.com/"))
        val root = Jp.root(body)
        val lists = Jp.objects(root, "data.lists")
        val songs = lists.mapNotNull { o -> parseSearchItem(o) }
        // 兜底：songsearch 被限流时退回老接口（只有 128k hash）
        val fallback = if (songs.isEmpty()) legacySearch(keyword, page, pageSize) else emptyList()
        val total = Jp.int(root, "data.total") ?: songs.size
        val out = songs.ifEmpty { fallback }
        SearchPage(out, page * pageSize < total, total.coerceAtLeast(out.size))
    }.getOrElse { SearchPage() }

    private fun parseSearchItem(o: kotlinx.serialization.json.JsonObject): Song? {
        val h128 = Jp.str(o, "FileHash") ?: Jp.str(o, "hash")
        val title = Jp.str(o, "SongName") ?: Jp.str(o, "songname") ?: return null
        val singer = Jp.str(o, "SingerName") ?: Jp.str(o, "singername").orEmpty()
        val album = Jp.str(o, "AlbumName") ?: Jp.str(o, "album_name").orEmpty()
        val ids = buildMap {
            h128?.let { put(Quality.STANDARD.name, it) }
            Jp.str(o, "HQFileHash")?.let { put(Quality.HIGH.name, it) }
            Jp.str(o, "SQFileHash")?.let { put(Quality.LOSSLESS.name, it) }
            (Jp.str(o, "ResFileHash") ?: Jp.str(o, "FileHash2"))?.let { put(Quality.HIRES.name, it) }
        }
        val id = h128 ?: ids.values.firstOrNull() ?: return null
        val img = Jp.str(o, "Image") ?: Jp.str(o, "trans_param.union_cover").orEmpty()
        return Song(
            id = id,
            platform = Platform.KUGOU,
            title = title,
            artists = splitSinger(singer),
            album = album,
            albumId = Jp.long(o, "AlbumID")?.toString() ?: Jp.long(o, "album_id")?.toString(),
            durationMs = (Jp.long(o, "Duration") ?: Jp.long(o, "duration") ?: 0) * 1000,
            coverUrl = img.ifBlank { null }?.replace("{size}", "400"),
            availableQualities = ids.keys.mapNotNull { k -> Quality.of(k) }.sortedBy { it.rank },
            vipFlag = Jp.bool(o, "Privilege") == true || (Jp.int(o, "privilege") ?: 0) > 0,
            fingerprint = Crypt.fingerprint(title, splitSinger(singer).firstOrNull().orEmpty(), album),
            qualityIds = ids,
        )
    }

    private suspend fun legacySearch(keyword: String, page: Int, pageSize: Int): List<Song> = runCatching {
        val params = "appid=1005&keyword=${enc(keyword)}&page=$page&pagesize=$pageSize&plat=0&type=0&ver=0&ver2=0"
        val url = "https://mobilecdn.kugou.com/api/v3/search/song?$params&signature=${signature(params)}"
        val body = net.get(url, mapOf("Referer" to "https://m.kugou.com/"))
        Jp.objects(Jp.root(body), "data.info").mapNotNull { parseSearchItem(it) }
    }.getOrDefault(emptyList())

    private fun splitSinger(s: String): List<String> =
        s.split('、', '&', '/', ',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun enc(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    override suspend fun songDetail(id: String): Song? = runCatching {
        val url = "https://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=$id"
        val body = net.get(url, mapOf("Referer" to "https://m.kugou.com/"))
        val root = Jp.root(body) ?: return@runCatching null
        val title = Jp.str(root, "songName") ?: Jp.str(root, "fileName") ?: return@runCatching null
        val singer = Jp.str(root, "author_name") ?: Jp.str(root, "singerName") ?: ""
        val isVip = Jp.int(root, "privilege") == 2
        val ids = mutableMapOf(Quality.STANDARD.name to id)
        Song(
            id = id,
            platform = Platform.KUGOU,
            title = title,
            artists = splitSinger(singer),
            album = Jp.str(root, "album_name").orEmpty(),
            albumId = Jp.long(root, "albumid")?.toString(),
            durationMs = (Jp.long(root, "timeLength") ?: Jp.long(root, "time") ?: 0) * 1000,
            coverUrl = Jp.str(root, "album_img") ?: Jp.str(root, "img"),
            availableQualities = listOf(Quality.STANDARD, Quality.HIGH),
            vipFlag = isVip,
            fingerprint = Crypt.fingerprint(title, splitSinger(singer).firstOrNull().orEmpty(), ""),
            qualityIds = ids,
        )
    }.getOrNull()

    override suspend fun lyric(id: String): Lyric = runCatching {
        klog("lyric enter id=$id cached=${qualityIds[id]?.keys}")
        // 酷狗歌词库用 128k 的 FileHash 做索引；我们手里可能是 HQ/SQ 的 hash，
        // 所以按「本条 hash → 实例缓存的其它档 hash → 按歌名搜」三级回退，任一级拿到 LRC 就返回。
        val hashes = buildList {
            add(id)
            qualityIds[id]?.values?.forEach { h -> if (h.isNotBlank()) add(h) }
        }.distinct()

        for (h in hashes) {
            for (c in findLyricCandidates(h, keyword = null)) {
                val l = downloadLyric(c)
                if (l.lines.isNotEmpty()) return@runCatching l
            }
        }

        // 按 hash 全落空：用歌名 + 歌手去歌词库检索（这是酷狗 App 自己也在用的兜底）。
        val detail = runCatching { songDetail(id) }.getOrNull()
        klog("lyric detail id=$id title=${detail?.title} artist=${detail?.artistsText}")
        val kw = listOfNotNull(detail?.title, detail?.artistsText).joinToString(" ").trim()
        if (kw.isNotBlank()) {
            for (c in findLyricCandidates("", keyword = kw)) {
                val l = downloadLyric(c)
                if (l.lines.isNotEmpty()) return@runCatching l
            }
        }
        klog("lyric MISS id=$id hashes=$hashes kw=$kw")
        Lyric(instrumental = true, source = "kugou")
    }.getOrElse {
        klog("lyric EX id=$id ${it.javaClass.simpleName}: ${it.message}")
        Lyric(source = "kugou")
    }

    /** 歌词链路的落盘日志：小米 ROM 吞 logcat，只能靠文件排查。 */
    private fun klog(msg: String) {
        com.polytune.core.download.TraceCtx.app?.let { c ->
            com.polytune.core.download.DownloadTrace.log(c, "kugou lyric | $msg")
        }
    }

    private class LyricCandidate(val id: String, val accessKey: String, val durationMs: Long = 0L)

    /** 按 hash 或关键词取候选列表（已排序），调用方逐个试，避免第一个候选是空文本就让整首判「无歌词」。 */
    private suspend fun findLyricCandidates(hash: String, keyword: String?): List<LyricCandidate> {
        val base = buildMap {
            put("appid", "1005")
            put("client", "mobi")
            put("ver", "1")
            put("man", "yes")
            if (hash.isNotBlank()) put("hash", hash)
            put("lrcx", "1")
            put("duration", "0")
            if (!keyword.isNullOrBlank()) {
                put("keyword", keyword)
                put("album", "")
                put("singer", "")
            }
        }
        val sorted = base.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" }
        val sig = Crypt.md5(SALT + sorted + SALT)
        val query = base.entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val url = "https://krcs.kugou.com/search?$query&signature=$sig"
        val body = net.get(url, mapOf("Referer" to "https://m.kugou.com/"))
        val cands = Jp.objects(Jp.root(body), "candidates")
        klog("search hash=$hash kw=$keyword n=${cands.size}")
        // 候选里常混进「纯文本无时间戳」的 UGC 条目和别的同名翻唱，按 score 与 krctype 排序后逐个试，
        // 只有真正的 LRC（含 [mm:ss.xx] 时间戳）才算命中。
        return cands
            .sortedWith(
                compareByDescending<kotlinx.serialization.json.JsonObject> { Jp.int(it, "score") ?: 0 }
                    .thenByDescending { Jp.int(it, "krctype") ?: 0 },
            )
            .mapNotNull { o ->
                val lid = Jp.str(o, "id") ?: return@mapNotNull null
                LyricCandidate(
                    lid,
                    Jp.str(o, "accesskey").orEmpty(),
                    (Jp.long(o, "duration") ?: 0L),
                )
            }
    }

    private suspend fun downloadLyric(c: LyricCandidate): Lyric {
        val dl = "https://lyrics.kugou.com/download?ver=1&client=pc&id=${c.id}" +
            "&accesskey=${enc(c.accessKey)}&fmt=lrc&charset=utf8"
        val body = net.get(dl, mapOf("Referer" to "https://m.kugou.com/"))
        klog("download id=${c.id} fmt=lrc status=${Jp.int(Jp.root(body), "status")} len=${body.length}")
        val raw = Jp.str(Jp.root(body.replace("\\/", "/")), "content").orEmpty()
        val text = if (raw.isBlank()) ""
        else runCatching { Crypt.base64Decode(raw).toString(Charsets.UTF_8) }.getOrDefault(raw)
            .removePrefix("\uFEFF")
        if (text.isBlank()) return Lyric(instrumental = true, source = "kugou")
        val parsed = LrcParser.parse(text)
        klog("parsed id=${c.id} lines=${parsed.lines.size} head=${text.take(40).replace("\n", "\\n")}")
        return parsed.copy(source = "kugou")
    }

    /**
     * 播放直链只有 trackercdn 能给出全档位：key = md5(hash + "kgcloudv2")，
     * 连 appid/pid/cmd 一起进 query。cmd=25 返回的 url 是数组（多 CDN），cmd=23 是字符串，两种都兼容。
     * 先按目标音质降序尝试登记过的 hash，再退回默认档，避免为了无损把整首歌搞成无法播放。
     */
    override suspend fun playUrl(id: String, quality: Quality): PlayEndpoint? = playUrlInternal(id, quality, null)

    override suspend fun playUrl(song: Song, quality: Quality): PlayEndpoint? =
        playUrlInternal(song.id, quality, song)

    private suspend fun playUrlInternal(id: String, quality: Quality, song: Song?): PlayEndpoint? = runCatching {
        val wanted = Quality.entries.sortedByDescending { it.rank }
            .filter { it.rank <= quality.rank } + Quality.entries.sortedByDescending { it.rank }
        for (q in wanted.distinct()) {
            val hash = candidateHash(song, id, q)
            com.polytune.core.download.TraceCtx.app?.let { c ->
                com.polytune.core.download.DownloadTrace.log(
                    c,
                    "kugou play id=$id q=${q.name} hash=$hash known=${song?.qualityIds?.keys}",
                )
            }
            if (hash == null) continue
            val key = Crypt.md5(hash + "kgcloudv2")
            val url = "https://trackercdn.kugou.com/i/v2/?appid=1005&pid=2&cmd=25&behavior=play" +
                "&hash=$hash&key=$key"
            val body = net.get(url, mapOf("Referer" to "https://m.kugou.com/"))
            com.polytune.core.download.TraceCtx.app?.let { c ->
                com.polytune.core.download.DownloadTrace.log(c, "kugou resp q=${q.name} body=${body.take(300)}")
            }
            val root = Jp.root(body) ?: continue
            val st = Jp.int(root, "status") ?: 0
            // 兼容两条线上的响应形状：status=1 时 url 直接给全曲直链；
            // 部分接口把试听片段放在 play_url/play_urls 里，也一并接受。
            val candidate = when {
                st == 1 -> Jp.str(root, "url[0]") ?: Jp.str(root, "url") ?: Jp.str(root, "play_url") ?: Jp.str(root, "play_urls[0]")
                else -> Jp.str(root, "play_url") ?: Jp.str(root, "play_urls[0]") ?: Jp.str(root, "url[0]")
            }
            if (candidate.isNullOrBlank() || !candidate.startsWith("http")) continue
            return@runCatching PlayEndpoint(
                url = candidate.replace("http://", "https://"),
                protocol = if (candidate.contains(".m3u8")) Protocol.HLS else Protocol.HTTP,
                quality = q,
                expiresAt = System.currentTimeMillis() + 3 * 60 * 60 * 1000,
                sizeBytes = Jp.long(root, "fileSize") ?: -1,
            )
        }
        null
    }.getOrNull()

    /** 取该音质对应的 hash；没有登记过就退回搜索时拿到的 id（通常就是 128k）。 */
    private fun candidateHash(song: Song?, id: String, q: Quality): String? {
        song?.qualityIds?.get(q.name)?.let { return it }
        val cached = qualityIds[id].orEmpty()
        cached[q.name]?.let { return it }
        if (q == Quality.STANDARD) return id
        // 没有登记该档位时返回 null，交给调用方降档或换源，而不是拿 128k 的 hash 冒充。
        return null
    }

    /** 搜索时把四档 hash 记下来，播放阶段不用再查一次详情。 */
    private val qualityIds = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    fun rememberQualityIds(song: Song) {
        if (song.qualityIds.isNotEmpty()) qualityIds[song.id] = song.qualityIds
    }

    override suspend fun recommendBanners(): List<BannerItem> = runCatching {
        // 酷狗没有独立 banner 接口，用每日推荐歌单封面拼一条可点的横幅
        val body = net.get("https://m.kugou.com/plist/index&json=true&page=1", mapOf("Referer" to "https://m.kugou.com/"))
        Jp.objects(Jp.root(body), "plist.list.info").take(6).mapNotNull { o ->
            val img = Jp.str(o, "imgurl")?.replace("{size}", "480") ?: return@mapNotNull null
            BannerItem(
                id = Jp.long(o, "specialid")?.toString() ?: return@mapNotNull null,
                imageUrl = img.replace("http://", "https://"),
                title = Jp.str(o, "specialname").orEmpty(),
                subtitle = "${Jp.int(o, "playcount") ?: 0} 次播放",
                targetType = "playlist",
                targetId = Jp.long(o, "specialid").toString(),
                platform = Platform.KUGOU,
            )
        }
    }.getOrDefault(emptyList())

    override suspend fun recommendPlaylists(): List<PlaylistBrief> = runCatching {
        val body = net.get("https://m.kugou.com/plist/index&json=true?page=1", mapOf("Referer" to "https://m.kugou.com/"))
        Jp.objects(Jp.root(body), "plist.list.info").mapNotNull { o ->
            PlaylistBrief(
                id = Jp.long(o, "specialid")?.toString() ?: return@mapNotNull null,
                name = Jp.str(o, "specialname").orEmpty(),
                coverUrl = Jp.str(o, "imgurl").orEmpty().replace("{size}", "400"),
                trackCount = Jp.int(o, "songcount") ?: 0,
                platform = Platform.KUGOU,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 歌单曲目。m.kugou.com 的 plist 详情只回第一页十首，所以改走 mobilecdn 的
     * special/song 接口按页拉全量（pagesize 300，最多 10 页，防止死循环）。
     * 注意这个域名只挂 http：https 的证书是 *.cdn.myqcloud.com，OkHttp 会直接
     * SSLPeerUnverifiedException，用 http 才稳定拿到数据。
     * 酷狗这一层只给 hash 和「歌手 - 歌名」拼串，没有独立音质档位，档位留空，
     * 真正取值时 playUrl 会按已知 hash 逐档试。
     */
    override suspend fun playlistDetail(id: String): List<Song> = runCatching {
        val out = ArrayList<Song>()
        var page = 1
        while (page <= 10) {
            val body = net.get(
                "http://mobilecdn.kugou.com/api/v3/special/song?specialid=$id&page=$page&pagesize=300&version=9108&plat=0&sver=5",
                mapOf("Referer" to "https://m.kugou.com/"),
            )
            if (body.isBlank()) break
            val rows = Jp.objects(Jp.root(body), "data.info")
            rows.forEach { o ->
                val hash = Jp.str(o, "hash") ?: return@forEach
                val filename = Jp.str(o, "filename").orEmpty()
                val (singer, title) = splitFilename(filename)
                if (title.isBlank()) return@forEach
                val artistList = splitSinger(singer)
                val album = Jp.str(o, "album_name").orEmpty()
                out += Song(
                    id = hash,
                    platform = Platform.KUGOU,
                    title = title,
                    artists = artistList,
                    album = album,
                    albumId = Jp.str(o, "album_id"),
                    durationMs = (Jp.long(o, "duration") ?: 0L) * 1000,
                    coverUrl = Jp.str(o, "cover")?.replace("{size}", "400"),
                    fingerprint = Crypt.fingerprint(title, artistList.firstOrNull().orEmpty(), album),
                )
            }
            if (rows.size < 300) break
            page++
        }
        out.distinctBy { it.id }
    }.getOrElse { emptyList() }

    /** 酷狗给的 filename 是「歌手 - 歌名」，拆开；没有分隔符时整串当歌名。 */
    private fun splitFilename(filename: String): Pair<String, String> {
        val i = filename.indexOf(" - ")
        return if (i > 0) filename.substring(0, i) to filename.substring(i + 3) else "" to filename
    }
}
