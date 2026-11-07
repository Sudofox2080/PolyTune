package com.polytune.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polytune.core.util.RecallStore
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.Platform
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 搜索页状态。多平台并行，展示层保留分组：一个分组代表一首歌的多个源版本，
 * 用户点开分组可以在抽屉里切换源、看到各源音质。
 */
class SearchViewModel(private val agg: Aggregator) : ViewModel() {

    data class UiState(
        val keyword: String = "",
        val submitted: String = "",
        val expanded: Boolean = false,
        val enabled: Set<Platform> = Platform.entries.toSet(),
        val groups: List<Aggregator.SongGroup> = emptyList(),
        val perPlatform: Map<Platform, Int> = emptyMap(),
        val failed: Set<Platform> = emptySet(),
        val loading: Boolean = false,
        val elapsedMs: Long = 0,
        val history: List<String> = RecallStore.get().keywords.value,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var suggestJob: Job? = null

    fun onKeyword(k: String) {
        _state.update { it.copy(keyword = k, expanded = true) }
        suggestJob?.cancel()
        if (k.isBlank()) return
        suggestJob = viewModelScope.launch {
            delay(350)
            // 输入停顿 350ms 自动搜，打字过程中不打断输入法
            submit(k, remember = false)
        }
    }

    fun setExpanded(v: Boolean) = _state.update { it.copy(expanded = v) }

    fun togglePlatform(p: Platform) {
        _state.update {
            val next = if (p in it.enabled) it.enabled - p else it.enabled + p
            it.copy(enabled = if (next.isEmpty()) it.enabled else next)
        }
        if (_state.value.submitted.isNotBlank()) submit(_state.value.submitted)
    }

    fun submit(keyword: String = _state.value.keyword, remember: Boolean = true) {
        val kw = keyword.trim()
        if (kw.isEmpty()) return
        if (remember) RecallStore.get().putKeyword(kw)
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, submitted = kw, keyword = kw, expanded = false, error = null) }
            val outcome = agg.search(kw, _state.value.enabled)
            _state.update {
                it.copy(
                    loading = false,
                    groups = outcome.groups,
                    perPlatform = outcome.perPlatformCount,
                    failed = outcome.failed,
                    elapsedMs = outcome.elapsedMs,
                    history = RecallStore.get().keywords.value,
                )
            }
        }
    }

    fun retryFailed() = submit(_state.value.submitted)

    fun clearHistory() {
        RecallStore.get().clearKeywords()
        _state.update { it.copy(history = emptyList()) }
    }

    fun useHistory(k: String) = submit(k)
}
