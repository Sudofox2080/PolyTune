package com.polytune.ui.download

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.polytune.core.db.DownloadRepository
import com.polytune.core.db.DownloadState
import com.polytune.core.db.DownloadTaskEntity
import com.polytune.source.api.Quality
import com.polytune.source.api.Song
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadViewModel(private val repo: DownloadRepository) : ViewModel() {

    data class UiState(
        val tasks: List<DownloadTaskEntity> = emptyList(),
        val activeCount: Int = 0,
        val doneCount: Int = 0,
    )

    val state: StateFlow<UiState> = repo.observeAll()
        .map { list ->
            UiState(
                tasks = list,
                activeCount = list.count { it.state == DownloadState.RUNNING || it.state == DownloadState.PENDING },
                doneCount = list.count { it.state == DownloadState.DONE },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    /** 手动刷新出来的快照；为空时界面仍用 [state]。 */
    private val manual = kotlinx.coroutines.flow.MutableStateFlow<UiState?>(null)

    /** 界面最终读的流：Flow 有数据用 Flow，没数据但手动快照有数据就用快照。 */
    val uiState: StateFlow<UiState> =
        kotlinx.coroutines.flow.combine(state, manual) { s, m ->
            when {
                m == null -> s
                // Flow 给出空列表而快照有任务时，说明观察链没看到库里的数据，以快照为准。
                s.tasks.isEmpty() && m.tasks.isNotEmpty() -> m
                // 快照里的任务已经被删掉/变化，交给 Flow。
                else -> s
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), state.value)

    /** 手动兜底：绕过 Flow 直查一次库。给「明明有任务却空列表」的情况留一条自救通道。 */
    fun refresh() = viewModelScope.launch {
        val list = runCatching { repo.loadOnce() }.getOrNull() ?: return@launch
        manual.value = UiState(
            tasks = list,
            activeCount = list.count { it.state == DownloadState.RUNNING || it.state == DownloadState.PENDING },
            doneCount = list.count { it.state == DownloadState.DONE },
        )
    }

    fun download(song: Song, quality: Quality = Quality.HIGH, siblings: List<Song> = emptyList()) {
        viewModelScope.launch { repo.enqueue(song, quality, siblings) }
    }

    /** 失败的任务换源重试：解析时会把其它三家也问一遍，不只是原来那一家。 */
    fun retry(id: String) {
        viewModelScope.launch {
            val t = repo.get(id) ?: return@launch
            val song = runCatching { com.polytune.core.util.SongCodec.decode(t.songJson) }.getOrNull()
                ?: return@launch
            repo.remove(id, deleteFile = true)
            repo.enqueue(song, Quality.of(t.wantQuality))
        }
    }

    fun pause(id: String) = viewModelScope.launch { repo.pause(id) }

    fun resume(id: String) = viewModelScope.launch { repo.resume(id) }

    fun remove(id: String) = viewModelScope.launch { repo.remove(id) }

    fun retryAll() = viewModelScope.launch { repo.requeueAllUnfinished() }
}
