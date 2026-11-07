package com.polytune.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polytune.source.api.Song
import com.polytune.ui.common.CoverImage
import com.polytune.ui.common.PlatformBadge
import com.polytune.ui.theme.ExpressiveLoading

/**
 * 详情页结构：封面大图 → 标题/艺人 → 多源版本横排 → 操作按钮 → 歌词全文。
 * 歌词默认只显示原文，翻译/罗马音是可选开关，避免屏幕挤满三行文字。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    vm: DetailViewModel,
    onPlay: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val s by vm.state.collectAsStateWithLifecycle()

    // 详情页是浮在列表之上的覆盖层，没有系统 ActionBar，所以自己给一个收起按钮。
    // 放在 LazyColumn 外面固定住，长列表滚到底也能点到。
    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 120.dp),
    ) {
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CoverImage(
                    url = s.song.coverUrl,
                    corner = 28.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    s.song.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append(s.song.artistsText)
                        if (s.song.album.isNotBlank()) append(" · ${s.song.album}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { onPlay(s.song) }, shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.size(6.dp))
                        Text("播放")
                    }
                    FilledTonalButton(onClick = { onDownload(s.song) }, shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Rounded.Download, null)
                        Spacer(Modifier.size(6.dp))
                        Text("下载")
                    }
                }
            }
        }

        item {
            Text(
                "其他源版本",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                Modifier.padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                s.variants.forEach { v ->
                    val active = v.platform == s.song.platform && v.id == s.song.id
                    FilterChip(
                        selected = active,
                        onClick = { if (!active) vm.switchVariant(v) },
                        label = { Text(v.platform.displayName) },
                        leadingIcon = { PlatformBadge(v.platform, compact = true) },
                        trailingIcon = {
                            Text(
                                v.availableQualities.maxByOrNull { it.rank }?.label ?: "-",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "歌词",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (s.lyricSource.isNotBlank()) {
                    Text(
                        s.lyricSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.size(8.dp))
                FilterChip(
                    selected = s.showTranslation,
                    onClick = { vm.toggleTranslation() },
                    label = { Text("翻译") },
                )
                Spacer(Modifier.size(6.dp))
                FilterChip(
                    selected = s.showRomanization,
                    onClick = { vm.toggleRomanization() },
                    label = { Text("罗马音") },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        if (s.loadingLyric) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) { ExpressiveLoading() }
            }
        }

        items(s.lyric) { line ->
            Surface(
                color = androidx.compose.ui.graphics.Color.Transparent,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            ) {
                Column {
                    Text(line.text, style = MaterialTheme.typography.bodyLarge)
                    if (s.showTranslation && line.translation != null) {
                        Text(
                            line.translation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (s.showRomanization && line.romanization != null) {
                        Text(
                            line.romanization,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    // 悬浮收起按钮：半透明底，压在封面左上角，不占布局高度。
    FilledTonalIconButton(
        onClick = onClose,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 16.dp, top = 16.dp),
    ) {
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起详情")
    }
    }
}
