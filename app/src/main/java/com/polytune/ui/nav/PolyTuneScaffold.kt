package com.polytune.ui.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polytune.source.api.Song
import com.polytune.ui.account.AccountScreen
import com.polytune.ui.account.AccountViewModel
import com.polytune.ui.common.MiniPlayer
import com.polytune.ui.debug.ProtocolProbe
import com.polytune.ui.detail.DetailScreen
import com.polytune.ui.detail.DetailViewModel
import com.polytune.ui.download.DownloadScreen
import com.polytune.ui.download.DownloadViewModel
import com.polytune.ui.home.HomeScreen
import com.polytune.ui.home.HomeViewModel
import com.polytune.ui.library.LibraryScreen
import com.polytune.ui.library.LibraryViewModel
import com.polytune.ui.play.NowPlayingScreen
import com.polytune.ui.play.PlayViewModel
import com.polytune.ui.queue.QueueSheet
import com.polytune.ui.queue.QueueViewModel
import com.polytune.ui.search.SearchScreen
import com.polytune.ui.search.SearchViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面导航状态。没有引入 navigation-compose，因为整个应用只有一层页签加一个详情覆盖层，
 * 用一个 StateFlow 表达，转场动画可以按内容类型单独设计（页签横滑、详情上推、播放器上滑）。
 */
class NavState {
    private val _tab = MutableStateFlow(Tab.HOME)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    private val _detail = MutableStateFlow<Song?>(null)
    val detail: StateFlow<Song?> = _detail.asStateFlow()

    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    /**
     * 协议自检页开关。账号页上的入口已经移除，这个字段先留着：
     * 它仍是返回键分层的最高一层，删掉要同时改 BackHandler 的四条判定，收益为零。
     */
    private val _probe = MutableStateFlow(false)
    val probe: StateFlow<Boolean> = _probe.asStateFlow()

    /**
     * 播放列表抽屉。和 expanded 分开存，是因为抽屉可以开在播放页之上，
     * 也可以（将来）从迷你条直接拉开，两种入口的关闭语义不同：
     * 关抽屉只收抽屉，不该顺手把播放页也收掉。
     */
    private val _queue = MutableStateFlow(false)
    val queue: StateFlow<Boolean> = _queue.asStateFlow()

    fun select(t: Tab) { _tab.value = t }
    fun openDetail(s: Song) { _detail.value = s }
    fun closeDetail() { _detail.value = null }
    fun expand(v: Boolean) { _expanded.value = v }
    fun openProbe(v: Boolean) { _probe.value = v }
    fun openQueue(v: Boolean = true) { _queue.value = v }
}

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("首页", Icons.Rounded.Home),
    SEARCH("搜索", Icons.Rounded.Search),
    LIBRARY("歌单", Icons.Rounded.LibraryMusic),
    DOWNLOAD("下载", Icons.Rounded.Download),
    ACCOUNT("账号", Icons.Rounded.AccountCircle),
}

/** 悬浮 dock 的几何参数集中在这里，改尺寸不用翻布局代码。 */
private val DockSideMargin = 16.dp
private val DockBottomGap = 12.dp
private val DockCorner = 28.dp

/** 单个页签格子的高度。胶囊与格子同高，选中态的「长胖」只体现在宽度上。 */
private val DockHeight = 52.dp

/**
 * 尺寸类动画用的弹性规格。主题的 PolyMotion 只暴露 FiniteAnimationSpec<Float>，
 * 而 animateDpAsState 要 FiniteAnimationSpec<Dp>，类型不同不能直接复用，
 * 这里按同样的阻尼与刚度各建一份 Dp 版，保证 dock 的动画节奏和界面其它部分一致。
 */
private val ExpressiveDpSpec: FiniteAnimationSpec<Dp> =
    spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessLow)
private val SoftDpSpec: FiniteAnimationSpec<Dp> =
    spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)

/**
 * 应用骨架。层级从下到上：页签内容（HorizontalPager）→ 迷你播放条 → 悬浮 dock →（覆盖）详情页 →（覆盖）全屏播放器。
 *
 * 底部导航不放 Scaffold(bottomBar)：那个槽位会占掉布局高度并把内容整体顶上去，
 * 而悬浮 dock 要的是浮在内容之上、页面能滚到它背后，所以放在外层 Box 的底部对齐位置。
 */
@Composable
fun PolyTuneScaffold(
    nav: NavState,
    homeVm: HomeViewModel,
    searchVm: SearchViewModel,
    downloadVm: DownloadViewModel,
    accountVm: AccountViewModel,
    playVm: PlayViewModel,
    libraryVm: LibraryViewModel,
    detailSong: Song?,
    detailVmFactory: (Song) -> ViewModelProvider.Factory,
) {
    val tab by nav.tab.collectAsStateCompat()
    val expanded by nav.expanded.collectAsStateCompat()
    val probeOpen by nav.probe.collectAsStateCompat()
    val queueOpen by nav.queue.collectAsStateCompat()
    val queueVm: QueueViewModel = viewModel(factory = QueueViewModel.factory(playVm, playVm.engine))
    val now by playVm.playback.collectAsStateCompat()
    val playUi by playVm.state.collectAsStateCompat()
    // 引擎状态只在真正 prepare 成功后才有歌；用户点了播放但直链还在解析时，
    // 歌名来自 playUi 自己的 _own，界面才不会一直挂在「未在播放」。
    val shownSong = now.song ?: playUi.song
    val shownPlatform = now.actualPlatform.ifBlank { playUi.actualPlatform }

    val detailVm: DetailViewModel? = detailSong?.let { song ->
        viewModel<DetailViewModel>(
            key = "detail-${song.platform.name}-${song.id}",
            factory = detailVmFactory(song),
        )
    }

    // 页签滑动交给 HorizontalPager 承载，而不是自己写 detectHorizontalDragGestures：
    // pager 内部走 nested scroll，手势从 LazyRow / HeroBanner 这类内层横向滚动组件起步时，
    // 子组件先把位移消费掉，pager 拿不到剩余量就不会抢；只有内层滚到头、或者手势本来就是纵向的，
    // pager 才接管。自己写手势检测拿不到「有没有被内层消费」这个信息，只能靠 ax > ay 猜，
    // 在推荐歌单滑到一半时会误切页。
    val pagerState = rememberPagerState(
        initialPage = tab.ordinal,
        pageCount = { Tab.entries.size },
    )

    // 同步方向一：手指滑动结束后 pager 停在新页，把导航状态跟上。
    // 用 settledPage 而不是 currentPage：currentPage 在滑过一半时就变了，
    // 动画还没落位就先切页签，底部指示器会超前于画面。
    LaunchedEffect(pagerState.settledPage) {
        val target = Tab.entries.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
        if (nav.tab.value != target) nav.select(target)
    }

    // 同步方向二：点 dock 或按返回键改的是 NavState，这里把 pager 滚过去。
    // 不在点击回调里直接 animateScrollToPage，因为那样会绕过状态源，
    // 返回键那条路径就不参与同步了。
    LaunchedEffect(tab) {
        if (pagerState.currentPage != tab.ordinal) {
            pagerState.animateScrollToPage(tab.ordinal)
        }
    }

    // 返回键优先级：全屏播放器 → 详情页 → 首页页签 → 交给系统退出。
    // 顺序与视觉层级一致，用户按返回永远是「收掉最上面那一层」。
    BackHandler(enabled = probeOpen) { nav.openProbe(false) }
    BackHandler(enabled = !probeOpen && expanded) { nav.expand(false) }
    BackHandler(enabled = !probeOpen && !expanded && detailSong != null) { nav.closeDetail() }
    BackHandler(enabled = !probeOpen && !expanded && detailSong == null && tab != Tab.HOME) {
        nav.select(Tab.HOME)
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold { inner ->
            Box(Modifier.padding(inner)) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                    // 预取相邻一页，横滑时下一屏已经组合好，不出现空白帧
                    beyondViewportPageCount = 1,
                    key = { Tab.entries[it].name },
                ) { page ->
                    when (Tab.entries[page]) {
                        Tab.HOME -> HomeScreen(
                            vm = homeVm,
                            onOpenSong = { nav.openDetail(it) },
                            onPlaySong = { s ->
                                // 先种队列再播：homeVm.state.value.hotSongs 就是首页当前那一列可见的歌，
                                // 用它当队列，「下一首」才会顺着用户眼前的列表往下走。
                                // 不种的话 queueList 里只有 play() 自己塞进去的这一首，
                                // nextTarget() 恒为 null，按钮画出来也是死的。
                                playVm.setQueue(homeVm.state.value.hotSongs, startAt = homeVm.state.value.hotSongs.indexOfFirst { it.id == s.id && it.platform == s.platform })
                                playVm.play(s)
                            },
                            onSearch = { nav.select(Tab.SEARCH) },
                            onLibrary = { libraryVm.setTab(0); nav.select(Tab.LIBRARY) },
                            onOffline = { libraryVm.setTab(1); nav.select(Tab.LIBRARY) },
                            onDownloads = { nav.select(Tab.DOWNLOAD) },
                        )

                        Tab.SEARCH -> SearchScreen(
                            vm = searchVm,
                            onOpenSong = { nav.openDetail(it) },
                            onPlaySong = { s ->
                                // 搜索结果的队列用每个分组的第一条（canonical）：
                                // 分组里其余是同一首歌在不同源的版本，铺进队列会变成连着放同一首。
                                val flat = searchVm.state.value.groups.map { it.canonical }
                                playVm.setQueue(flat, startAt = flat.indexOfFirst { it.id == s.id && it.platform == s.platform })
                                playVm.play(s)
                            },
                        )

                        Tab.LIBRARY -> LibraryScreen(
                            vm = libraryVm,
                            onOpenSong = { nav.openDetail(it) },
                            onPlaySong = { s ->
                                val list = libraryVm.state.value.playlist
                                playVm.setQueue(list, startAt = list.indexOfFirst { it.id == s.id && it.platform == s.platform })
                                val local = libraryVm.state.value.localSongs.find { it.song.id == s.id && it.song.platform == s.platform }
                                if (local != null) playVm.playLocal(s, local.filePath) else playVm.play(s)
                            },
                            onPlayOnline = { tracks, start ->
                                // 在线歌单整张进队列：点哪首从哪首开始，后面的照歌单顺序继续。
                                playVm.setQueue(
                                    tracks,
                                    startAt = tracks.indexOfFirst { it.id == start.id && it.platform == start.platform },
                                )
                                playVm.play(start)
                            },
                            onAccount = { nav.select(Tab.ACCOUNT) },
                        )
                        Tab.DOWNLOAD -> DownloadScreen(downloadVm)
                        Tab.ACCOUNT -> AccountScreen(accountVm)
                    }
                }
            }
        }

        // 底部浮层：迷你播放条与悬浮 dock 同处一条 Column，
        // 有歌时播放条把 dock 往上推，没歌时 dock 自己落回底部，两者不会互相遮挡。
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(start = DockSideMargin, end = DockSideMargin, bottom = DockBottomGap),
        ) {
            AnimatedVisibility(
                visible = shownSong != null && !expanded,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                MiniPlayer(
                    song = shownSong,
                    isPlaying = now.isPlaying,
                    buffering = now.buffering,
                    progress = if (now.durationMs > 0) {
                        now.positionMs.toFloat() / now.durationMs
                    } else 0f,
                    onClick = { nav.expand(true) },
                    onToggle = { playVm.toggle() },
                )
            }
            PttFloatingDock(
                selected = tab,
                onSelect = { nav.select(it) },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // 详情覆盖层：从右侧滑入，命中返回键时由外部手势区关闭
        AnimatedVisibility(
            visible = detailSong != null && !expanded,
            enter = slideInHorizontally(tween(280)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(240)) { it } + fadeOut(tween(180)),
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (detailVm != null) {
                    DetailScreen(
                        vm = detailVm,
                        onPlay = { s ->
                            // 详情页里点播放时，队列用「当前这首歌 + 它的其他源版本」之外的同列表：
                            // 优先把搜索结果的分组代表曲当队列，这样从详情页起播也能继续往下听。
                            val flat = searchVm.state.value.groups.map { it.canonical }
                            if (flat.isNotEmpty()) {
                                playVm.setQueue(flat, startAt = flat.indexOfFirst { it.id == s.id && it.platform == s.platform })
                            }
                            playVm.play(s)
                            nav.expand(true)
                        },
                        onDownload = { s -> downloadVm.download(s) },
                        onClose = { nav.closeDetail() },
                    )
                }
            }
        }

        // 全屏播放器：从底部上滑覆盖一切
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(280)) { it } + fadeOut(tween(160)),
        ) {
            NowPlayingScreen(
                song = shownSong,
                actualPlatform = shownPlatform,
                lyric = playUi.lyric,
                positionMs = now.positionMs,
                durationMs = now.durationMs,
                isPlaying = now.isPlaying,
                buffering = now.buffering,
                onSeek = { playVm.seekTo(it) },
                onToggle = { playVm.toggle() },
                onClose = { nav.expand(false) },
                onDownload = {
                    shownSong?.let { downloadVm.download(it) }
                },
                isPreview = playUi.preview,
                // 解析出来的直链要透给播放页，格式徽标靠它反推真实容器
                endpointUrl = now.endpoint?.url,
                quality = now.endpoint?.quality,
                // 队列由 PlayViewModel 持有，这里只做转发：
                // 按钮的可用状态必须跟队列实时同步，所以每个页面进出都要重读一次 hasPrevious/hasNext。
                hasPrevious = playVm.hasPrevious(),
                hasNext = playVm.hasNext(),
                onPrevious = { playVm.previous() },
                onNext = { playVm.next() },
                onOpenQueue = { nav.openQueue() },
                queueSize = playUi.queueSize,
            )
        }

        // 播放列表抽屉：从播放页顶栏的队列按钮进入，整屏覆盖在播放器之上，
        // 层级低于协议自检（那是最底层的调试入口），高于播放器本身。
        AnimatedVisibility(
            visible = queueOpen,
            enter = slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(180)),
            exit = slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(160)),
        ) {
            QueueSheet(
                vm = queueVm,
                onClose = { nav.openQueue(false) },
                onOpenSong = { s ->
                    // 从队列里点「看详情」：先收起抽屉再开详情，避免详情页压在抽屉上面还要再按两次返回。
                    nav.openQueue(false)
                    nav.openDetail(s)
                },
            )
        }

        // 协议自检页本身保留，账号页上的入口已删除；
        // 需要排查协议时仍可由 NavState.openProbe(true) 打开。
        if (probeOpen) {
            ProtocolProbe(onClose = { nav.openProbe(false) })
        }
    }
}

/**
 * 悬浮 dock。自绘而不用 NavigationBar：后者自带一条通栏背景和底部 inset，
 * 塞进圆角 Surface 会出现双层底色、底边还多一截空白；自绘才能让指示器做成真正的胶囊，
 * 弹性形变也有地方下手。
 */
@Composable
private fun PttFloatingDock(
    selected: Tab,
    onSelect: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        // 用容器色而非常规 surface：dock 浮在内容上方，要和页面底色拉开一档层级，
        // 同时保持 MD3 的容器语义。半透明留给后续毛玻璃改造，现在仍用实色避免文字发灰。
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(DockCorner),
        shadowElevation = 8.dp,
        tonalElevation = 0.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { t ->
                DockItem(
                    tab = t,
                    selected = t == selected,
                    onClick = { onSelect(t) },
                    modifier = Modifier.weight(1f),
                    // 被选中的那一个用更松的弹簧，胶囊扩张时有回弹；其它页签用软弹簧回收
                    indicatorSpec = if (t == selected) ExpressiveDpSpec else SoftDpSpec,
                )
            }
        }
    }
}

@Composable
private fun DockItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
    indicatorSpec: FiniteAnimationSpec<Dp>,
    modifier: Modifier = Modifier,
) {
    val brand = MaterialTheme.colorScheme.primary
    val idle = MaterialTheme.colorScheme.onSurfaceVariant

    // 底色谈入谈出用颜色动画，图标尺寸与内边距走弹性 spec，
    // 两条时间线节奏不同才像 M3E 的「指示器先动、内容跟上」。
    val pillColor by animateColorAsState(
        targetValue = if (selected) brand.copy(alpha = 0.16f) else brand.copy(alpha = 0f),
        animationSpec = tween(180),
        label = "dockPill",
    )
    val contentColor by animateColorAsState(
        targetValue = if (selected) brand else idle,
        animationSpec = tween(180),
        label = "dockContent",
    )
    val iconSize by animateDpAsState(
        targetValue = if (selected) 23.dp else 21.dp,
        animationSpec = indicatorSpec,
        label = "dockIcon",
    )
    // 关键：整格宽度固定（来自调用方的 weight(1f)），胶囊只画在格子内部、随内容宽度变化。
    // 上一版把 background 与随选中态变化的内边距直接加在整格上，选中项于是要向两侧「长胖」，
    // 而 Row 里四项是 weight 均分的，长胖的那一项会挤掉邻居的宽度 —— 真机上就是
    // 「首页」的胶囊被撑宽、后面三个图标整体右移。
    // 现在外层 Box 只负责占位与点击，内层 Row 才是胶囊本体。
    Box(
        modifier = modifier
            .height(DockHeight)
            .clip(RoundedCornerShape(20.dp))
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(DockHeight)
                .clip(RoundedCornerShape(20.dp))
                .background(pillColor)
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = tab.icon,
                contentDescription = tab.label,
                tint = contentColor,
                modifier = Modifier.size(iconSize),
            )
            // 文字只在选中项出现：四个页签挤在窄屏上时，常驻文字会把图标压得很小。
            AnimatedVisibility(
                visible = selected,
                enter = fadeIn(tween(200)) + slideInHorizontally(tween(200)) { it / 2 },
                exit = fadeOut(tween(90)),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp))
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = contentColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateCompat(): State<T> =
    this.collectAsState(this.value)
