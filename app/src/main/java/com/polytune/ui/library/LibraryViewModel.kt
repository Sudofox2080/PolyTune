package com.polytune.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.polytune.core.db.DownloadRepository
import com.polytune.core.db.DownloadState
import com.polytune.core.util.SongCodec
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.PlaylistBrief
import com.polytune.source.api.Song
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 歌单页的两块内容：本地已下载（离线可放）和在线推荐歌单（要联网取曲目）。
 * 两块放在同一个 VM 里，是因为它们共享「点了就进队列播放」这一套行为，
 * 分开会让播放动作在两边各写一遍。
 */
class LibraryViewModel(
    private val repo: DownloadRepository,
    private val agg: Aggregator,
) : ViewModel() {

    data class LocalSong(val song: Song, val filePath: String)

    data class UiState(
        val localSongs: List<LocalSong> = emptyList(),
        val loading: Boolean = true,
        /** 0 = 在线歌单，1 = 离线歌单。在线放前面，是因为它是打开歌单页时更常用的入口。 */
        val tab: Int = 0,
        val online: List<PlaylistBrief> = emptyList(),
        val onlineLoading: Boolean = false,
        val onlineLoaded: Boolean = false,
        val onlineError: String? = null,
        /** 当前打开的歌单，null 表示还停在列表上。 */
        val opened: PlaylistBrief? = null,
        val tracks: List<Song> = emptyList(),
        val tracksLoading: Boolean = false,
        val tracksError: String? = null,
    ) {
        /** 本地歌单的播放顺序，给「播放全部」和队列播种用。 */
        val playlist: List<Song> get() = localSongs.map { it.song }
    }

    private val _ui = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _ui

    /** 已取回的歌单曲目缓存，按「平台-歌单 id」存，来回切换不再重打一次接口。 */
    private val trackCache = ConcurrentHashMap<String, List<Song>>()

    private var onlineJob: Job? = null
    private var tracksJob: Job? = null

    init {
        viewModelScope.launch {
            repo.observeAll().collect { tasks ->
                val local = tasks.asSequence()
                    .filter { it.state == DownloadState.DONE && it.filePath.isNotBlank() }
                    .mapNotNull { task ->
                        runCatching { SongCodec.decode(task.songJson) }.getOrNull()
                            ?.let { LocalSong(it, task.filePath) }
                    }
                    .distinctBy { "${it.song.platform}-${it.song.id}" }
                    .toList()
                _ui.update { it.copy(localSongs = local, loading = false) }
            }
        }
    }

    fun setTab(index: Int) {
        _ui.update { it.copy(tab = index) }
        if (index == 0 && !_ui.value.onlineLoaded && !_ui.value.onlineLoading) refreshOnline()
    }

    fun refreshOnline() {
        if (onlineJob?.isActive == true) return
        _ui.update { it.copy(onlineLoading = true, onlineError = null) }
        onlineJob = viewModelScope.launch {
            // 账号歌单与平台推荐并行取：登录后先看到自己的歌单，未登录时 myPlaylists 立即返回空。
            val mineDeferred = async { runCatching { agg.myPlaylists() }.getOrElse { emptyList() } }
            val recDeferred = async { runCatching { agg.playlists() }.getOrElse { emptyList() } }
            val mine = mineDeferred.await()
            val rec = recDeferred.await()
            val list = (mine + rec).distinctBy { it.platform to it.id }
            _ui.update {
                it.copy(
                    online = list,
                    onlineLoading = false,
                    onlineLoaded = true,
                    onlineError = if (list.isEmpty()) "三个源都没返回歌单，检查网络后重试" else null,
                )
            }
        }
    }

    fun openPlaylist(brief: PlaylistBrief) {
        tracksJob?.cancel()
        val key = "${brief.platform}-${brief.id}"
        _ui.update { it.copy(opened = brief, tracksError = null) }
        trackCache[key]?.let { cached ->
            _ui.update { it.copy(tracks = cached, tracksLoading = false) }
            return
        }
        _ui.update { it.copy(tracksLoading = true, tracks = emptyList()) }
        tracksJob = viewModelScope.launch {
            val tracks = agg.playlistTracks(brief)
            if (tracks.isNotEmpty()) trackCache[key] = tracks
            _ui.update {
                it.copy(
                    tracks = tracks,
                    tracksLoading = false,
                    tracksError = if (tracks.isEmpty()) "这个歌单没有取到曲目，可能需要在账号页登录后再试" else null,
                )
            }
        }
    }

    fun closePlaylist() {
        tracksJob?.cancel()
        _ui.update { it.copy(opened = null, tracks = emptyList(), tracksError = null, tracksLoading = false) }
    }

    /** 详情页点「下载」后回到本地页时能看到新任务，这个入口留给界面上的重试按钮。 */
    fun retryTracks() {
        val brief = _ui.value.opened ?: return
        trackCache.remove("${brief.platform}-${brief.id}")
        openPlaylist(brief)
    }

    companion object {
        fun factory(repo: DownloadRepository, agg: Aggregator): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(repo, agg) as T
            }
    }
}
