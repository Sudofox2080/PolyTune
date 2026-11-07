package com.polytune.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polytune.source.api.Platform
import com.polytune.source.api.Song
import com.polytune.ui.common.SongRow
import com.polytune.ui.theme.ExpressiveLoading

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onOpenSong: (Song) -> Unit,
    onPlaySong: (Song) -> Unit,
    onSearch: () -> Unit = {},
    onLibrary: () -> Unit = {},
    onOffline: () -> Unit = onLibrary,
    onDownloads: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = vm::refresh, modifier = modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 130.dp), modifier = Modifier.fillMaxSize()) {
            item {
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
                    Text("LopMusic", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("把喜欢的声音，放在一起", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(22.dp))
                    Surface(
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onSearch),
                    ) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text("搜索歌曲、歌手与专辑", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QuickEntry("发现歌单", Icons.Rounded.Explore, onLibrary, Modifier.weight(1f))
                        QuickEntry("每日推荐", Icons.Rounded.AutoAwesome, { vm.refresh() }, Modifier.weight(1f))
                        QuickEntry("离线歌单", Icons.Rounded.Download, onOffline, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(26.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("为你推荐", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Platform.entries) { platform ->
                        FilterChip(
                            selected = state.activeHot == platform,
                            onClick = { vm.selectHot(platform) },
                            label = { Text(platform.displayName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(platform.brand).copy(alpha = 0.18f),
                                selectedLabelColor = Color(platform.brand),
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            items(state.hotSongs.take(20), key = { "${state.activeHot}-${it.id}" }) { song ->
                SongRow(song = song, onClick = { onPlaySong(song) }, modifier = Modifier.padding(horizontal = 8.dp).animateItem(), trailing = {
                    FilledIconButton(onClick = { onPlaySong(song) }) { Icon(Icons.Rounded.PlayArrow, "播放") }
                })
            }
            if (state.hotSongs.isEmpty() && !state.loading) {
                item {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("暂时没有推荐音乐", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = vm::refresh) { Text("重新加载") }
                    }
                }
            }
        }
        if (state.loading && state.hotSongs.isEmpty()) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) { ExpressiveLoading() }
        }
    }
}

@Composable
private fun QuickEntry(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
