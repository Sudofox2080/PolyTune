package com.polytune.ui.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.polytune.source.api.Song
import com.polytune.ui.theme.MorphingShape

/**
 * 底部迷你播放条。它跟主页一起常驻，是「从列表到全屏播放器」的过渡锚点。
 * 形状随播放态变化（播放中圆角更大），点按进全屏。
 */
@Composable
fun MiniPlayer(
    song: Song?,
    isPlaying: Boolean,
    buffering: Boolean,
    progress: Float,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (song == null) return
    val corner by animateDpAsState(
        if (isPlaying) 26.dp else 20.dp,
        spring(dampingRatio = 0.6f, stiffness = 420f),
        label = "miniCorner",
    )
    val brand = Color(song.platform.brand)
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(corner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (buffering) {
                MorphingShape(
                    size = 40.dp,
                    color = brand,
                    modifier = Modifier.padding(4.dp),
                )
            } else {
                CoverImage(song.coverUrl, Modifier.size(40.dp), corner = 12.dp)
            }
            androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
            androidx.compose.foundation.layout.Column(
                Modifier.weight(1f),
            ) {
                Text(
                    song.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    song.artistsText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FilledIconButton(
                onClick = onToggle,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = brand),
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(50)),
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
