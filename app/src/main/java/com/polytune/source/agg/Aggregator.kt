package com.polytune.source.agg

import com.polytune.source.api.BannerItem
import com.polytune.source.api.Lyric
import com.polytune.source.api.MusicSource
import com.polytune.source.api.Platform
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.PlaylistBrief
import com.polytune.source.api.Quality
import com.polytune.source.api.ResolvedPlay
import com.polytune.source.api.Song
import com.polytune.source.api.bestAtMost
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 聚合器：面向 [MusicSource] 接口做并行搜索、跨源合并、播放换源与歌词兜底。
 *
 * 合并策略不追求「同一首歌只留一条」，而是保留多源版本并标出主条目：
 * 用户能在搜索页看到「QQ音乐 / 网易云 / 酷狗 都有这首」，播放时再按音质与可用性择优。
 */
class Aggregator(val sources: Map<Platform, MusicSource>) {

    private val _loggedIn = MutableStateFlow<Set<Platform>>(emptySet())
    val loggedIn: StateFlow<Set<Platform>> = _loggedIn

    fun refreshLoginState() {
        _loggedIn.value = sources.filterValues { it.isLoggedIn() }.keys
    }

    fun source(p: Platform): MusicSource? = sources[p]

    /* ---------------- 搜索 ---------------- */

    data class SearchOutcome(
        val groups: List<SongGroup>,
        val perPlatformCount: Map<Platform, Int>,
        val failed: Set<Platform>,
        val elapsedMs: Long,
    )

    /** 同一首曲的多源版本。canonical 为默认选中的版本。 */
    data class SongGroup(val key: String, val variants: List<Song>) {
        val canonical: Song get() = variants.first()
        val platforms: List<Platform> get() = variants.map { it.platform }
    }

    /**
     * 并行搜索，单源慢不拖累整体：每个源 4 秒超时，超时的源标记失败但仍返回其它源结果。
     */
    suspend fun search(
        keyword: String,
        enabled: Set<Platform> = sources.keys,
        pageSize: Int = 20,
        perSourceTimeoutMs: Long = 4_000,
    ): SearchOutcome = coroutineScope {
        val started = System.currentTimeMillis()
        val targets = sources.filterKeys { it in enabled }
        val results = targets.map { (p, src) ->
            p to async {
                runCatching { src.search(keyword, 1, pageSize).songs }
                    .getOrDefault(emptyList())
            }
        }
        val collected = mutableMapOf<Platform, List<Song>>()
        val failed = mutableSetOf<Platform>()
        results.forEach { (p, d) ->
            val r = withTimeoutOrNull(perSourceTimeoutMs) { d.await() }
            if (r == null || r.isEmpty()) failed += p else collected[p] = r
        }
        val flat = collected.values.flatten()
        SearchOutcome(
            groups = group(flat),
            perPlatformCount = collected.mapValues { it.value.size },
            failed = failed,
            elapsedMs = System.currentTimeMillis() - started,
        )
    }

    /** 按指纹分组；同组内按「音质档位多 > 非VIP > 时长与均值接近」排序。 */
    fun group(list: List<Song>): List<SongGroup> {
        val byKey = LinkedHashMap<String, MutableList<Song>>()
        list.forEach { s ->
            val key = s.fingerprint.ifBlank {
                com.polytune.core.crypto.Crypt.fingerprint(s.title, s.artists.firstOrNull().orEmpty(), s.album)
            }
            byKey.getOrPut(key) { mutableListOf() } += s
        }
        return byKey.map { (k, v) ->
            val avg = v.map { it.durationMs }.average()
            val ranked = v.sortedWith(
                compareByDescending<Song> { it.availableQualities.size }
                    .thenByDescending { !it.vipFlag }
                    .thenBy { kotlin.math.abs(it.durationMs - avg) },
            )
            SongGroup(k, ranked)
        }.sortedByDescending { it.variants.maxOf { v -> v.availableQualities.size } }
    }

    /* ---------------- 播放解析 ---------------- */

    /**
     * 取播放直链。优先原源；原源拿不到就按其它已启用源里同指纹的版本兜底。
     * 返回的 song 可能是换了源的版本，播放页据此显示当前实际音源。
     */
    suspend fun resolvePlay(song: Song, want: Quality, allowCrossSource: Boolean = true): ResolvedPlay =
        resolvePlay(song, want, knownSiblings = emptyList(), allowCrossSource = allowCrossSource)

    /**
     * 取播放直链。优先原源；原源拿不到就按其它已启用源里同指纹的版本兜底。
     * [knownSiblings] 是调用方已经拿到的其它源版本（详情页/播放页通常都有），
     * 给了就直接用，省掉「换源前再搜一遍对方曲库」的那次请求。
     */
    suspend fun resolvePlay(
        song: Song,
        want: Quality,
        knownSiblings: List<Song>,
        allowCrossSource: Boolean = true,
    ): ResolvedPlay = coroutineScope {
        val order = buildList {
            add(song.platform)
            if (allowCrossSource) {
                knownSiblings.forEach { v -> if (v.platform != song.platform && v.platform !in this) add(v.platform) }
                sources.keys.filter { it != song.platform && it !in this }.forEach { add(it) }
            }
        }
        // 各源并行抢答：串行试的时候，酷狗一首歌要先等它把三档音质全试完、再等 QQ 两档，
        // 网易云才轮到，点一下歌要空等 4~6 秒。并行之后总耗时约等于最快那个源。
        val pending = order.filter { sources[it] != null }
            .map { p -> Job(p, async { resolveOn(p, song, want, knownSiblings) }) }
            .toMutableList()
        var previewFallback: ResolvedPlay? = null
        val tried = mutableListOf<String>()
        var winner: ResolvedPlay? = null
        while (pending.isNotEmpty()) {
            val done = select<Job> { pending.forEach { job -> job.deferred.onAwait { job } } }
            pending.remove(done)
            val attempt = done.deferred.await()
            tried += attempt.tried
            val r = attempt.resolved ?: continue
            // 第一轮只要正片：未登录时 QQ 会下发试听片段，谁先把试听塞进来谁就把整首正片挡住了。
            if (r.endpoint?.preview == true) {
                if (previewFallback == null) previewFallback = r
                continue
            }
            winner = r
            break
        }
        // 找到正片就取消还在跑的源，否则 structured concurrency 会等它们全部超时才返回。
        pending.forEach { runCatching { it.deferred.cancel() } }
        winner?.copy(tried = tried.toList())
            ?: previewFallback?.copy(tried = tried.toList())
            ?: ResolvedPlay(song, song.platform, null, tried)
    }

    private data class Job(val platform: Platform, val deferred: Deferred<Attempt>)

    private data class Attempt(val resolved: ResolvedPlay?, val tried: List<String>)

    /** 在单个源上按音质阶梯取直链；异常也记进 trace，区分「接口返回空」与「代码炸了」。 */
    private suspend fun resolveOn(
        platform: Platform,
        song: Song,
        want: Quality,
        knownSiblings: List<Song>,
    ): Attempt {
        val src = sources[platform] ?: return Attempt(null, emptyList())
        val variant = when {
            platform == song.platform -> song
            else -> knownSiblings.firstOrNull { it.platform == platform } ?: findVariant(song, platform)
        } ?: return Attempt(null, emptyList())
        val tried = mutableListOf<String>()
        for (q in qualityLadder(variant, want)) {
            tried += "${platform.displayName}:${q.label}"
            val ep = try {
                src.playUrl(variant, q)
            } catch (t: Throwable) {
                com.polytune.core.download.TraceCtx.app?.let { c ->
                    com.polytune.core.download.DownloadTrace.log(
                        c,
                        "resolve throw ${platform.name}/${variant.id} ${q.name}: " +
                            "${t.javaClass.simpleName}: ${t.message}",
                    )
                }
                null
            }
            if (ep == null || ep.url.isBlank()) continue
            return Attempt(ResolvedPlay(variant, platform, ep, tried.toList()), tried.toList())
        }
        return Attempt(null, tried.toList())
    }

    /** 换源时搜一次对方曲库，指纹匹配即认为同一首。 */
    suspend fun findVariant(song: Song, target: Platform): Song? {
        val src = sources[target] ?: return null
        val kw = "${song.title} ${song.artists.firstOrNull().orEmpty()}".trim()
        if (kw.isBlank()) return null
        val candidates = runCatching { src.search(kw, 1, 8).songs }.getOrDefault(emptyList())
        val fp = song.fingerprint.ifBlank {
            com.polytune.core.crypto.Crypt.fingerprint(song.title, song.artists.firstOrNull().orEmpty(), song.album)
        }
        return candidates.firstOrNull { it.fingerprint == fp }
            ?: candidates.firstOrNull {
                it.title.equals(song.title, ignoreCase = true) &&
                    it.artists.firstOrNull()?.equals(song.artists.firstOrNull(), ignoreCase = true) == true
            }
    }

    /**
     * 一次性拿到这首曲在所有源上的版本，用于详情页的「换用其它音源」。
     * 并发查四个源，单源 4 秒超时，原源版本永远排在第一个。
     */
    suspend fun findVariants(song: Song): List<Song> = coroutineScope {
        val jobs = sources.keys.map { p ->
            p to async {
                if (p == song.platform) song else withTimeoutOrNull(4_000) { findVariant(song, p) }
            }
        }
        jobs.mapNotNull { (_, d) -> d.await() }.distinctBy { it.platform }
    }

    /**
     * 期望档位以下的降级阶梯。曲目自己声明的可用档位优先，
     * 声明的池子拿不到时再退到「全部档位里不高于期望的那些」，
     * 最后补一个 STANDARD 兜底，保证每一家源至少被问一次。
     */
    fun qualityLadder(song: Song, want: Quality): List<Quality> {
        val declared = song.availableQualities.filter { it.rank <= want.rank }
            .sortedByDescending { it.rank }
        val fallback = Quality.entries.filter { it.rank <= want.rank }.sortedByDescending { it.rank }
        return (declared + fallback + listOf(Quality.STANDARD)).distinct()
    }

    /** 在曲目声明的可用档位里挑不高于期望的最高档。 */
    fun pickQuality(song: Song, want: Quality): Quality {
        val pool = song.availableQualities.ifEmpty { listOf(Quality.STANDARD) }
        return pool.bestAtMost(want) ?: Quality.STANDARD
    }

    /* ---------------- 歌词 ---------------- */

    /** 本源没有歌词（或只有空行）时，去其它源按指纹找同曲歌词。返回歌词与来源标注。 */
    suspend fun lyricWithFallback(
        song: Song,
        knownSiblings: List<Song> = emptyList(),
    ): Pair<List<com.polytune.source.api.LineLyric>, String> {
        val own = sources[song.platform]?.let { runCatching { it.lyric(song.id) }.getOrNull() }
        if (own != null && own.lines.isNotEmpty() && !own.instrumental) {
            return own.lines to sourceLabel(song.platform, own.source)
        }

        for ((p, src) in sources) {
            if (p == song.platform) continue
            // 只按「指纹命中一首」去要歌词太脆：跨源的 album 经常不一致，
            // 指纹里带了 album，命中失败就整首没歌词。这里退一步：
            // 先用指纹/同名同歌手找，找不到就拿标题搜前 10 条、逐个试歌词，
            // 只认标题完全一致的结果，避免把翻唱或 Live 版歌词贴上去。
            val candidates = buildList {
                knownSiblings.firstOrNull { it.platform == p }?.let { add(it) }
                findVariant(song, p)?.let { add(it) }
                if (isEmpty()) {
                    val kw = "${song.title} ${song.artists.firstOrNull().orEmpty()}".trim()
                    runCatching { src.search(kw, 1, 10).songs }
                        .getOrDefault(emptyList())
                        .filter { it.title.equals(song.title, ignoreCase = true) }
                        .take(3)
                        .forEach { add(it) }
                }
            }.distinctBy { it.id }
            for (variant in candidates) {
                val l = runCatching { src.lyric(variant.id) }.getOrNull() ?: continue
                if (l.lines.isNotEmpty() && !l.instrumental) {
                    return l.lines to "${p.displayName}（兜底）"
                }
            }
        }
        return (own?.lines ?: emptyList()) to (if (own?.instrumental == true) "纯音乐" else "")
    }

    /** 各源返回的 source 是内部短标识（qq/netease/kugou），这里统一翻成给人看的名字。 */
    private fun sourceLabel(p: Platform, raw: String): String {
        if (raw.isBlank()) return p.displayName
        val byName = Platform.entries.firstOrNull {
            raw.equals(it.name, ignoreCase = true) || raw.equals(it.displayName, ignoreCase = true)
        }
        return byName?.displayName ?: raw
    }

    /* ---------------- 首页 ---------------- */

    suspend fun banners(): List<BannerItem> = coroutineScope {
        val defs = sources.values.map { src -> async {
            runCatching { src.recommendBanners() }.getOrDefault(emptyList())
        } }
        defs.awaitAllList().flatten().distinctBy { it.imageUrl }
    }

    /** 已登录账号的「我的歌单」。未登录的源直接跳过，避免白等超时。 */
    suspend fun myPlaylists(): List<PlaylistBrief> = coroutineScope {
        val defs = sources.values.filter { it.isLoggedIn() }.map { src ->
            async { runCatching { src.myPlaylists() }.getOrDefault(emptyList()) }
        }
        defs.awaitAllList().flatten().distinctBy { it.platform to it.id }
    }

    suspend fun playlists(): List<PlaylistBrief> = coroutineScope {
        val defs = sources.values.map { src -> async {
            runCatching { src.recommendPlaylists() }.getOrDefault(emptyList())
        } }
        defs.awaitAllList().flatten().distinctBy { it.platform to it.id }
    }

    private suspend fun <T> List<kotlinx.coroutines.Deferred<List<T>>>.awaitAllList(): List<List<T>> =
        map { withTimeoutOrNull(5_000) { it.await() } ?: emptyList() }

    /**
     * 在线歌单曲目。固定回到歌单自己的源去取，不跨源聚合：一张网易云歌单里混进 QQ 的
     * 同曲版本，会让「这张歌单是哪个平台的」失去意义，用户下次打开还会发现曲目变了。
     * 源端不给曲目（接口下线/需要登录）就返回空列表，由界面提示，而不是编造数据。
     */
    suspend fun playlistTracks(brief: PlaylistBrief): List<Song> {
        val src = sources[brief.platform] ?: return emptyList()
        return withTimeoutOrNull(12_000) {
            runCatching { src.playlistDetail(brief.id) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    /* ---------------- 下载 ---------------- */

    /**
     * 下载解析结果。和播放不同，下载允许换源：原源这首曲没版权/只有试听片段时，
     * 换到别家同曲版本，文件头信息也跟着换成实际音源的，免得文件名写着 QQ 音乐却下了酷狗的文件。
     */
    data class DownloadPlan(
        val song: Song,
        val actualPlatform: Platform,
        val endpoint: PlayEndpoint,
        val tried: List<String>,
        val fileName: String,
        val extension: String,
    )

    suspend fun planDownload(
        song: Song,
        want: Quality,
        knownSiblings: List<Song> = emptyList(),
    ): DownloadPlan? {
        val resolved = resolvePlay(song, want, knownSiblings)
        val ep = resolved.endpoint ?: return null
        val actual = resolved.song
        // 扩展名按服务端真正给出的编码猜，而不是按请求的音质猜：
        // 1080 万播放的老歌常常只给 flac 之外的格式，猜错会导致播放器认不出文件。
        val ext = guessExtension(ep.url)
        return DownloadPlan(
            song = actual.copy(platform = resolved.actualPlatform),
            actualPlatform = resolved.actualPlatform,
            endpoint = ep,
            tried = resolved.tried,
            fileName = safeName(actual),
            extension = ext,
        )
    }

    private fun guessExtension(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".flac") -> "flac"
            path.endsWith(".m4a") || path.endsWith(".mp4") || path.endsWith(".alac") -> "m4a"
            path.endsWith(".ogg") || path.endsWith(".opus") -> "ogg"
            path.endsWith(".wav") -> "wav"
            path.endsWith(".wma") -> "wma"
            // APE 与 DSD 在浏览器直链里少见，但资源库会直接给原文件路径，
            // 猜成 mp3 会被播放器按 mp3 解析失败，所以这里保留真实后缀。
            path.endsWith(".ape") -> "ape"
            path.endsWith(".dsf") -> "dsf"
            path.endsWith(".dff") -> "dff"
            path.endsWith(".mka") -> "mka"
            path.endsWith(".aac") -> "aac"
            path.endsWith(".aiff") || path.endsWith(".aif") -> "aiff"
            else -> "mp3"
        }
    }

    private fun safeName(song: Song): String {
        val raw = "${song.artists.firstOrNull().orEmpty()} - ${song.title}"
        return raw.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").trim().take(80)
            .ifBlank { song.id }
    }
}
