package com.polytune.ui.play

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.polytune.core.download.DownloadTrace
import com.polytune.player.PlaybackEngine
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.LineLyric
import com.polytune.source.api.Platform
import com.polytune.source.api.Quality
import com.polytune.source.api.Song
import com.polytune.ui.queue.QueueViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 播放页 VM：负责「一首歌 + 期望音质 → 解析直链 → 塞进引擎 → 拉歌词」，
 * 同时持有整个 App 唯一的一份播放队列。
 *
 * 队列为什么放在这里而不是队列页：点歌、上一首、下一首、抽屉里点某一行，
 * 最终都落到同一个「当前曲 + 顺序」上。分散到两个 VM 就会出现
 * 「抽屉里显示的顺序和按下下一首之后真的跳到的歌不一致」这种漂移，所以队列只有一份。
 */
class PlayViewModel(
    private val agg: Aggregator,
    val engine: PlaybackEngine,
    private val appCtx: android.content.Context,
) : ViewModel() {

    data class UiState(
        val song: Song? = null,
        val actualPlatform: String = "",
        val lyric: List<LineLyric> = emptyList(),
        val resolving: Boolean = false,
        val error: String? = null,
        val tried: List<String> = emptyList(),
        /** 当前放的是 60 秒试听片段（未登录或会员曲），播放页会提示。 */
        val preview: Boolean = false,
        /** 队列里当前曲目的下标，-1 表示当前曲不在队列内（此时下一首置灰，上一首退回最近播放）。 */
        val queueIndex: Int = -1,
        val queueSize: Int = 0,
    )

    private val _own = MutableStateFlow(UiState())
    private var playJob: Job? = null

    /** 正在播放的期望音质，切歌时沿用同一档，不因为换歌就掉回标准音质。 */
    private var lastWant: Quality = Quality.HIGH

    /**
     * 队列本体。可变列表是因为顺序推进要就地增删；对外只通过 [queue] 这个 StateFlow 暴露快照，
     * 避免观察者读到一半被改。
     * 名字取 queueList 而不是 queue，是因为对外的快照流也叫 queue，同名会被 Kotlin 判成重复声明。
     */
    private val queueList = mutableListOf<Song>()
    private var cursor = -1

    /**
     * 队列快照流。队列页（QueueViewModel）靠它渲染整表，播放页只读 index/size 两个数字。
     * 每次结构或光标变化都要调 [publishQueue]，否则抽屉里的高亮会落后一帧。
     */
    private val _queue = MutableStateFlow<List<Song>>(emptyList())
    val queue: StateFlow<List<Song>> = _queue

    /** 当前光标。和 UiState.queueIndex 冗余，前者给 combine 用，后者给同步读取用。 */
    private val _queueCursor = MutableStateFlow(-1)
    val queueCursor: StateFlow<Int> = _queueCursor

    /** 最近播放过的歌（最新在前），队列走不下去时用它兜底「上一首」。 */
    private val recents = ArrayDeque<Song>()

    /** 自身状态与引擎播放状态合并成一个 UI 状态流。 */
    val state: StateFlow<UiState> = combine(_own, engine.state) { own, now ->
        val target = own.song ?: now.song
        val declared = target?.durationMs ?: 0L
        val actual = now.durationMs
        // 有些源即便账号有权限也会带 freeTrialInfo（尤其网易云的对唱/合唱版本），
        // 只看接口标记会把整首正片标成试听。真正播放后拿到的时长才是硬证据：
        // 实际时长接近曲目声明时长就说明拿到的是整首，此时不该再提示试听。
        val stillPreview = own.preview &&
            !(actual > 0L && declared > 0L && actual >= declared * 0.8)
        own.copy(
            song = own.song ?: now.song,
            actualPlatform = own.actualPlatform.ifBlank { now.actualPlatform },
            preview = stillPreview,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), UiState())

    init {
        // 一首放完就接队列下一首。引擎只给「播完了」这一个信号，走不走由队列决定：
        // 队列到头就停在 STATE_ENDED，不自动循环，避免用户以为播放器卡住了。
        // 这里不处理 playLocal 的本地曲，因为同一套队列逻辑对它们一样适用。
        engine.onEnded = {
            if (hasNext()) next()
        }
    }

    val playback: StateFlow<PlaybackEngine.NowPlaying> = engine.state

    fun playLocal(song: Song, filePath: String) {
        playJob?.cancel()
        cursor = queueList.indexOfFirst { it.sameAs(song) }
        if (cursor < 0) pushRecent(song)
        publishQueue()
        playJob = viewModelScope.launch {
            if (!java.io.File(filePath).isFile) {
                _own.update { it.copy(song = song, resolving = false, error = "本地文件不存在，请重新下载") }
                return@launch
            }
            _own.update { it.copy(song = song, actualPlatform = song.platform.name, resolving = false, error = null, lyric = emptyList(), preview = false) }
            val endpoint = com.polytune.source.api.PlayEndpoint(filePath, com.polytune.source.api.Protocol.LOCAL, quality = Quality.HIGH)
            val file = java.io.File(filePath)
            // 先读下载时存下的旁边车歌词；有就不再打网络，断网也能看歌词。
            val cached = com.polytune.core.util.LyricStore.load(file)
            if (cached.isNotEmpty()) _own.update { it.copy(lyric = cached) }
            engine.prepare(song, endpoint, song.platform.name)
            if (cached.isEmpty()) {
                val lyric = agg.lyricWithFallback(song)
                if (lyric.first.isNotEmpty()) {
                    _own.update { it.copy(lyric = lyric.first) }
                    runCatching { com.polytune.core.util.LyricStore.save(file, lyric.first) }
                }
            }
        }
    }

    fun play(song: Song, want: Quality = Quality.HIGH, siblings: List<Song> = emptyList()) {
        playJob?.cancel()
        lastWant = want
        // 「谁被点了谁成为当前曲」必须在这里统一维护：上一首/下一首也是走 play()，
        // 如果只在按钮回调里挪 cursor，从抽屉里点一首队列外的歌之后 cursor 会停在旧位置，
        // 再按下一首就会跳回已经放过的歌。
        val existing = queueList.indexOfFirst { it.sameAs(song) }
        cursor = existing
        // 点的是队列外的歌：队列不清空（用户还能往回走到刚才列表里的歌），
        // 但这首歌要进最近播放，否则上一首无路可走。
        if (existing < 0) pushRecent(song)
        publishQueue()
        playJob = viewModelScope.launch {
            DownloadTrace.log(appCtx, "play start: ${song.platform.name}/${song.id} ${song.title} want=$want")
            // 解析期间保留上一首的歌词/封面：很多曲目在换源解析时会失败（VIP、版权区、
            // 酷狗直链 status=2），如果这里先把 lyric 清空，失败后会留下「歌名还是旧的、
            // 歌词却变成暂无歌词」的半截状态。真正的切换等 ep 拿到后再原子地做。
            val before = _own.value
            _own.update { it.copy(resolving = true, error = null) }
            val resolved = agg.resolvePlay(song, want, siblings)
            val ep = resolved.endpoint
            DownloadTrace.log(
                appCtx,
                "play resolve: platform=${resolved.actualPlatform.name} url=${ep?.url} " +
                    "tried=${resolved.tried} protocol=${ep?.protocol}",
            )
            if (ep == null) {
                _own.update {
                    it.copy(
                        song = before.song,
                        actualPlatform = before.actualPlatform,
                        lyric = before.lyric,
                        preview = before.preview,
                        resolving = false,
                        error = "三个源都没拿到直链（可能是 VIP 或版权限制）",
                        tried = resolved.tried,
                    )
                }
                return@launch
            }
            // 让界面先切到目标曲目，再去等播放器：服务冷启动要 100~300ms，
            // 之前那版是等完播放器才更新界面，用户看到的是上一首的名字和「未在播放」。
            _own.update {
                it.copy(
                    song = resolved.song,
                    actualPlatform = resolved.actualPlatform.name,
                    resolving = false,
                    tried = resolved.tried,
                    preview = ep.preview,
                    lyric = emptyList(),
                )
            }
            engine.prepare(resolved.song, ep, resolved.actualPlatform.name)
            // 歌词和起播并行：歌词接口慢的时候不该拖住播放
            val l = agg.lyricWithFallback(resolved.song, siblings)
            DownloadTrace.log(appCtx, "play lyric: ${l.second} lines=${l.first.size}")
            _own.update { it.copy(lyric = l.first) }
        }
    }

    fun toggle() = engine.togglePlay()

    fun seekTo(ms: Long) = engine.seekTo(ms)

    fun platformOf(song: Song?): Platform? = song?.platform

    /* ---------------- 队列：上/下一首、整表替换、清空 ---------------- */

    /**
     * 用一整张列表替换队列。歌单/专辑页接过来时直接调；当前曲若在新列表里，
     * 光标跟到它在新列表里的位置，否则落在 [startAt]，不会出现「换了列表光标还指在旧下标」的越界。
     */
    fun setQueue(list: List<Song>, startAt: Int = 0) {
        val playing = _own.value.song
        queueList.clear()
        queueList.addAll(list.distinctBy { it.platform.name to it.id })
        cursor = when {
            queueList.isEmpty() -> -1
            playing != null && queueList.any { it.sameAs(playing) } -> queueList.indexOfFirst { it.sameAs(playing) }
            else -> startAt.coerceIn(0, queueList.lastIndex)
        }
        publishQueue()
    }

    fun hasPrevious(): Boolean = previousTarget() != null

    fun hasNext(): Boolean = nextTarget() != null

    fun previous() {
        val target = previousTarget() ?: return
        if (cursor > 0) cursor -= 1
        play(target, lastWant)
    }

    fun next() {
        val target = nextTarget() ?: return
        if (cursor < queueList.lastIndex) cursor += 1
        play(target, lastWant)
    }

    /**
     * 跳到队列里的第 index 首。先把 cursor 定到目标位置再 play()：
     * play() 内部会按同一首歌重新定位 cursor，目标曲若因指纹命中列表里另一条同曲记录，
     * 先定 cursor 能保证后续「下一首」按用户看到的位置继续。
     */
    fun queueSeek(index: Int) {
        val target = queueList.getOrNull(index) ?: return
        cursor = index
        publishQueue()
        play(target, lastWant)
    }

    /**
     * 清空排队列表。只清列表不动播放：用户点「清空」的意图是「后面排的都不放了」，
     * 正在听的那首突然静音反而是破坏性操作。
     */
    fun clearQueue() {
        // 清空前把当前曲收进最近播放，否则清完队列「上一首」就没有任何目标、按钮直接变灰。
        _own.value.song?.let { pushRecent(it) }
        queueList.clear()
        cursor = -1
        publishQueue()
    }

    /** 上一首：队列里往前一格；到队首之后退回最近播放历史（recents 最新在前）。 */
    private fun previousTarget(): Song? {
        if (cursor > 0) return queueList.getOrNull(cursor - 1)
        if (cursor == 0) return null
        val cur = _own.value.song
        return recents.firstOrNull { cur == null || !it.sameAs(cur) }
    }

    /** 下一首：队列里往后一格。没有队列时返回 null，界面据此把按钮置灰而不是空点。 */
    private fun nextTarget(): Song? = queueList.getOrNull(cursor + 1)

    private fun publishQueue() {
        _queue.value = queueList.toList()
        _queueCursor.value = cursor
        _own.update { it.copy(queueIndex = cursor, queueSize = queueList.size) }
    }

    private fun pushRecent(song: Song) {
        recents.removeAll { it.sameAs(song) }
        recents.addFirst(song)
        while (recents.size > RECENT_LIMIT) recents.removeLast()
    }

    /**
     * 同一首歌判定：平台 + id 是硬键；指纹用来兜住跨源兜底后同一首换了平台的情况，
     * 否则队列里会出现同一首歌的两个源版本，上一首/下一首看起来像重复。
     */
    private fun Song.sameAs(other: Song): Boolean =
        (platform == other.platform && id == other.id) ||
            (fingerprint.isNotBlank() && fingerprint == other.fingerprint)

    companion object {
        /** 最近播放保留条数。只用于「队列走到头之后还能不能上一首」，不需要很长。 */
        private const val RECENT_LIMIT = 30

        /** 队列抽屉独立建工厂：它只依赖播放层，不需要整个 AppGraph。 */
        fun queueFactory(play: PlayViewModel, engine: PlaybackEngine): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { QueueViewModel(play, engine) }
            }
    }
}

