package com.polytune.ui.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polytune.source.api.Platform
import com.polytune.source.api.Song
import com.polytune.ui.common.CoverImage
import com.polytune.ui.common.PlatformBadge

/**
 * 播放列表抽屉。
 *
 * 为什么做成覆盖在播放页上的整屏面板而不是 ModalBottomSheet：
 * 1. 播放页本身已经是整屏、且带着品牌色渐变，底部弹出式面板会把「当前封面 + 歌词」压成一小条，
 *    既不好看也不好读；
 * 2. 抽屉要能一屏看到十几首，靠底部弹层只能露三行，用户还得再拖一次。
 * 所以这里用左右滑入的整屏面板，左上角收起回播放页，语义上是「翻开队列看一眼再翻回去」。
 *
 * 队列本体在 PlayViewModel 里（全 App 唯一一份），这里只镜像 + 转交意图，
 * 理由写在 QueueViewModel 的类注释里。
 */
@Composable
fun QueueSheet(
    vm: QueueViewModel,
    onClose: () -> Unit,
    onOpenSong: (Song) -> Unit = {},
) {
    val s by vm.state.collectAsStateCompat()
    val listState = rememberLazyListState()

    // 打开时把当前曲滚到可见位置：队列可能有几十首，默认停在顶部会让人以为「当前曲没在队列里」。
    LaunchedEffect(s.items.size, s.currentIndex) {
        if (s.currentIndex >= 0 && s.items.isNotEmpty()) {
            listState.animateScrollToItem(s.currentIndex.coerceIn(0, s.items.lastIndex))
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "收起播放列表")
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "播放列表",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        if (s.items.isEmpty()) "队列是空的" else "共 ${s.items.size} 首",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (s.items.isNotEmpty()) {
                    IconButton(onClick = { vm.clear() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = "清空队列")
                    }
                }
            }

            if (s.items.isEmpty()) {
                EmptyQueue()
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(
                        items = s.items,
                        key = { index, song -> "${index}-${song.platform.name}-${song.id}" },
                    ) { index, song ->
                        QueueRow(
                            song = song,
                            index = index,
                            active = index == s.currentIndex,
                            playing = index == s.currentIndex && s.isPlaying,
                            onClick = { vm.select(index) },
                            onOpen = { onOpenSong(song) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    song: Song,
    index: Int,
    active: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onOpen: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (active) tint.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        // 序号位：当前曲换成播放三角，其余显示下标。
        // 之所以不用「整个行高亮成选中色」：手机上颜色块太大，反而盖住了封面。
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(28.dp),
        ) {
            if (active) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = if (playing) "正在播放" else "已暂停",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            } else {
                Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.size(6.dp))
        CoverImage(
            url = song.coverUrl,
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(12.dp)),
            corner = 12.dp,
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) tint else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlatformBadge(song.platform, compact = true)
                Spacer(Modifier.size(6.dp))
                Text(
                    song.artistsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            song.durationText,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 长按之外的显式入口：点这一行是「切到这首」，点右侧图标是「看这首歌的详情」。
        // 两个动作分开挂，避免用户想切歌结果跳进了详情页。
        IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Rounded.DragHandle,
                contentDescription = "查看详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EmptyQueue() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.7f),
    ) {
        FilledTonalIconButton(onClick = {}, enabled = false, modifier = Modifier.size(64.dp)) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "队列里还没有歌",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "去首页或搜索里，点任意一首的播放键",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 与 PolyTuneScaffold 里那个同名辅助函数一样：
 * collectAsState(initial = value) 在 Compose 1.12 里仍是最稳的写法，
 * 直接 collectAsState() 拿不到默认值，界面会在第一帧闪一下空态。
 */
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateCompat(): State<T> =
    this.collectAsState(this.value)

/** 保留给后续「按平台着色序号」用；当前未被引用，删掉会牵动 import，先留着。 */
@Suppress("unused")
private val Platform.queueTint: Long
    get() = brand
