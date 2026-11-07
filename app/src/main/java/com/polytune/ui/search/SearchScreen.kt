package com.polytune.ui.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.Platform
import com.polytune.source.api.Song
import com.polytune.ui.common.CoverImage
import com.polytune.ui.common.PlatformBadge
import com.polytune.ui.common.SongRow
import com.polytune.ui.theme.ExpressiveLoading

/**
 * 跨平台搜索页。搜索栏用 DockedSearchBar 保持 MD3 语义，
 * 结果区按「同曲分组」渲染，组内多源版本可展开切换。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    vm: SearchViewModel,
    onOpenSong: (Song) -> Unit,
    onPlaySong: (Song) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()
    var expandedVariants by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxSize()) {
        Spacer(Modifier.height(8.dp))
        DockedSearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = s.keyword,
                    onQueryChange = vm::onKeyword,
                    onSearch = { vm.submit(it) },
                    expanded = s.expanded,
                    onExpandedChange = vm::setExpanded,
                    placeholder = { Text("搜索歌曲、歌手、专辑") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (s.keyword.isNotEmpty()) {
                            IconButton(onClick = { vm.onKeyword("") }) {
                                Icon(Icons.Rounded.Clear, null)
                            }
                        }
                    },
                )
            },
            expanded = s.expanded,
            onExpandedChange = vm::setExpanded,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            // 历史只在「刚开始搜、还没输入」时露出，并且压成单行横滑。
            // 之前是标题行 + 两行 FlowRow，搜索面板一展开就被历史撑满，
            // 看上去像是搜索结果区上多了一大块浅色留白。
            if (s.keyword.isBlank() && s.history.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth()) {
                    item {
                        LazyRow(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            item {
                                Icon(
                                    Icons.Rounded.History,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                            items(s.history.take(12)) { k ->
                                AssistChip(
                                    onClick = { vm.useHistory(k) },
                                    label = {
                                        Text(
                                            k,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                        )
                                    },
                                )
                            }
                            item {
                                AssistChip(
                                    onClick = vm::clearHistory,
                                    label = { Text("清空", style = MaterialTheme.typography.labelMedium) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        FlowRow(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Platform.entries.forEach { p ->
                val on = p in s.enabled
                FilterChip(
                    selected = on,
                    onClick = { vm.togglePlatform(p) },
                    label = { Text(p.displayName) },
                    leadingIcon = {
                        if (on) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .padding(end = 0.dp),
                            )
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(p.brand).copy(alpha = 0.18f),
                        selectedLabelColor = Color(p.brand),
                    ),
                )
            }
        }

        if (s.loading) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center,
            ) {
                ExpressiveLoading(color = MaterialTheme.colorScheme.primary)
            }
        }

        AnimatedVisibility(
            visible = s.groups.isNotEmpty() && !s.loading,
            enter = fadeIn() + slideInVertically { it / 6 },
            exit = fadeOut(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append("共 ${s.groups.size} 首")
                        if (s.perPlatform.isNotEmpty()) {
                            append(" · ")
                            append(s.perPlatform.entries.joinToString(" / ") { "${it.key.displayName}${it.value}" })
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${s.elapsedMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            itemsIndexed(s.groups, key = { _, g -> g.key }) { _, group ->
                val song = group.canonical
                Column(Modifier.animateContentSize(spring())) {
                    SongRow(
                        song = song,
                        onClick = { onPlaySong(song) },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (group.variants.size > 1) {
                                    AssistChip(
                                        onClick = {
                                            expandedVariants = if (expandedVariants == group.key) null else group.key
                                        },
                                        label = { Text("${group.variants.size}源") },
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                }
                                Spacer(Modifier.size(4.dp))
                                FilledTonalIconButton(
                                    onClick = { onOpenSong(song) },
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.Search,
                                        contentDescription = "详情",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Spacer(Modifier.size(4.dp))
                                FilledIconButton(
                                    onClick = { onPlaySong(song) },
                                    modifier = Modifier.size(38.dp),
                                ) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "播放",
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        },
                    )
                    if (expandedVariants == group.key) {
                        VariantDrawer(group, onOpenSong, onPlaySong)
                    }
                }
            }
            if (s.failed.isNotEmpty()) {
                item {
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${s.failed.joinToString("、") { it.displayName }} 本次无结果或超时",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            AssistChip(onClick = { vm.retryFailed() }, label = { Text("重试") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VariantDrawer(
    group: Aggregator.SongGroup,
    onOpenSong: (Song) -> Unit,
    onPlaySong: (Song) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            group.variants.forEach { v ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformBadge(v.platform)
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            v.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append(v.album.ifBlank { "未知专辑" })
                                if (v.availableQualities.isNotEmpty()) {
                                    append(" · ")
                                    append(v.availableQualities.joinToString("/") { it.label })
                                }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    AssistChip(onClick = { onPlaySong(v) }, label = { Text("播放") })
                    Spacer(Modifier.size(6.dp))
                    IconButton(onClick = { onOpenSong(v) }) {
                        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}
