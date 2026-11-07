package com.polytune.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.LineLyric
import com.polytune.source.api.Platform
import com.polytune.source.api.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 歌曲详情：多源版本、歌词、可用音质。
 * 歌词展示走 LRC 解析后的带时间戳行，供 NowPlaying 做逐行高亮。
 */
class DetailViewModel(
    private val agg: Aggregator,
    private val initial: Song,
) : ViewModel() {

    data class UiState(
        val song: Song,
        val variants: List<Song>,
        val lyric: List<LineLyric> = emptyList(),
        val lyricSource: String = "",
        val loadingLyric: Boolean = true,
        val showTranslation: Boolean = true,
        val showRomanization: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState(song = initial, variants = listOf(initial)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 同曲的其他源版本：借用聚合器的指纹分组，不额外打搜索请求
            val others = runCatching { agg.findVariants(initial) }.getOrDefault(emptyList())
            _state.update { it.copy(variants = (listOf(initial) + others).distinctBy { v -> v.platform to v.id }) }
            val res = agg.lyricWithFallback(initial)
            _state.update {
                it.copy(
                    lyric = res.first,
                    lyricSource = res.second,
                    loadingLyric = false,
                    error = if (res.first.isEmpty()) "未取到歌词" else null,
                )
            }
        }
    }

    fun switchVariant(s: Song) {
        viewModelScope.launch {
            _state.update { it.copy(song = s, loadingLyric = true) }
            val res = agg.lyricWithFallback(s)
            _state.update { it.copy(lyric = res.first, lyricSource = res.second, loadingLyric = false) }
        }
    }

    fun toggleTranslation() = _state.update { it.copy(showTranslation = !it.showTranslation) }
    fun toggleRomanization() = _state.update { it.copy(showRomanization = !it.showRomanization) }
    fun platformOf(): Platform = initial.platform
}
