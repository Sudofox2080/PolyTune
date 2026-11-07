package com.polytune.ui.download

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.polytune.core.db.DownloadState
import com.polytune.core.db.DownloadTaskEntity
import com.polytune.core.db.progressFraction
import com.polytune.core.db.sizeText
import com.polytune.ui.common.CoverImage
import com.polytune.ui.common.PlatformBadge

/**
 * 下载管理页。进度条用动画值而不是直接绑 state，避免高频进度刷新把界面拖成跳帧；
 * 已完成项显示落盘大小，失败项直接展示错误原文，方便判断是 vkey 过期还是权限不足。
 */
@Composable
fun DownloadScreen(
    vm: DownloadViewModel,
    modifier: Modifier = Modifier,
    /** 进入「本地音乐」页。默认空实现，是为了不改动 PolyTuneScaffold 里既有的调用点。 */
    onOpenLibrary: () -> Unit = {},
) {
    // 读 uiState（Flow 与手动直查快照的合并结果），而不是直接读 state：
    // 观察链万一没看到库里的数据，手动刷新出来的快照仍然能把任务显示出来。
    val s by vm.uiState.collectAsStateWithLifecycle()

    // 进页面先直查一次库。成本就是一条 SELECT，换来的是不必再依赖 Flow 是否正常。
    androidx.compose.runtime.LaunchedEffect(vm) { vm.refresh() }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "下载",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "进行中 ${s.activeCount} · 已完成 ${s.doneCount}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledTonalIconButton(onClick = { onOpenLibrary() }) {
                Icon(Icons.Rounded.LibraryMusic, contentDescription = "本地音乐")
            }
            Spacer(Modifier.size(8.dp))
            FilledTonalIconButton(onClick = { vm.retryAll() }, enabled = s.activeCount > 0) {
                Icon(Icons.Rounded.Download, contentDescription = "全部继续")
            }
        }

        AnimatedVisibility(visible = s.tasks.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "还没有下载任务",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(s.tasks, key = { it.id }) { t ->
                TaskRow(
                    t = t,
                    onPause = { vm.pause(t.id) },
                    onResume = { vm.resume(t.id) },
                    onRetry = { vm.retry(t.id) },
                    onRemove = { vm.remove(t.id) },
                )
            }
        }
    }
}

@Composable
private fun TaskRow(
    t: DownloadTaskEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val target = t.progressFraction()
    val animated by animateFloatAsState(target, spring(stiffness = 300f), label = "dl-${t.id}")

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverImage(
                    url = t.coverUrl.orEmpty(),
                    corner = 14.dp,
                    modifier = Modifier.size(52.dp),
                )
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.polytune.source.api.Platform.of(t.platform)?.let { PlatformBadge(it, compact = true) }
                        Spacer(Modifier.size(6.dp))
                        // 格式直接按落盘路径的后缀推：filePath 才是真正写出去的文件名，
                        // savePath 在建任务时就定了，下载中断时两者可能还不一致。
                        val fmt = com.polytune.player.formatOfUrl(t.filePath.ifBlank { t.savePath })
                        if (fmt != null) {
                            val playable = com.polytune.player.canPlay(fmt.ext)
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = (if (playable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error).copy(alpha = 0.12f),
                                contentColor = if (playable) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            ) {
                                Text(
                                    fmt.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 1.dp),
                                )
                            }
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            buildString {
                                append(t.quality.let { q -> com.polytune.source.api.Quality.of(q).label })
                                val size = t.sizeText()
                                if (size.isNotBlank()) append(" · $size")
                                append(" · ${stateLabel(t.state)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                when (t.state) {
                    DownloadState.RUNNING, DownloadState.PENDING -> FilledTonalIconButton(
                        onClick = onPause,
                        modifier = Modifier.size(38.dp),
                    ) { Icon(Icons.Rounded.Pause, contentDescription = "暂停", modifier = Modifier.size(18.dp)) }

                    DownloadState.FAILED -> FilledTonalIconButton(
                        onClick = onRetry,
                        modifier = Modifier.size(38.dp),
                    ) { Icon(Icons.Rounded.Refresh, contentDescription = "换源重试", modifier = Modifier.size(18.dp)) }

                    DownloadState.PAUSED, DownloadState.CANCELED -> FilledTonalIconButton(
                        onClick = onResume,
                        modifier = Modifier.size(38.dp),
                    ) { Icon(Icons.Rounded.PlayArrow, contentDescription = "继续", modifier = Modifier.size(18.dp)) }

                    else -> {}
                }
                Spacer(Modifier.size(4.dp))
                FilledTonalIconButton(onClick = onRemove, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Rounded.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
                }
            }
            if (t.state != DownloadState.DONE) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp),
                    color = if (t.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            if (t.state == DownloadState.FAILED && !t.errMsg.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    t.errMsg!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    DownloadState.PENDING -> "排队中"
    DownloadState.RUNNING -> "下载中"
    DownloadState.PAUSED -> "已暂停"
    DownloadState.DONE -> "已完成"
    DownloadState.FAILED -> "失败"
    DownloadState.CANCELED -> "已取消"
    else -> state
}
