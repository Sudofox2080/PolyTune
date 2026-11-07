package com.polytune

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polytune.core.db.DownloadRepository
import com.polytune.source.api.Song
import com.polytune.ui.account.AccountViewModel
import com.polytune.ui.detail.DetailViewModel
import com.polytune.ui.download.DownloadViewModel
import com.polytune.ui.home.HomeViewModel
import com.polytune.ui.library.LibraryViewModel
import com.polytune.ui.nav.NavState
import com.polytune.ui.nav.PolyTuneScaffold
import com.polytune.ui.play.PlayViewModel
import com.polytune.ui.search.SearchViewModel
import com.polytune.ui.theme.PolyTuneTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PolyTuneTheme {
                PolyTuneRoot()
            }
        }
    }
}

/**
 * 顶层组合。这里只做三件事：建依赖、建 ViewModel、把状态喂给骨架。
 * 页面切换与转场全部在 [PolyTuneScaffold] 里，本文件保持无布局逻辑。
 */
@Composable
private fun PolyTuneRoot() {
    val ctx = LocalContext.current
    val graph = remember { (ctx.applicationContext as PolyTuneApp).graph }
    val nav = remember { NavState() }

    // 通知权限不给，「系统媒体胶囊 / 后台播放卡片」就都不会出现——媒体通知本身是
    // MediaSessionService 发的，属于通知的一种，Android 13 起必须显式授权。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { }
        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val baseFactory = remember {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
                modelClass.isAssignableFrom(HomeViewModel::class.java) ->
                    HomeViewModel(graph.aggregator) as T

                modelClass.isAssignableFrom(SearchViewModel::class.java) ->
                    SearchViewModel(graph.aggregator) as T

                modelClass.isAssignableFrom(DownloadViewModel::class.java) ->
                    DownloadViewModel(DownloadRepository(ctx.applicationContext, graph.db, graph.aggregator)) as T

                modelClass.isAssignableFrom(AccountViewModel::class.java) ->
                    AccountViewModel(graph.aggregator) as T

                modelClass.isAssignableFrom(PlayViewModel::class.java) ->
                    PlayViewModel(graph.aggregator, graph.engine, ctx.applicationContext) as T

                modelClass.isAssignableFrom(LibraryViewModel::class.java) ->
                    LibraryViewModel(
                        DownloadRepository(ctx.applicationContext, graph.db, graph.aggregator),
                        graph.aggregator,
                    ) as T

                else -> error("未注册的 ViewModel ${modelClass.name}")
            }
        }
    }

    val homeVm: HomeViewModel = viewModel(factory = baseFactory)
    val searchVm: SearchViewModel = viewModel(factory = baseFactory)
    val downloadVm: DownloadViewModel = viewModel(factory = baseFactory)
    val accountVm: AccountViewModel = viewModel(factory = baseFactory)
    val playVm: PlayViewModel = viewModel(factory = baseFactory)
    val libraryVm: LibraryViewModel = viewModel(factory = baseFactory)

    val detailSong by nav.detail.collectAsState()

    PolyTuneScaffold(
        nav = nav,
        homeVm = homeVm,
        searchVm = searchVm,
        downloadVm = downloadVm,
        accountVm = accountVm,
        playVm = playVm,
        libraryVm = libraryVm,
        detailSong = detailSong,
        detailVmFactory = { song ->
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DetailViewModel(graph.aggregator, song) as T
            }
        },
    )
}
