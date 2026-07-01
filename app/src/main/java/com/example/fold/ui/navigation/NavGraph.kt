package com.example.fold.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fold.MainActivity
import com.example.fold.ui.eq.EqScreen
import com.example.fold.ui.filebrowser.FileBrowserScreen
import com.example.fold.ui.calculator.CalculatorScreen
import com.example.fold.ui.hidden.HiddenAppsScreen
import com.example.fold.ui.player.AudioPlayerScreen
import com.example.fold.ui.player.MiniPlayerFloatingWidget
import com.example.fold.ui.player.MiniPlayerState
import com.example.fold.ui.player.MusicPlayerHolder
import com.example.fold.ui.player.VideoPlayerScreen
import com.example.fold.ui.reader.ReaderScreen
import com.example.fold.ui.viewer.ArchiveViewerScreen
import com.example.fold.ui.viewer.ImageViewerScreen
import com.example.fold.util.FoldLogger
import com.example.fold.ui.common.PredictiveBackScreen
import com.example.fold.ui.common.PredictiveBackManager
import androidx.compose.ui.platform.LocalView

object Routes {
    const val CALCULATOR = "calculator"
    const val FILE_BROWSER = "file_browser"
    const val HIDDEN_APPS = "hidden_apps"
    const val READER = "reader/{filePath}"
    const val READER_BASE = "reader"
    const val IMAGE = "image/{filePath}"
    const val IMAGE_BASE = "image"
    const val ARCHIVE = "archive/{filePath}"
    const val ARCHIVE_BASE = "archive"
    const val VIDEO = "video/{filePath}"
    const val VIDEO_BASE = "video"
    const val AUDIO = "audio/{filePath}"
    const val AUDIO_BASE = "audio"
    const val EQ = "eq"
    const val TRASH = "trash"
    const val TEXT_EDITOR = "text_editor/{filePath}"
    const val TEXT_EDITOR_BASE = "text_editor"

    fun textEditor(filePath: String): String {
        return "text_editor/${android.net.Uri.encode(filePath)}"
    }

    fun reader(filePath: String): String {
        return "reader/${android.net.Uri.encode(filePath)}"
    }

    fun image(filePath: String): String {
        return "image/${android.net.Uri.encode(filePath)}"
    }

    fun archive(filePath: String): String {
        return "archive/${android.net.Uri.encode(filePath)}"
    }

    fun video(filePath: String): String {
        return "video/${android.net.Uri.encode(filePath)}"
    }

    fun audio(filePath: String): String {
        return "audio/${android.net.Uri.encode(filePath)}"
    }
}

private const val ANIM_DURATION = 250

@Composable
fun AppNavGraph(navController: NavHostController) {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 通知栏点击 → 自动打开播放页
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingOpenPlayer.value }
            .collect { pending ->
                if (pending) {
                    MainActivity.pendingOpenPlayer.value = false
                    val filePath = MusicPlayerHolder.lastFilePath
                    if (filePath.isNotEmpty()) {
                        FoldLogger.i("NavGraph", "auto-navigate to player: $filePath")
                        navController.navigate(Routes.audio(filePath))
                    }
                }
            }
    }
    // TTS 通知栏点击 → 自动打开阅读器
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingOpenReader.value }
            .collect { filePath ->
                if (filePath != null) {
                    MainActivity.pendingOpenReader.value = null
                    FoldLogger.i("NavGraph", "auto-navigate to reader: $filePath")
                    navController.navigate(Routes.reader(filePath)) {
                        launchSingleTop = true
                    }
                }
            }
    }
    // 息屏归隐 → 导航到计算器
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingNavigateCalculator.value }
            .collect { pending ->
                if (pending) {
                    MainActivity.pendingNavigateCalculator.value = false
                    FoldLogger.i("NavGraph", "stealth navigate to calculator")
                    navController.navigate(Routes.CALCULATOR) {
                        popUpTo(Routes.FILE_BROWSER)
                    }
                }
            }
    }
    // 系统打开文件 → 导航到对应查看器
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingOpenFile.value }
            .collect { filePath ->
                if (filePath != null) {
                    MainActivity.pendingOpenFile.value = null
                    val ext = filePath.substringAfterLast('.').lowercase()
                    FoldLogger.i("NavGraph", "open file from intent: $filePath (ext=$ext)")
                    when (ext) {
                        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg" ->
                            navController.navigate(Routes.image(filePath))
                        "mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "3gp", "ts" ->
                            navController.navigate(Routes.video(filePath))
                        "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus", "ape" -> {
                            navController.popBackStack(Routes.AUDIO_BASE, true)
                            navController.navigate(Routes.audio(filePath))
                        }
                        else -> navController.navigate(Routes.reader(filePath))
                    }
                }
            }
    }
    // 每次重组都读取，确保切换后立即生效（不需要重启app）
    val calculatorMode = context.getSharedPreferences("file_sort", android.content.Context.MODE_PRIVATE)
        .getBoolean("calculator_mode", false)

    // 息屏归隐：强制首帧为计算器，不恢复任何旧导航状态
    // forceStealthStart 只在 onCreate 设一次，remember 保证只消费一次
    var stealthConsumed by remember { mutableStateOf(false) }
    if (MainActivity.forceStealthStart && !stealthConsumed) {
        stealthConsumed = true
        MainActivity.forceStealthStart = false
        FoldLogger.i("NavGraph", "stealth start: forcing calculator as start destination")
    }
    val startDest = if (stealthConsumed || calculatorMode) Routes.CALCULATOR else Routes.FILE_BROWSER

    // 追踪当前路由，决定是否显示悬浮小窗
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val miniState by MiniPlayerState.state.collectAsState()
    val showMiniPlayer = currentRoute != Routes.AUDIO && currentRoute != Routes.EQ && miniState.filePath.isNotEmpty() && currentRoute != null

    // 同步当前路由到 Activity（用于 savedInstanceState 恢复）+ SharedPreferences 持久化
    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        if (currentRoute != null) {
            MainActivity.currentNavRoute = currentRoute
            context.getSharedPreferences("nav_state", android.content.Context.MODE_PRIVATE)
                .edit().putString("current_route", currentRoute).apply()
        }
    }

    // Activity 重建时恢复路由
    androidx.compose.runtime.LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingRestoreRoute.value }
            .collect { route ->
                if (route != null) {
                    MainActivity.pendingRestoreRoute.value = null
                    FoldLogger.i("NavGraph", "restoring route: $route")
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                }
            }
    }

    // 当 ViewModel 销毁但 MusicPlayerHolder 仍有活跃播放器时，同步 MiniPlayerState
    androidx.compose.runtime.LaunchedEffect(currentRoute) {
        if (currentRoute != Routes.AUDIO && MusicPlayerHolder.isActive()) {
            val player = MusicPlayerHolder.exoPlayer!!
            val currentPath = MusicPlayerHolder.lastFilePath
            if (currentPath.isNotEmpty() && MiniPlayerState.state.value.filePath != currentPath) {
                MiniPlayerState.update(
                    title = currentPath.substringAfterLast('/').substringBeforeLast('.'),
                    isPlaying = player.isPlaying,
                    albumArt = null,
                    filePath = currentPath,
                )
            }
            // 持续轮询 isPlaying 状态（ViewModel 销毁后 listener 已移除）
            while (MusicPlayerHolder.isActive()) {
                kotlinx.coroutines.delay(500)
                val p = MusicPlayerHolder.exoPlayer ?: break
                MiniPlayerState.updatePlaying(p.isPlaying)
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        NavHost(
        navController = navController,
        startDestination = startDest,
        enterTransition = { fadeIn(tween(ANIM_DURATION)) },
        exitTransition = { fadeOut(tween(ANIM_DURATION)) },
        popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
        popExitTransition = { fadeOut(tween(ANIM_DURATION)) }
    ) {
        composable(Routes.CALCULATOR) {
            CalculatorScreen(
                onUnlock = {
                    navController.navigate(Routes.FILE_BROWSER) {
                        popUpTo(Routes.CALCULATOR) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.FILE_BROWSER) {
            val view = LocalView.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                FoldLogger.i("NavGraph", "FILE_BROWSER destination composed, backStack=${navController.currentBackStackEntry?.destination?.route}")
            }
            FileBrowserScreen(
                onFileClick = { filePath ->
                    FoldLogger.i("NavGraph", "onFileClick: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.reader(filePath))
                },
                onImageClick = { filePath ->
                    FoldLogger.i("NavGraph", "onImageClick: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.image(filePath))
                },
                onArchiveClick = { filePath ->
                    FoldLogger.i("NavGraph", "onArchiveClick: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.archive(filePath))
                },
                onVideoClick = { filePath ->
                    FoldLogger.i("NavGraph", "onVideoClick: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.video(filePath))
                },
                onAudioClick = { filePath ->
                    FoldLogger.i("NavGraph", "onAudioClick: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.popBackStack(Routes.AUDIO_BASE, true)
                    navController.navigate(Routes.audio(filePath))
                },
                onTextEditorClick = { filePath ->
                    FoldLogger.i("NavGraph", "onTextEditorClick: $filePath")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.textEditor(filePath))
                },
                onNavigateToHiddenApps = {
                    FoldLogger.i("NavGraph", "onNavigateToHiddenApps: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.HIDDEN_APPS)
                },
                onNavigateToTrash = {
                    FoldLogger.i("NavGraph", "onNavigateToTrash: capturing screenshot")
                    PredictiveBackManager.captureCurrentScreen(view)
                    navController.navigate(Routes.TRASH)
                }
            )
        }

        composable(Routes.HIDDEN_APPS) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                HiddenAppsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.EQ) {
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                EqScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(Routes.TRASH) {
            val viewModel: com.example.fold.ui.filebrowser.FileBrowserViewModel = viewModel()
            val trashEnabled by viewModel.trashEnabled.collectAsState()
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.example.fold.ui.trash.TrashScreen(
                    onBack = { navController.popBackStack() },
                    trashEnabled = trashEnabled,
                    onToggleTrash = { viewModel.toggleTrashEnabled() },
                    getTrashDir = { viewModel.getTrashDir() },
                    getOriginalPath = { path -> viewModel.getTrashOriginalPath(path) },
                    onRestore = { file, origPath -> viewModel.restoreFromTrash(file.name, origPath ?: "") },
                    onDeletePermanent = { file -> file.delete() },
                    onEmptyTrash = { viewModel.emptyTrash() }
                )
            }
        }

        composable(
            route = Routes.TEXT_EDITOR,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                com.example.fold.ui.reader.TextEditorScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            popExitTransition = { fadeOut(tween(300)) }
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                ReaderScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.IMAGE,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                ImageViewerScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.ARCHIVE,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                ArchiveViewerScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.VIDEO,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            enterTransition = { slideInVertically(animationSpec = tween(ANIM_DURATION)) { it / 4 } + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { slideOutVertically(animationSpec = tween(ANIM_DURATION)) { it / 4 } + fadeOut(tween(ANIM_DURATION)) }
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                VideoPlayerScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() }
                )
            }
        }

        composable(
            route = Routes.AUDIO,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            enterTransition = { slideInVertically(animationSpec = tween(ANIM_DURATION)) { it / 4 } + fadeIn(tween(ANIM_DURATION)) },
            exitTransition = { fadeOut(tween(ANIM_DURATION)) },
            popEnterTransition = { fadeIn(tween(ANIM_DURATION)) },
            popExitTransition = { slideOutVertically(animationSpec = tween(ANIM_DURATION)) { it / 4 } + fadeOut(tween(ANIM_DURATION)) }
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            PredictiveBackScreen(onBack = { navController.popBackStack() }) {
                AudioPlayerScreen(
                    filePath = filePath,
                    onBack = { navController.popBackStack() },
                    onNavigateToEq = { navController.navigate(Routes.EQ) }
                )
            }
        }
    }

    // 悬浮小窗播放器 — 仅在非播放页且有活跃播放器时显示
    if (showMiniPlayer) {
        MiniPlayerFloatingWidget(
            onOpenPlayer = {
                val fp = MiniPlayerState.state.value.filePath
                if (fp.isNotEmpty()) {
                    navController.popBackStack(Routes.AUDIO_BASE, true)
                    navController.navigate(Routes.audio(fp))
                }
            },
            onTogglePlay = {
                val player = MusicPlayerHolder.exoPlayer
                if (player != null) {
                    if (player.isPlaying) player.pause() else player.play()
                }
            },
            onClose = {
                MusicPlayerHolder.release(context)
            }
        )
    }
}
}
