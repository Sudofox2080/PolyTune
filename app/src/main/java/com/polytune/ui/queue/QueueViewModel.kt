package com.polytune.ui.queue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.polytune.player.PlaybackEngine
import com.polytune.source.api.Song
import com.polytune.ui.play.PlayViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 播放队列页的 VM。
 *
 * 为什么队列本体不放在这里：[PlayViewModel] 已经持有真正的可播放队列（顺序推进、最近播放兜底都在那边），
 * 这里再存一份就会立刻出现「抽屉里的顺序和下一首按下后实际跳到的歌不一致」的漂移。
 * 所以这里只做两件事：把 PlayViewModel 的队列镜像成可观察的快照，以及把用户的意图
 * （切歌 / 清空）转交给 PlayViewModel 去执行——单一数据源，界面永远只反映一个真相。
 */
class QueueViewModel(
    private val play: PlayViewModel,
    private val engine: PlaybackEngine,
) : ViewModel() {

    data class UiState(
        /** 当前播放列表快照。空列表时抽屉显示空态。 */
        val items: List<Song> = emptyList(),
        /** 当前曲目在 [items] 里的下标。-1 表示当前曲目不在列表内（用户点了列表外的歌）。 */
        val currentIndex: Int = -1,
        /** 正在播的那首歌。用它做同一曲判定，因为跨源兜底后平台/id 可能和列表里那条不一致。 */
        val currentSong: Song? = null,
        val isPlaying: Boolean = false,
    )

    /**
     * 队列快照。PlayViewModel 只对外暴露 queueIndex / queueSize 两个数字，
     * 抽屉需要整表，所以这里用 play.queue（StateFlow<List<Song>>）做唯一来源，
     * 再和引擎的 isPlaying 合并——高亮项要同时表达「这是当前曲」和「现在有没有在响」。
     */
    val state: StateFlow<UiState> = combine(play.queue, play.state, engine.state) { list, playUi, now ->
        UiState(
            items = list,
            currentIndex = playUi.queueIndex,
            currentSong = playUi.song ?: now.song,
            isPlaying = now.isPlaying,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(2_000), UiState())

    /**
     * 点击队列里的一行。
     *
     * 分三种情况，目的都是「用户看到的那一行就是接下来响的那一首」：
     * 1. 点的是当前曲 —— 只切播放/暂停，不重新解析直链（重播会让进度归零，手感像 bug）；
     * 2. 点的是列表里另一首 —— 先推进光标再播，这样后续的下一首从被点的那首往后走；
     * 3. 点的下标越界（列表刚被清空）—— 直接忽略。
     */
    fun select(index: Int) {
        val s = state.value
        val target = s.items.getOrNull(index) ?: return
        if (index == s.currentIndex && s.currentSong != null && sameSong(target, s.currentSong)) {
            play.toggle()
            return
        }
        // 先把光标挪到目标位置，再触发播放：否则新一轮解析完成时 cursor 仍停在旧位置，
        // 紧接着按「下一首」会跳回中间某首歌，看起来像跳歌。
        viewModelScope.launch { play.queueSeek(index) }
    }

    /**
     * 清空队列。
     *
     * 只清排队列表、不停当前播放：用户点「清空」的意图是「把后面排的都不放了」，
     * 正在放的那首歌突然静音反而是破坏性操作。播放本身仍由播放页的播放/暂停键控制。
     */
    fun clear() {
        viewModelScope.launch { play.clearQueue() }
    }

    /** 跳歌失败时（例如队列已空）抽屉不该卡在加载态，这里保留一个显式的暂停入口给抽屉的重试按钮。 */
    fun toggle() = play.toggle()

    /**
     * 同一首歌判定与 PlayViewModel 内部一致：平台 + id 是硬键，指纹用来兜住跨源兜底后的同曲。
     * 这里复制了一份而不是把 PlayViewModel 的 private 扩展公开，是为了避免动到对方正在改的文件。
     */
    private fun sameSong(a: Song, b: Song): Boolean =
        (a.platform == b.platform && a.id == b.id) ||
            (a.fingerprint.isNotBlank() && a.fingerprint == b.fingerprint)

    companion object {
        /** 抽屉与 ViewModel 分开建工厂，是因为 QueueViewModel 只依赖播放层，不需要整个 AppGraph。 */
        fun factory(play: PlayViewModel, engine: PlaybackEngine): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { QueueViewModel(play, engine) }
            }
    }
}
