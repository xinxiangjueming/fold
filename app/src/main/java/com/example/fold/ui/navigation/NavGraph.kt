package com.example.fold.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.example.fold.MainActivity
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
    val startDest = if (calculatorMode) Routes.CALCULATOR else Routes.FILE_BROWSER

    // 追踪当前路由，决定是否显示悬浮小窗
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val miniState by MiniPlayerState.state.collectAsState()
    val showMiniPlayer = currentRoute != Routes.AUDIO && miniState.filePath.isNotEmpty() && currentRoute != null

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
        enterTransition = {
            fadeIn(animationSpec = tween(ANIM_DURATION))
        },
        exitTransition = {
            fadeOut(animationSpec = tween(ANIM_DURATION))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(ANIM_DURATION))
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(ANIM_DURATION))
        }
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
            FileBrowserScreen(
                onFileClick = { filePath ->
                    navController.navigate(Routes.reader(filePath))
                },
                onImageClick = { filePath ->
                    navController.navigate(Routes.image(filePath))
                },
                onArchiveClick = { filePath ->
                    navController.navigate(Routes.archive(filePath))
                },
                onVideoClick = { filePath ->
                    navController.navigate(Routes.video(filePath))
                },
                onAudioClick = { filePath ->
                    navController.popBackStack(Routes.AUDIO_BASE, true)
                    navController.navigate(Routes.audio(filePath))
                },
                onNavigateToHiddenApps = {
                    navController.navigate(Routes.HIDDEN_APPS)
                }
            )
        }

        composable(Routes.HIDDEN_APPS) {
            HiddenAppsScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.READER,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            ReaderScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.IMAGE,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            ImageViewerScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.ARCHIVE,
            arguments = listOf(navArgument("filePath") { type = NavType.StringType })
        ) { backStackEntry ->
            val encodedPath = backStackEntry.arguments?.getString("filePath") ?: ""
            val filePath = android.net.Uri.decode(encodedPath)
            ArchiveViewerScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
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
            VideoPlayerScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
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
            AudioPlayerScreen(
                filePath = filePath,
                onBack = { navController.popBackStack() }
            )
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

