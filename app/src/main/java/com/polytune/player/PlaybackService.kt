package com.polytune.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.polytune.AppGraph
import com.polytune.MainActivity
import com.polytune.PolyTuneApp

/**
 * 后台播放服务。用 MediaSessionService 拿到系统媒体控制、锁屏卡片、蓝牙按键。
 *
 * 三个必须踩准的点：
 * 1. ExoPlayer 只在这里建一次，并挂到 AppGraph 里全局唯一的 PlaybackEngine 上，
 *    界面层永远不自己建 player，界面状态与 MediaSession 天然是同一个实例。
 * 2. MediaSessionService 默认只在前台播放时才自己 startForeground，
 *    被 startForegroundService() 拉起后如果不立刻 startForeground，系统会在 5 秒后
 *    RemoteServiceException 直接杀进程。所以 onCreate 里主动挂一个「准备中」前台通知。
 * 3. 通知用 MediaStyle 并绑到 session token，播放后 media3 会接管样式与控件。
 */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var engine: PlaybackEngine? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val shared = AppGraph.of(application as PolyTuneApp).engine
        // 渲染器工厂必须与 PlaybackEngine 共用同一个函数，否则这里建出来的实例
        // 仍然只有系统 MediaCodec，而播放链路偏偏走的是服务里的这个实例。
        val p = ExoPlayer.Builder(this, PlaybackEngine.renderersFactory(this))
            .setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true, // 请求音频焦点，否则暂停后不会被别的应用抢回
            )
            .build()
        // 单曲由引擎交给 ExoPlayer，队列前进由 onEnded 回调完成，禁止 ExoPlayer 自己循环。
        p.repeatMode = Player.REPEAT_MODE_OFF
        p.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // 缓冲期间不提前退前台，避免 startForegroundService 的启动窗口到期。
                if (!playWhenReady && p.playbackState != Player.STATE_BUFFERING) {
                    stopForeground(false)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                    stopForeground(false)
                }
            }
        })
        engine = shared.also { it.attach(p) }
        engine?.holder = this

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, p)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> {
                    // 直链由界面侧解析好再交给播放器，这里只补齐通知栏需要的元数据
                    val patched = mediaItems.map { patch(it) }.toMutableList()
                    return Futures.immediateFuture(patched)
                }

                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    // 队列不做持久化，恢复时交回空列表，界面会继续上次那首
                    return Futures.immediateFuture(
                        MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0L),
                    )
                }
            })
            .build()

        // MediaSessionService 后续会用相同 ID 换成带控件的媒体通知。
        addSession(session!!)
        ensureForeground(session!!)
    }

    /** 立刻进入前台，并让系统媒体面板从一开始就绑定同一个 Session。 */
    private fun ensureForeground(currentSession: MediaSession) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "播放", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "显示当前播放的歌曲"
                    setShowBadge(false)
                },
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LopMusic")
            .setContentText("正在准备播放")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openApp)
            .setOngoing(true)
            // 这里刻意不做 MediaStyle：MediaSession.sessionCompat 在 media3 里是包内可见，
            // 取不到 token。占位通知只负责让服务合法进入前台，真正的媒体控件由
            // MediaSessionService 自己的 DefaultMediaNotificationProvider 在有了媒体项之后接管。
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun patch(item: MediaItem): MediaItem {
        val md = item.mediaMetadata
        val title = md.title ?: item.mediaId
        return item.buildUpon()
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(md.artist)
                    .setAlbumTitle(md.albumTitle)
                    .setArtworkUri(md.artworkUri)
                    .build(),
            )
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        engine?.holder = null
        engine?.release()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "polytune_playback"
        const val NOTIFICATION_ID = 1001

        /** Android 8+ 起前台服务必须显式 startForegroundService。 */
        fun start(ctx: android.content.Context) {
            val intent = Intent(ctx, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(intent)
            else ctx.startService(intent)
        }
    }
}
