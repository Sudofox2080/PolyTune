package com.polytune

import android.app.Application
import com.polytune.core.crypto.CredentialVault
import com.polytune.core.db.PolyDatabase
import com.polytune.core.proto.SmbProxy
import com.polytune.core.util.RecallStore
import com.polytune.player.PlaybackEngine
import com.polytune.source.agg.Aggregator
import com.polytune.source.api.BaseMusicSource
import com.polytune.source.api.MusicSource
import com.polytune.source.api.Platform
import com.polytune.source.kugou.KugouSource
import com.polytune.source.netease.NeteaseSource
import com.polytune.source.qq.QqSource

class PolyTuneApp : Application() {

    lateinit var graph: AppGraph
        private set

    /** 播放服务把播放状态回抛给界面层，避免 UI 直接持有 Service。 */
    var notifyPlaybackState: ((Boolean) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        com.polytune.core.download.TraceCtx.app = applicationContext
        graph = AppGraph(this)
    }

    override fun onTerminate() {
        SmbProxy.stopAll()
        super.onTerminate()
    }
}

/**
 * 手写依赖图，避免为单一模块引入 Hilt 的构建开销。所有对象单例，生命周期跟随 Application。
 */
class AppGraph(private val app: Application) {

    val db: PolyDatabase by lazy { PolyDatabase.build(app) }
    /**
     * 播放引擎全局唯一：UI 拿它解析直链、读状态，Service 拿它挂到同一个 ExoPlayer 上。
     * 这样界面和 MediaSession 永远是同一个 player，不会出现「界面在播、通知栏没动静」。
     */
    val engine: PlaybackEngine by lazy { PlaybackEngine(app) }

    val aggregator: Aggregator by lazy {
        val map: Map<Platform, MusicSource> = listOf(
            QqSource(com.polytune.core.net.SourceHttp(Platform.QQ)),
            NeteaseSource(com.polytune.core.net.SourceHttp(Platform.NETEASE)),
            KugouSource(com.polytune.core.net.SourceHttp(Platform.KUGOU)),
        ).associateBy { it.platform }
        Aggregator(map)
    }

    init {
        CredentialVault.init(app)
        RecallStore.init(app)
        // 启动时恢复已保存凭证
        val vault = CredentialVault.get()
        aggregator.sources.values.filterIsInstance<BaseMusicSource>().forEach { src ->
            src.restore(vault.get(src.platform))
        }
        aggregator.refreshLoginState()
    }

    companion object {
        fun of(app: Application): AppGraph = (app as PolyTuneApp).graph
    }
}
