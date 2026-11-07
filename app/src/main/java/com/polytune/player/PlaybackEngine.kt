package com.polytune.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.polytune.core.proto.ProtocolMiddleware
import com.polytune.source.api.PlayEndpoint
import com.polytune.source.api.Protocol
import com.polytune.source.api.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ExoPlayer 封装。每个源的播放地址都带自己的 UA/Referer/Cookie，
 * 而 ExoPlayer 的请求头是工厂级的，所以这里用一个可变更 headers 的工厂，
 * prepare 前更新 header 表，再按协议包成 Progressive / HLS / DASH MediaSource。
 */
class PlaybackEngine(private val context: Context) {

    data class NowPlaying(
        val song: Song? = null,
        val endpoint: PlayEndpoint? = null,
        val actualPlatform: String = "",
        val isPlaying: Boolean = false,
        val buffering: Boolean = false,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 当前生效的请求头，prepare 时整体替换。 */
    @Volatile
    private var dynamicHeaders: Map<String, String> = emptyMap()

    private val httpFactory = object : DataSource.Factory {
        override fun createDataSource(): DataSource {
            val headers = dynamicHeaders
            val inner = DefaultHttpDataSource.Factory()
                .setUserAgent(headers["User-Agent"] ?: DEFAULT_UA)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(30_000)
                .setDefaultRequestProperties(headers)
                .createDataSource()
            return object : DataSource {
                private var bytes = 0L

                override fun addTransferListener(transferListener: androidx.media3.datasource.TransferListener) {
                    inner.addTransferListener(transferListener)
                }

                override fun open(dataSpec: DataSpec): Long {
                    val len = inner.open(dataSpec)
                    com.polytune.core.download.TraceCtx.app?.let { c ->
                        val ds = inner as? DefaultHttpDataSource
                        com.polytune.core.download.DownloadTrace.log(
                            c,
                            "http open uri=${dataSpec.uri.toString().take(160)} " +
                                "pos=${dataSpec.position} len=${dataSpec.length} " +
                                "resp=${ds?.responseCode} ctype=${ds?.responseHeaders?.get("Content-Type")} " +
                                "ret=${len}",
                        )
                    }
                    return len
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val n = inner.read(buffer, offset, length)
                    if (n > 0) bytes += n
                    return n
                }

                override fun getUri(): Uri? = inner.uri

                override fun close() {
                    com.polytune.core.download.TraceCtx.app?.let { c ->
                        com.polytune.core.download.DownloadTrace.log(c, "http close read=$bytes")
                    }
                    inner.close()
                }

                override fun getResponseHeaders(): Map<String, List<String>> =
                    inner.responseHeaders
            }
        }
    }

    private val sourceFactory: DataSource.Factory = DefaultDataSource.Factory(context, httpFactory)

    /** 当前挂载的 Service 实例，界面侧发给它用来触发前台服务启动。 */
    @Volatile var holder: android.app.Service? = null

    private var player: ExoPlayer? = null

    /** 当前单曲自然播完时回调。队列持有者在这里推进到下一首。 */
    @Volatile
    var onEnded: (() -> Unit)? = null

    val currentPlayer: ExoPlayer? get() = player

    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state.asStateFlow()

    /**
     * 绑定一个 ExoPlayer。服务里创建、引擎只负责挂监听，
     * 这样 MediaSession 与播放器是同一个实例，通知栏与界面天然同步。
     */
    fun attach(existing: ExoPlayer = ExoPlayer.Builder(context, renderersFactory(context))
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
    ) {
        player = existing
        existing.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.value = _state.value.copy(isPlaying = isPlaying)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val p = player ?: return
                _state.value = _state.value.copy(
                    buffering = playbackState == Player.STATE_BUFFERING,
                    durationMs = p.duration.coerceAtLeast(0),
                )
                // ExoPlayer 只装了这一首，自然结束后必须交给队列持有者换歌。
                // 保留 STATE_ENDED，不用 seekTo(0) 假装重播，避免回调被再次触发。
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("PlaybackEngine", "player error", error)
                com.polytune.core.download.TraceCtx.app?.let { c ->
                    com.polytune.core.download.DownloadTrace.log(
                        c,
                        "player error code=${error.errorCodeName} msg=${error.message} " +
                            "cause=${error.cause?.javaClass?.simpleName}:${error.cause?.message}",
                    )
                }
                _state.value = _state.value.copy(error = error.message ?: error.errorCodeName)
            }
        })
        scope.launch {
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0),
                        durationMs = p.duration.coerceAtLeast(0),
                    )
                }
                delay(200)
            }
        }
    }

    /**
     * 播放一首歌。直链解析（多协议中间件）是挂起操作，SMB 要在本地起代理，
     * 所以统一在 IO 线程完成后再回主线程设置 MediaSource。
     */
    suspend fun prepare(song: Song, ep: PlayEndpoint, actualPlatform: String) {
        android.util.Log.i("PlaybackEngine", "prepare start: ${song.title} via $actualPlatform player=${player != null}")
        // 界面点播放时服务可能还没起来：先确保前台服务启动，它会在 onCreate 里把 player 挂上来。
        // 冷启动一台小米机的 MediaSessionService 实测要 200~600ms，之前只等 1 秒就放弃，
        // 结果第一次点击被吞成「播放器未就绪」，用户必须再点一次播放键。
        if (player == null) {
            val started = runCatching { PlaybackService.start(context) }.isSuccess
            android.util.Log.i("PlaybackEngine", "service start requested=$started")
            var waited = 0L
            while (player == null && waited < SERVICE_WAIT_MS) {
                delay(50)
                waited += 50
            }
            android.util.Log.i("PlaybackEngine", "waited ${waited}ms player=${player != null}")
        }
        val prepared: ProtocolMiddleware.Prepared = ProtocolMiddleware.prepare(context, ep)
        android.util.Log.i("PlaybackEngine", "protocol=${prepared.protocol} uri=${prepared.uri}")
        scope.launch {
            val p = player ?: run {
                android.util.Log.e("PlaybackEngine", "player still null after service start")
                _state.value = NowPlaying(song = song, error = "播放器未就绪，请再点一次播放", actualPlatform = actualPlatform)
                return@launch
            }
            dynamicHeaders = prepareHeaders(ep.headers, prepared.headers)
            com.polytune.core.download.TraceCtx.app?.let { c ->
                com.polytune.core.download.DownloadTrace.log(
                    c,
                    "engine headers=${dynamicHeaders.entries.joinToString(",") { "${it.key}=${it.value.take(24)}" }}",
                )
            }
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(prepared.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artistsText)
                        .setAlbumTitle(song.album)
                        .setArtworkUri(
                            song.coverUrl?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) },
                        )
                        .build(),
                )
                .build()
            // 源里很多直链把 protocol 一律填 HTTP，地址本身却是 .m3u8/.mpd 的分片清单。
            // 用 ProgressiveMediaSource 去喂 m3u8 只会得到一行 Extractor 解析错误，
            // 所以这里按最终 URI 再推一次协议，协议字段只作为 HLS/DASH 的显式声明。
            val finalProtocol = protocolFor(prepared.uri, prepared.protocol)
            android.util.Log.i("PlaybackEngine", "source protocol=${prepared.protocol} -> $finalProtocol")
            // 真机 logcat 会被系统吞掉自定义 tag，所以这一行同时落进应用自己的日志文件：
            // 出问题时能直接看到「当时有没有软解、这个后缀能不能放」。
            com.polytune.core.download.TraceCtx.app?.let { c ->
                val ext = extOfUrl(prepared.uri)
                com.polytune.core.download.DownloadTrace.log(
                    c,
                    "engine source ext=$ext fmt=${formatOfUrl(prepared.uri)?.label} " +
                        "canPlay=${canPlay(ext)} ffmpeg=$isFfmpegRendererLoaded src=$finalProtocol",
                )
            }
            val source = when (finalProtocol) {
                Protocol.HLS -> HlsMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
                Protocol.DASH -> DashMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
                // 未知扩展名不提前判死：ProgressiveMediaSource 会让 Extractor 去嗅探容器头，
                // flac/ape/mka 这类「后缀不对但内容认得」的文件就靠这一步救回来。
                else -> ProgressiveMediaSource.Factory(sourceFactory).createMediaSource(mediaItem)
            }
            p.setMediaSource(source, true)
            p.prepare()
            p.playWhenReady = true
            _state.value = NowPlaying(
                song = song,
                endpoint = ep,
                actualPlatform = actualPlatform,
                buffering = true,
            )
        }
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun seekTo(ms: Long) {
        val p = player ?: return
        p.seekTo(ms.coerceIn(0, p.duration.coerceAtLeast(0)))
        _state.value = _state.value.copy(positionMs = ms)
    }

    fun release() {
        runCatching { player?.release() }
        player = null
    }

    companion object {
        /** 等前台服务把 ExoPlayer 挂上来的上限。 */
        private const val SERVICE_WAIT_MS = 3_000L

        /** 扩展渲染器里 ffmpeg 那一支的类名，判定「软解到底装没装进来」只认它。 */
        private const val FFMPEG_RENDERER_CLASS = "androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer"

        /**
         * 本次进程里 ffmpeg 扩展渲染器是否真的加载成功。
         *
         * Media3 的 DefaultRenderersFactory 是用 Class.forName 反射找 FfmpegAudioRenderer 的：
         * 编译期没有编译时常量可以直接判断，所以这里同样用反射探一次，结果只算一次。
         * 用真实探测而不是写死常量，是为了让「APK 里到底有没有软解」这件事可验证——
         * ffmpeg 模块在 Google Maven 上没有产物（见 build.gradle.kts 注释），只能靠源码编进产物，
         * 一旦没编进去，这里立刻返回 false，界面上的能力标识也会跟着说实话。
         */
        val isFfmpegRendererLoaded: Boolean by lazy {
            runCatching { Class.forName(FFMPEG_RENDERER_CLASS) }.isSuccess
        }

        /**
         * 播放器的渲染器工厂。播放引擎与前台服务两处必须共用这一个函数：
         * 两处各写一份配置，迟早会出现「界面里能用扩展渲染器、服务里用不了」的漂移。
         *
         * 三点配置的理由：
         * - PREFER：只要扩展渲染器能处理，就先给扩展渲染器。音频解码在软解上质量稳定且不占
         *   MediaCodec 实例（小米这类机器上系统解码器实例很少），少一路抢资源。
         * - setEnableDecoderFallback(true)：硬解失败（常见于非 16k 对齐的 AAC、异常采样率）时
         *   自动回退，否则用户看到的是「这首歌在别的播放器能放、在这不能放」。
         * - mediaCodecSelector 保持默认：系统默认的 MediaCodecUtil 选择器已经会按编码画像
         *   过滤不支持的 profile，自己再包一层反而会把厂商的私有解码器排除掉。
         */
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        fun renderersFactory(context: Context): RenderersFactory =
            DefaultRenderersFactory(context)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                .setEnableDecoderFallback(true)

        const val DEFAULT_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"

        /** 合并源头、中间件头，并保证一定有 UA，避免部分 CDN 直接 403。 */
        private fun prepareHeaders(
            endpoint: Map<String, String>,
            middleware: Map<String, String>,
        ): Map<String, String> {
            // user/pass 是中间件取认证信息的内部字段，不能作为 HTTP 请求头发给服务器。
            val out = LinkedHashMap<String, String>()
            endpoint.forEach { (key, value) ->
                if (!key.equals("user", true) && !key.equals("pass", true)) out[key] = value
            }
            out["User-Agent"] = endpoint.entries.firstOrNull {
                it.key.equals("User-Agent", true)
            }?.value ?: DEFAULT_UA
            out.putAll(middleware)
            return out
        }
    }
}
