package com.polytune.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.BannerItem
import com.polytune.source.api.Platform
import com.polytune.source.api.PlaylistBrief
import com.polytune.source.api.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 首页数据。
 *
 * 三个源各自并发，谁慢谁先被丢掉，但**丢掉的只是超时的那个源**，不影响其它源的结果：
 * 早先是聚合器整体 await，任何一个源卡住首屏就一起白等，真机上表现为「推荐歌单只有三张」。
 */
class HomeViewModel(private val agg: Aggregator) : ViewModel() {

    data class UiState(
        val banners: List<BannerItem> = emptyList(),
        val playlists: List<PlaylistBrief> = emptyList(),
        val hot: Map<Platform, List<Song>> = emptyMap(),
        val loading: Boolean = true,
        val activeHot: Platform = Platform.QQ,
        val loggedIn: Set<Platform> = emptySet(),
        val failedSources: Set<Platform> = emptySet(),
    ) {
        /** 当前选中源的热门列表，切源时界面直接读这个，避免每个 composable 各写一遍空判断。 */
        val hotSongs: List<Song> get() = hot[activeHot].orEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loggedIn = agg.loggedIn.value) }

            val hotJobs = Platform.entries.associateWith { p ->
                async { loadHot(p) }
            }

            val hotResults = hotJobs.mapValues { it.value.await() }
            val hot = hotResults.filterValues { it.isNotEmpty() }
            val failed = hotResults.filterValues { it.isEmpty() }.keys

            _state.update {
                // 当前选中源没数据时自动落到第一个有数据的源，避免切到空白 tab
                val active = if (hot.containsKey(it.activeHot)) it.activeHot else hot.keys.firstOrNull() ?: it.activeHot
                it.copy(
                    banners = emptyList(),
                    playlists = emptyList(),
                    hot = hot,
                    loading = false,
                    failedSources = failed,
                    activeHot = active,
                )
            }
        }
    }

    /** 单个源的热门首屏。异常与超时都折算成空列表，交给上层标记为失败源。 */
    private suspend fun loadHot(p: Platform): List<Song> = withTimeoutOrNull(7000) {
        runCatching { agg.source(p)?.search(p.hotKeyword(), 1, HOT_PAGE_SIZE)?.songs }
            .getOrNull()
            .orEmpty()
    } ?: emptyList()

    /** 按平台轮流取一张，保证混合展示而不是 QQ 的一排接网易的一排。 */
    private fun interleave(list: List<PlaylistBrief>): List<PlaylistBrief> {
        val buckets = list.groupBy { it.platform }
            .values
            .map { it.distinctBy { pl -> "${pl.platform}-${pl.id}" } }
            .sortedByDescending { it.size }
        val out = mutableListOf<PlaylistBrief>()
        var round = 0
        while (out.size < PLAYLIST_TARGET && buckets.any { round < it.size }) {
            buckets.forEach { b -> b.getOrNull(round)?.let { out += it } }
            round++
        }
        return if (out.size > PLAYLIST_TARGET) out.take(PLAYLIST_TARGET) else out
    }

    fun selectHot(p: Platform) = _state.update { it.copy(activeHot = p) }

    private companion object {
        /** 每个源一次拉 20 条，切 tab 后不用二次加载也不会显得空。 */
        const val HOT_PAGE_SIZE = 20

        /** 推荐歌单目标条数：太少首页只剩一行，滑不动也不好看。 */
        const val PLAYLIST_TARGET = 15
    }
}

private fun Platform.hotKeyword(): String = when (this) {
    Platform.QQ -> "热歌榜"
    Platform.NETEASE -> "热歌"
    Platform.KUGOU -> "热歌"
}
