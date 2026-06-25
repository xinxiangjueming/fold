package com.example.fold.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.fold.MainActivity
import com.example.fold.ui.filebrowser.FileBrowserScreen
import com.example.fold.ui.calculator.CalculatorScreen
import com.example.fold.ui.hidden.HiddenAppsScreen
import com.example.fold.ui.player.AudioPlayerScreen
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
        androidx.compose.runtime.snapshotFlow { MainActivity.pendingOpenPlayer }
            .collect { pending ->
                if (pending) {
                    MainActivity.pendingOpenPlayer = false
                    val filePath = com.example.fold.ui.player.MusicPlayerHolder.lastFilePath
                    if (filePath.isNotEmpty()) {
                        FoldLogger.i("NavGraph", "auto-navigate to player: $filePath")
                        navController.navigate(Routes.audio(filePath))
                    }
                }
            }
    }
    // 每次重组都读取，确保切换后立即生效（不需要重启app）
    val calculatorMode = context.getSharedPreferences("file_sort", android.content.Context.MODE_PRIVATE)
        .getBoolean("calculator_mode", false)
    val startDest = if (calculatorMode) Routes.CALCULATOR else Routes.FILE_BROWSER

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
}
