package com.polytune.ui.play

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.polytune.source.api.LineLyric
import com.polytune.source.api.Song
import com.polytune.ui.common.PlatformBadge
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 全屏播放页。视觉是「封面 + 逐行歌词 + 控制条」，歌词跟随播放进度高亮，
 * 高亮行走 Apple Music 那套：当前行放大加粗，上下相邻行按距离递减字号与透明度，
 * 所有属性都用 spring 过渡，切行时不会瞬变；列表用 animateScrollToItem 带居中偏移滚动。
 *
 * 布局约束（真机反馈过的问题，改之前先读这里）：
 * - 页面整体是一块连续的品牌色渐变，顶栏也吃同一块渐变，且完全不透明。
 *   旧版顶栏是一张 alpha 0.86 的圆角 Surface，看起来像浮在内容上的白卡片，
 *   而且播放页挂在 PolyTuneScaffold 的 Box 里、下面压着搜索页，圆角外面的透明缝隙会
 *   直接透出下层页面的文字（真机截图里出现过「晴天」的残影）。
 * - 封面宽度取 min(屏宽 * 0.62, 240dp)，不再铺满一屏：封面多吃一点高度，
 *   歌词区的可用高度就少一截，Apple Music 那种「一次能看到上下两行」的效果就没了。
 */
@Composable
fun NowPlayingScreen(
    song: Song?,
    actualPlatform: String,
    lyric: List<LineLyric>,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    buffering: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    isPreview: Boolean = false,
    /** 当前直链地址。用来从后缀反推真实容器格式：同一首「无损」可能是 flac 也可能是 m4a。 */
    endpointUrl: String? = null,
    /** 实际拿到的音质档位，解不出格式时退回显示它。 */
    quality: com.polytune.source.api.Quality? = null,
    /** 是否有上一首。队列为空时传 false，按钮置灰而不是崩溃。 */
    hasPrevious: Boolean = false,
    /** 是否有下一首。 */
    hasNext: Boolean = false,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    /** 打开播放列表抽屉。 */
    onOpenQueue: () -> Unit = {},
    /** 队列长度，只用来决定队列图标要不要置灰。 */
    queueSize: Int = 0,
    modifier: Modifier = Modifier,
) {
    val brand = song?.let { Color(it.platform.brand) } ?: MaterialTheme.colorScheme.primary
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // 整页底色：从品牌色的深色版本起手（不用纯色 0.55 alpha 叠在 surface 上，
    // 那样亮度随下层的 surface 变化，浅色主题下封面会发灰）。
    val bgTop = MaterialTheme.colorScheme.surface
    val bgMid = brand.darken(0.78f).copy(alpha = 0.38f).compositeOver(bgTop)
    val bgBottom = MaterialTheme.colorScheme.surface

    // 顶部「向上拖收起」的累计位移。只用来看要不要触发 onClose，
    // 不做位移跟手（跟手需要把整页往下平移，而播放页本身已经在 AnimatedVisibility 里带着
    // slideOutVertically 退场，两套位移叠加会互相打架）。
    var dragAccum by remember { mutableStateOf(0f) }

    // 松手瞬间的纵向速度（px/s，向上为负）。和 dragAccum 一样只用于判定，
    // 单独存一份是因为速度必须逐帧算（相邻两个事件的 dy/dt），不能只留最后一段。
    var velocityY by remember { mutableStateOf(0f) }
    var lastDragUptime by remember { mutableStateOf(0L) }

    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgMid, bgMid, bgBottom)))
            // 收起手势挂在整页上，用 nested scroll 把「是否该滚动」的判断让给内容。
            //
            // 为什么不再只挂在封面上：真机上从封面往下一点起手（标题、艺人那两行）就收不起来，
            // 因为手势区随 headerWeight 算出来的高度比视觉上的封面区还窄，手指很容易落在外面。
            // 挂整页就必须先解决和 LazyColumn 抢手势的问题，所以这里不用
            // detectVerticalDragGestures，而是自己吃 nested scroll 的 preScroll：
            // 纵向列表只要有可滚的余地（歌词区就是），它会在 preScroll 里消费掉位移，
            // 我们拿到的 available.y 接近 0，就不算收起位移；只有列表到顶、列表本身不滚，
            // 位移才会漏到这里来，正好对应「页面整体往上推」的语义。
            .nestedScroll(closeOnSwipeUpScroll(onClose = onClose))
            .pointerInput(onClose, onPrevious, onNext, hasPrevious, hasNext) {
                val closeThresholdPx = 120.dp.toPx()
                val switchThresholdPx = 64.dp.toPx()
                var horizontalDistance = 0f
                var switched = false
                detectHorizontalDragGestures(
                    onDragStart = { horizontalDistance = 0f; switched = false },
                    onDragEnd = { horizontalDistance = 0f; switched = false },
                    onDragCancel = { horizontalDistance = 0f; switched = false },
                ) { change, amount ->
                    if (!switched) horizontalDistance += amount
                    // 父级只接管明确的横滑；纵向仍交给歌词列表与上滑收起链路。
                    if (abs(horizontalDistance) > 12f) change.consume()
                    if (!switched && abs(horizontalDistance) >= switchThresholdPx) {
                        if (horizontalDistance < 0f && hasNext) onNext()
                        if (horizontalDistance > 0f && hasPrevious) onPrevious()
                        switched = true
                    }
                }
            }
            .pointerInput(onClose) {
                val closeThresholdPx = 120.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { dragAccum = 0f; velocityY = 0f; lastDragUptime = 0L },
                    onDragEnd = {
                        if (dragAccum > closeThresholdPx || -velocityY > 1200f) onClose()
                        dragAccum = 0f
                        velocityY = 0f
                        lastDragUptime = 0L
                    },
                    onDragCancel = { dragAccum = 0f; velocityY = 0f; lastDragUptime = 0L },
                ) { change, dy ->
                    if (dy < 0f) dragAccum -= dy
                    val t = change.uptimeMillis
                    if (lastDragUptime > 0L && t > lastDragUptime) {
                        val dt = (t - lastDragUptime).toFloat()
                        velocityY = dy / dt * 1000f
                    }
                    lastDragUptime = t
                    change.consume()
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 背景波纹：用两条正弦叠出流动感，随播放进度平移。
            // 颜色从品牌色的亮端取（亮色提纯），画在深色底上才看得见。
            val phase = (positionMs % 20_000) / 20_000f * 6.28f
            repeat(3) { i ->
                val y = size.height * (0.08f + i * 0.05f)
                val path = androidx.compose.ui.graphics.Path()
                var x = 0f
                path.moveTo(x, y)
                while (x < size.width) {
                    val yy = y + sin(x / size.width * 6.28f * 2 + phase + i) * 18f
                    path.lineTo(x, yy)
                    x += 8f
                }
                drawPath(
                    path,
                    color = Color.White.copy(alpha = 0.05f + i * 0.02f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f, cap = StrokeCap.Round),
                )
            }
        }

        Column(Modifier.fillMaxSize()) {
            // 顶栏：完全不透明（整个外框先铺满这块渐变），内部不放任何半透明覆盖层。
            // 渐变起点与整页保持同一套色，所以它读起来是「页面往上延续」，而不是一张独立卡片。
            NowPlayingTopBar(
                brand = brand,
                isPreview = isPreview,
                actualPlatform = actualPlatform,
                endpointUrl = endpointUrl,
                quality = quality,
                onClose = onClose,
                onDownload = onDownload,
                onOpenQueue = onOpenQueue,
                queueSize = queueSize,
            )

            // 上半部分按参考图改成「封面在左、歌名与歌手在右」：
            // 竖屏下单列居中会把封面撑到半屏，歌词只剩窄窄一条；横过来之后封面还会把歌名挤出屏幕。
            // 改成左右分栏后，封面高度受限、文字独立成列，歌词区能拿到更大的可用高度。
            val availableH = LocalConfiguration.current.screenHeightDp.dp
            val headerWeight = if (availableH < 700.dp) 1.0f else 0.9f
            BoxWithConstraints(
                Modifier
                    .fillMaxWidth()
                    .weight(headerWeight),
                contentAlignment = Alignment.CenterStart,
            ) {
                // 封面同时受宽度与高度约束：横屏（如平板 / 模拟器）里高度只有两百多 dp，
                // 只按宽度算会让封面把标题、艺人挤出可视区，出现“只有封面没有歌名”。
                val widthBudget = maxWidth * 0.52f
                val heightBudget = (maxHeight - 16.dp).coerceAtLeast(96.dp)
                val coverSize = minOf(widthBudget, heightBudget, 220.dp)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                ) {
                    SongCover(song = song, brand = brand, size = coverSize)
                    Spacer(Modifier.width(16.dp))
                    Column(
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            song?.title ?: "未在播放",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            song?.artistsText.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!song?.album.isNullOrBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                song?.album.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            LyricStrip(
                lyric = lyric,
                positionMs = positionMs,
                activeColor = brand.liftForDark(),
                onLineClick = { line -> onSeek(line.timeMs.toLong()) },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            // 进度 + 控制条：同一块深色底，不做独立卡片
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                val shown = if (dragging) dragValue else positionMs.toFloat()
                Slider(
                    value = shown.coerceIn(0f, (durationMs.takeIf { it > 0 } ?: 1).toFloat()),
                    onValueChange = {
                        dragging = true
                        dragValue = it
                    },
                    onValueChangeFinished = {
                        dragging = false
                        onSeek(dragValue.toLong())
                    },
                    valueRange = 0f..((durationMs.takeIf { it > 0 } ?: 1).toFloat()),
                    colors = SliderDefaults.colors(
                        thumbColor = brand.liftForDark(),
                        activeTrackColor = brand.liftForDark(),
                    ),
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        msToText(shown.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        msToText(durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp, bottom = 14.dp),
                ) {
                    // 上一首：没有目标时 enabled=false 且 alpha 0.38。
                    // 置灰而不是隐藏，是为了让三个按钮的位置固定，用户不会因为「少一个键」而误点。
                    IconButton(
                        onClick = onPrevious,
                        enabled = hasPrevious,
                        modifier = Modifier
                            .size(56.dp)
                            .alpha(if (hasPrevious) 1f else 0.38f),
                    ) {
                        Icon(
                            Icons.Rounded.SkipPrevious,
                            contentDescription = "上一首",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.width(24.dp))
                    Surface(
                        shape = CircleShape,
                        color = brand.liftForDark(),
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onToggle),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (buffering) {
                                com.polytune.ui.theme.ExpressiveLoading(
                                    color = Color.White,
                                    modifier = Modifier.size(28.dp),
                                )
                            } else {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = if (isPlaying) "暂停" else "播放",
                                    tint = Color.White,
                                    modifier = Modifier.size(36.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(24.dp))
                    IconButton(
                        onClick = onNext,
                        enabled = hasNext,
                        modifier = Modifier
                            .size(56.dp)
                            .alpha(if (hasNext) 1f else 0.38f),
                    ) {
                        Icon(
                            Icons.Rounded.SkipNext,
                            contentDescription = "下一首",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 顶栏。整条横条是**不透明**的：外层先铺一层与整页同源的垂直渐变，再把状态栏 inset 一起吃进去。
 *
 * 为什么不用 Surface 的半透明底：播放页是盖在 PolyTuneScaffold 之上的浮层，下层还挂着搜索页，
 * 任何 alpha < 1 的底色都会让下层的文字透上来（真机出现过 qingtian/晴天 残影），
 * 圆角还会在四角留出透明缝隙，等于给残影开了个窗口。
 */
@Composable
private fun NowPlayingTopBar(
    brand: Color,
    isPreview: Boolean,
    actualPlatform: String,
    endpointUrl: String?,
    quality: com.polytune.source.api.Quality?,
    onClose: () -> Unit,
    onDownload: () -> Unit,
    onOpenQueue: () -> Unit = {},
    queueSize: Int = 0,
) {
    Box(
        Modifier
            .fillMaxWidth()
            // statusBarsPadding 放在背景里面、Row 外面：背景要盖住状态栏那一块，
            // 否则时间/信号图标会压在渐变外面，露出页面底下的东西。
            .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface)))
            .statusBarsPadding(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "收起", modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.size(4.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isPreview) "正在播放 · 试听片段" else "正在播放",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // 徽标只在真拿着一个直链时才显示：没有地址就说明还没解析出来，
                    // 这时候显示「未知格式」只会误导。试听片段照常显示格式，两者不冲突。
                    if (!endpointUrl.isNullOrBlank()) {
                        Spacer(Modifier.size(8.dp))
                        FormatBadgeChip(endpointUrl, quality)
                    }
                }
                if (actualPlatform.isNotBlank()) {
                    com.polytune.source.api.Platform.of(actualPlatform)?.let { PlatformBadge(it) }
                }
            }
            IconButton(onClick = onOpenQueue) {
                // 队列为空时也放出来（置灰）：按钮时隐时现会让人以为界面在抖，
                // 置灰至少明确告诉用户「现在没有下一首」。
                Icon(
                    Icons.Rounded.QueueMusic,
                    contentDescription = "播放列表",
                    tint = if (queueSize > 1) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    },
                )
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Rounded.Download, contentDescription = "下载")
            }
        }
    }
}

/** 封面。尺寸由调用方算好传进来（min(屏宽 * 0.62, 240dp)），这里只负责圆角与阴影。 */
@Composable
private fun SongCover(song: Song?, brand: Color, size: Dp) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        shadowElevation = 18.dp,
        modifier = Modifier.size(size),
    ) {
        if (song?.coverUrl.isNullOrBlank()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.linearGradient(listOf(brand, brand.copy(alpha = 0.3f)))),
            )
        } else {
            AsyncImage(
                model = song?.coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 格式 + 音质徽标。颜色区分能力：能播用品牌主色描边，需要软解但现在放不了用 error 色，
 * 这样用户在点播放之前就能看到「这条链接其实解不了」，而不是听完一次报错才发现。
 */
@Composable
private fun FormatBadgeChip(url: String, quality: com.polytune.source.api.Quality?) {
    val fmt = com.polytune.player.formatOfUrl(url)
    val q = quality ?: com.polytune.source.api.Quality.STANDARD
    val text = if (fmt != null) "${fmt.label} · ${q.label}" else q.label
    val playable = fmt == null || com.polytune.player.canPlay(fmt.ext)
    val tone = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        shape = RoundedCornerShape(50),
        color = tone.copy(alpha = 0.12f),
        contentColor = tone,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * 单行歌词的视觉参数。抽成数据类是为了让「距离 → 字号/透明度」这张表只有一份：
 * 之前字号写在 Text 的 style 里、透明度写在 alpha 里、缩放写在 scale 里，三份阈值各写一遍，
 * 改一个距离档要同步改三处，很容易漏。
 */
private data class LyricStyle(
    val alpha: Float,
    val scale: Float,
    val weight: FontWeight,
)

private fun lyricStyleFor(distance: Int): LyricStyle = when (distance) {
    0 -> LyricStyle(1f, 1f, FontWeight.Bold)
    1 -> LyricStyle(0.55f, 0.92f, FontWeight.Medium)
    2 -> LyricStyle(0.28f, 0.86f, FontWeight.Normal)
    else -> LyricStyle(0.15f, 0.86f, FontWeight.Normal)
}

/**
 * 歌词区。当前行垂直居中，切行时用 spring 过渡字号/透明度/缩放，
 * 并用 animateScrollToItem 把当前行滚到视口中心（带 center offset）。
 */
@Composable
private fun LyricStrip(
    lyric: List<LineLyric>,
    positionMs: Long,
    activeColor: Color,
    onLineClick: (LineLyric) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (lyric.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "暂无歌词",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    // 当前行下标用 derivedStateOf 包一层：进度每 200ms 推一次，但行号只在跨过某一行时才变。
    // 不包的话每个 tick 都会重组整个 LazyColumn 的 key 计算，白烧帧。
    //
    // 这里必须用 rememberUpdatedState 托住 positionMs：derivedStateOf 的 lambda 只在
    // remember(lyric) 首次组合时创建，闭包里捕获的是那一刻的 positionMs。之后进度变化，
    // 闭包读到的还是旧值，于是「当前行」永远停在 0、LaunchedEffect(current) 也就永不重启，
    // 表现就是歌词一行都不滚。这是之前排查了很久才定位到的真凶。
    val positionState = rememberUpdatedState(positionMs)
    val currentState = remember(lyric) {
        derivedStateOf { lyric.indexOfLast { it.timeMs <= positionState.value }.coerceAtLeast(0) }
    }
    val current by currentState

    val listState = rememberLazyListState()
    // 当前行居中的偏移量。animateScrollToItem 只接受一个固定的 scrollOffset，
    // 而「半屏减半行高」依赖实际测量出来的视口高度，所以先按 dp 估一个基准，
    // 拿到 layoutInfo 之后再用视口高度的一半覆盖它——首帧之前 layoutInfo 是空的，
    // 直接读会拿到 0，整段偏移就丢了。
    val density = LocalDensity.current
    var lyricCenterOffsetPx by remember { mutableStateOf(with(density) { LyricCenterOffsetDp.dp.roundToPx() }) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.viewportSize.height }
            .filter { it > 0 }
            .collect { h ->
                // 行高约 36dp，取一半；视口高度减掉它，就是让当前行落到中线需要的滚动量。
                lyricCenterOffsetPx = h / 2 - with(density) { 18.dp.roundToPx() }
            }
    }

    LaunchedEffect(current) {
        // 两个容易错的地方，之前就是错在这儿，表现是「歌词完全不滚」：
        //
        // 1. 列表第 0 项是顶上那块半屏空白 Spacer，歌词第 i 行实际在 item 位 i+1，
        //    直接传 current 会一直滚到上一行，看起来总是慢半拍。
        // 2. animateScrollToItem 的 scrollOffset 正方向是「继续往前滚」，也就是把目标项
        //    往视口上方推出去。想让当前行停在视口中线，得往上留出「半屏减半行高」的空白，
        //    也就是传负值。之前传的是正值，目标行被推到屏幕外，整片歌词看着像不动。
        val targetOffset = -lyricCenterOffsetPx
        runCatching {
            listState.animateScrollToItem(current + 1, targetOffset)
        }.onFailure {
            // 歌词在播放中途刷新过时，列表可能还没完成首帧测量，animate 会抛 IndexOutOfBounds；
            // 这时退回硬滚，至少保证位置对，不因为一次动画异常把播放页带崩。
            runCatching { listState.scrollToItem(current + 1, targetOffset) }
        }
    }

    Box(modifier) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            // 上下各垫半屏空白：第一行/最后一行也能滚到视口中心，
            // 否则开头和结尾那几句永远只能贴着边缘。
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Spacer(Modifier.height(LyricPadHalfScreenDp.dp)) }
            itemsIndexed(lyric) { i, line ->
                val distance = abs(i - current)
                val target = lyricStyleFor(distance)
                // spring(dampingRatio = 0.8, stiffness = 380)：阻尼 0.8 基本不过冲，
                // 刚度 380 略硬于 Default，切行跟随歌词节奏（一句 3~5 秒）时不会显得黏。
                val spec = spring<Float>(dampingRatio = 0.8f, stiffness = 380f)
                val alpha by animateFloatAsState(target.alpha, spec, label = "lyA$i")
                val scale by animateFloatAsState(target.scale, spec, label = "lyS$i")
                val isActive = distance == 0
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        // 缩放用 graphicsLayer 而不是 size：graphicsLayer 只走绘制阶段，
                        // 不触发重新测量，十几行同时做 spring 缩放也不会让 LazyColumn 重排。
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .alpha(alpha)
                        // 点击整行跳转：点哪句就跳到哪句的时间点。
                        .clickable { onLineClick(line) },
                ) {
                    Text(
                        line.text.ifBlank { "♪" },
                        style = if (isActive) MaterialTheme.typography.titleLarge
                        else MaterialTheme.typography.titleMedium,
                        fontWeight = target.weight,
                        color = if (isActive) activeColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    line.translation?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(LyricPadHalfScreenDp.dp)) }
        }
    }
}

/**
 * 首帧之前（layoutInfo 还是空的）用的兜底居中偏移。
 * 拿到真实视口高度后会被「视口高度 / 2 - 半行高」覆盖，这里只是不让第一次滚动贴边。
 */
private const val LyricCenterOffsetDp = 150
private const val LyricPadHalfScreenDp = 110

/**
 * 「整页往上推就收起」的嵌套滚动实现。
 *
 * 只认 preScroll（子组件还没消费时的那一段），并且只认向上：
 * - 如果歌词列表还有可滚的余地，它会在随后的 postScroll/自身滚动里把 available.y 吃干净，
 *   我们这里看到的位移就是 0，不会误收起；
 * - 列表到顶后继续上推，位移没人吃，累计超过阈值就收起；
 * - 向下拉一律不管（available.y > 0 提都不提），避免和「滚回顶部」的操作打架。
 *
 * 用 preScroll 而不是自己挂 detectVerticalDragGestures 挂在整页，是因为后者完全不参与
 * nested scroll 协议，会把歌词列表的滚动整个吞掉。
 */
private fun closeOnSwipeUpScroll(onClose: () -> Unit): NestedScrollConnection =
    object : NestedScrollConnection {
        private val thresholdPx = 180f

        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // available.y < 0 表示手指向上推。只累计向上的部分，
            // 而且是负位移累加，所以用 -= 把它变成正数去和阈值比。
            if (available.y < 0f) {
                accumulated -= available.y
                if (accumulated > thresholdPx) {
                    accumulated = 0f
                    onClose()
                }
            } else if (available.y > 0f) {
                // 反向一拉就清零：否则来回搓几下也能凑够阈值，手感会很廉价。
                accumulated = 0f
            }
            // 不消费任何位移，原样交给歌词列表，滚动不受影响。
            return Offset.Zero
        }

        private var accumulated = 0f
    }

/**
 * 品牌色压暗。直接 copy(alpha=) 是「透明」，压到深色背景上会露出底下的 surface，
 * 在浅色主题下反而发灰；乘 RGB 才是真的变暗，和背景混在一起更接近 Apple Music 的观感。
 */
private fun Color.darken(factor: Float): Color = Color(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
    alpha = 1f,
)

/** 深底上的高亮色：品牌色如果本身太暗（网易云红接近黑），直接拿来当文字色会糊在背景里，提亮一档。 */
private fun Color.liftForDark(): Color = Color(
    red = red + (1f - red) * 0.35f,
    green = green + (1f - green) * 0.35f,
    blue = blue + (1f - blue) * 0.35f,
    alpha = 1f,
)

private fun msToText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

