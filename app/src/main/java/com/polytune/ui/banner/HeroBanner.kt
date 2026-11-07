package com.polytune.ui.banner

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.polytune.source.api.BannerItem
import com.polytune.ui.common.PlatformBadge
import com.polytune.ui.theme.MorphingShape
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.absoluteValue

/**
 * 首页 hero banner。三个细节撑起「流畅」：
 * 1. 视差缩放：offscreen 页按偏移量缩到 0.88 并淡出，只用 graphicsLayer 不改布局，滑动不掉帧。
 * 2. 自动轮播：用 snapshotFlow 监听 settledPage 而不是把 isScrollInProgress 放进 LaunchedEffect 的 key。
 *    之前那样写在用户一拨动时就会重启 effect、delay 从头算，轮播看起来像卡死不动。
 * 3. 指示器：选中项用弹簧把胶囊拉长到 22dp，其余保持 6dp 圆点。
 */
@Composable
fun HeroBanner(
    items: List<BannerItem>,
    onTap: (BannerItem) -> Unit,
    modifier: Modifier = Modifier,
    autoScrollMs: Long = 4_000,
) {
    if (items.isEmpty()) {
        BannerSkeleton(modifier)
        return
    }
    val pager = rememberPagerState(pageCount = { items.size })

    // 停稳一页就重新计时：这样手动滑动会重置倒计时，而不是把循环掐断。
    LaunchedEffect(items.size) {
        if (items.size <= 1) return@LaunchedEffect
        snapshotFlow { pager.settledPage }.collectLatest {
            delay(autoScrollMs)
            if (!pager.isScrollInProgress) {
                val next = (pager.settledPage + 1) % items.size
                pager.animateScrollToPage(next, animationSpec = tween(650))
            }
        }
    }

    Column(modifier) {
        HorizontalPager(
            state = pager,
            contentPadding = PaddingValues(horizontal = 20.dp),
            pageSpacing = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .height(196.dp),
        ) { page ->
            val offset = ((pager.currentPage - page) + pager.currentPageOffsetFraction)
                .absoluteValue.coerceIn(0f, 1f)
            val scale by animateFloatAsState(1f - 0.12f * offset, tween(220), label = "bannerScale")
            val item = items[page]
            Surface(
                shape = RoundedCornerShape(30.dp),
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 1f - 0.35f * offset
                    }
                    .clip(RoundedCornerShape(30.dp))
                    .clickable { onTap(item) },
            ) {
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Black.copy(alpha = 0.15f),
                                    1f to Color.Black.copy(alpha = 0.72f),
                                ),
                            ),
                    )
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PlatformBadge(item.platform)
                            if (item.subtitle.isNotBlank()) {
                                Spacer(Modifier.size(8.dp))
                                Text(
                                    item.subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White.copy(alpha = 0.85f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.size(6.dp))
                        Text(
                            item.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.indices.forEach { i ->
                val selected = i == pager.currentPage
                val w by animateFloatAsState(
                    if (selected) 22f else 6f,
                    animationSpec = androidx.compose.animation.core.spring(
                        dampingRatio = 0.6f,
                        stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
                    ),
                    label = "dotW",
                )
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(width = w.dp, height = 6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                        ),
                )
            }
        }
    }
}

/* 注释在此前的一次错误编码写入中损坏，已丢弃 */
@Composable
fun BannerSkeleton(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(196.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MorphingShape(
                size = 72.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                strokeWidth = 3.dp,
            )
        }
    }
}
