package com.polytune.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polytune.source.api.PlaylistBrief
import com.polytune.source.api.Song
import com.polytune.ui.common.CoverImage
import com.polytune.ui.common.PlatformBadge
import com.polytune.ui.common.SongRow

/**
 * 歌单页。本地（已下载）与在线（平台推荐歌单）两块并列，
 * 在线块里点开一张歌单就换成曲目列表，返回键回到歌单列表。
 */
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    onPlaySong: (Song) -> Unit,
    onOpenSong: (Song) -> Unit,
    onPlayOnline: (List<Song>, Song) -> Unit,
    onAccount: () -> Unit = {},
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (!state.onlineLoaded && !state.onlineLoading) vm.refreshOnline()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
            Text(
                "我的歌单",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                if (state.tab == 0) "账号歌单与三平台推荐，登录后自动出现" else "已下载到本机，离线也能放",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.tab == 0,
                    onClick = { vm.setTab(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { Icon(Icons.Rounded.Cloud, null, Modifier.size(18.dp)) },
                ) { Text("在线歌单") }
                SegmentedButton(
                    selected = state.tab == 1,
                    onClick = { vm.setTab(1) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) },
                ) { Text("离线歌单") }
            }
        }

        when {
            state.opened != null -> OnlinePlaylistDetail(
                state = state,
                onBack = vm::closePlaylist,
                onPlayOnline = onPlayOnline,
                onOpenSong = onOpenSong,
                onRetry = vm::retryTracks,
            )
            state.tab == 0 -> OnlineTab(state, onOpen = vm::openPlaylist, onRefresh = vm::refreshOnline, onAccount = onAccount)
            else -> LocalTab(state, onPlaySong, onOpenSong)
        }
    }
}

@Composable
private fun LocalTab(
    state: LibraryViewModel.UiState,
    onPlaySong: (Song) -> Unit,
    onOpenSong: (Song) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 130.dp),
    ) {
        item {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(38.dp))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text("离线歌曲", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("${state.localSongs.size} 首歌曲", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.localSongs.isNotEmpty()) {
                        FilledIconButton(onClick = { onPlaySong(state.localSongs.first().song) }) {
                            Icon(Icons.Rounded.PlayArrow, "播放全部")
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(
            state.localSongs,
            key = { _, local -> "${local.song.platform}-${local.song.id}" },
        ) { index, local ->
            SongRow(
                song = local.song,
                onClick = { onPlaySong(local.song) },
                modifier = Modifier.animateItem(),
                trailing = {
                    FilledIconButton(onClick = { onPlaySong(local.song) }) {
                        Icon(Icons.Rounded.PlayArrow, "播放第 ${index + 1} 首")
                    }
                },
            )
        }
        if (!state.loading && state.localSongs.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Rounded.LibraryMusic,
                        null,
                        Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("还没有本地下载音乐", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "在歌曲详情里下载后，会在这里组成离线歌单",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnlineTab(
    state: LibraryViewModel.UiState,
    onOpen: (PlaylistBrief) -> Unit,
    onRefresh: () -> Unit,
    onAccount: () -> Unit,
) {
    if (state.onlineLoading && state.online.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val mine = state.online.filter { it.owned }
        val recommended = state.online.filterNot { it.owned }
        if (mine.isEmpty()) {
            item(key = "mine-login") {
                Surface(
                    onClick = onAccount,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Cloud, null, Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("登录后显示我的在线歌单", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("包括你自己创建和收藏的歌单；未登录只显示平台推荐", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                        Text("去登录", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        if (mine.isNotEmpty()) {
            item(key = "mine-header") {
                Text(
                    "我的歌单 · 已登录账号",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 6.dp),
                )
            }
            items(mine, key = { "mine-${it.platform}-${it.id}" }) { brief ->
                PlaylistRow(brief) { onOpen(brief) }
            }
        }
        if (recommended.isNotEmpty()) {
            item(key = "rec-header") {
                Text(
                    "平台推荐",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp),
                )
            }
            items(recommended, key = { "rec-${it.platform}-${it.id}" }) { brief ->
                PlaylistRow(brief) { onOpen(brief) }
            }
        }
        if (state.onlineError != null) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.onlineError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重新加载")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistRow(brief: PlaylistBrief, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(
                url = brief.coverUrl.ifBlank { null },
                modifier = Modifier.size(62.dp),
                corner = 14.dp,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    brief.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(brief.platform)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (brief.trackCount > 0) "${brief.trackCount} 首" else "歌单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.Rounded.PlayArrow,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OnlinePlaylistDetail(
    state: LibraryViewModel.UiState,
    onBack: () -> Unit,
    onPlayOnline: (List<Song>, Song) -> Unit,
    onOpenSong: (Song) -> Unit,
    onRetry: () -> Unit,
) {
    val brief = state.opened ?: return
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 130.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "返回歌单列表") }
                Text("返回", style = MaterialTheme.typography.labelLarge)
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverImage(
                    url = brief.coverUrl.ifBlank { null },
                    modifier = Modifier.size(96.dp),
                    corner = 18.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        brief.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlatformBadge(brief.platform)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.tracks.isNotEmpty()) "${state.tracks.size} 首" else "曲目",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (state.tracks.isNotEmpty()) {
                    FilledIconButton(onClick = { onPlayOnline(state.tracks, state.tracks.first()) }) {
                        Icon(Icons.Rounded.PlayArrow, "播放全部")
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        if (state.tracksLoading) {
            item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
        itemsIndexed(state.tracks, key = { _, s -> "${s.platform}-${s.id}" }) { index, song ->
            SongRow(
                song = song,
                onClick = { onPlayOnline(state.tracks, song) },
                modifier = Modifier.animateItem(),
                trailing = {
                    FilledIconButton(onClick = { onPlayOnline(state.tracks, song) }) {
                        Icon(Icons.Rounded.PlayArrow, "播放第 ${index + 1} 首")
                    }
                },
            )
        }
        if (state.tracksError != null && !state.tracksLoading) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.tracksError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重试")
                    }
                }
            }
        }
    }
}
