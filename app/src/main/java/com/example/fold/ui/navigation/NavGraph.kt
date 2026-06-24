package com.example.fold.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.example.fold.ui.filebrowser.FileBrowserScreen
import com.example.fold.ui.reader.ReaderScreen
import com.example.fold.ui.viewer.ArchiveViewerScreen
import com.example.fold.ui.viewer.ImageViewerScreen

object Routes {
    const val FILE_BROWSER = "file_browser"
    const val READER = "reader/{filePath}"
    const val READER_BASE = "reader"
    const val IMAGE = "image/{filePath}"
    const val IMAGE_BASE = "image"
    const val ARCHIVE = "archive/{filePath}"
    const val ARCHIVE_BASE = "archive"

    fun reader(filePath: String): String {
        return "reader/${android.net.Uri.encode(filePath)}"
    }

    fun image(filePath: String): String {
        return "image/${android.net.Uri.encode(filePath)}"
    }

    fun archive(filePath: String): String {
        return "archive/${android.net.Uri.encode(filePath)}"
    }
}

private const val ANIM_DURATION = 250

@Composable
fun AppNavGraph(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = Routes.FILE_BROWSER,
        enterTransition = {
            slideInHorizontally(initialOffsetX = { it }, animationSpec = tween(ANIM_DURATION))
        },
        exitTransition = {
            ExitTransition.None
        },
        popEnterTransition = {
            EnterTransition.None
        },
        popExitTransition = {
            slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(ANIM_DURATION))
        }
    ) {
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
                }
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
    }
}
