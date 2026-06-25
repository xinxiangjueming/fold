package com.example.fold.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.fold.R

/**
 * 视频播放器 — ExoPlayer (media3)
 * 全屏按钮嵌入 PlayerView 控制器内部，紧挨设置图标左边
 */
@Composable
fun VideoPlayerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? android.app.Activity

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse("file://$filePath")))
            prepare()
            playWhenReady = true
        }
    }

    val mediaSession = remember {
        androidx.media3.session.MediaSession.Builder(context, exoPlayer).build()
    }

    var isFullscreen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            mediaSession.release()
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(if (isFullscreen) Color.Black else MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    controllerShowTimeoutMs = 3000

                    post {
                        injectFullscreenButton(this,
                            onFullscreenToggle = { fs ->
                                isFullscreen = fs
                                activity?.requestedOrientation =
                                    if (fs) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                    else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                            },
                            onBack = {
                                if (isFullscreen) {
                                    isFullscreen = false
                                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                } else {
                                    onBack()
                                }
                            }
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 在 ExoPlayer 控制器中注入全屏按钮 + 返回按钮
 * 遍历 view 树找到 exo_settings，把按钮插入其左边
 */
private fun injectFullscreenButton(
    playerView: PlayerView,
    onFullscreenToggle: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    // 在 exoplayer-ui AAR 中，设置按钮的 ID 是 exo_settings
    // 资源 ID 格式: com.google.android.exoplayer2.ui:id/exo_settings
    val settingsView = findExoViewById(playerView, "exo_settings")

    if (settingsView?.parent is ViewGroup) {
        val parent = settingsView.parent as ViewGroup
        val settingsIndex = parent.indexOfChild(settingsView)

        // 全屏按钮
        val fsBtn = createControlButton(playerView, R.drawable.ic_fullscreen, "全屏").apply {
            tag = false // isFullscreen state
            setOnClickListener {
                val fs = !(tag as? Boolean ?: false)
                tag = fs
                setImageResource(
                    if (fs) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen
                )
                onFullscreenToggle(fs)
            }
        }
        parent.addView(fsBtn, settingsIndex) // 插入到设置按钮左边

        // 返回按钮 — 找到控制器最左侧的容器
        val backBtn = createControlButton(playerView, android.R.drawable.ic_menu_revert, "返回").apply {
            setOnClickListener { onBack() }
        }
        // 插入到控制器顶部容器的最前面
        val topContainer = findTopContainer(playerView)
        topContainer?.addView(backBtn, 0)
    }
}

/** 创建与 ExoPlayer 控制器风格一致的按钮 */
private fun createControlButton(playerView: PlayerView, iconRes: Int, desc: String): ImageButton {
    val size = (48 * playerView.resources.displayMetrics.density).toInt()
    return ImageButton(playerView.context).apply {
        setImageResource(iconRes)
        contentDescription = desc
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
        layoutParams = ViewGroup.LayoutParams(size, size)
        val padding = (4 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
    }
}

/** 在 PlayerView 中递归查找 exo_* ID 的 View */
private fun findExoViewById(root: View, name: String): View? {
    // 尝试 exoplayer-ui 包名
    val uiPackage = "com.google.android.exoplayer2.ui"
    val resId = root.resources.getIdentifier(name, "id", uiPackage)
    if (resId != 0) {
        root.findViewById<View>(resId)?.let { return it }
    }
    // 尝试 app 包名
    val appId = root.resources.getIdentifier(name, "id", root.context.packageName)
    if (appId != 0) {
        root.findViewById<View>(appId)?.let { return it }
    }
    // 降级：遍历 view 树找 ImageButton（设置按钮通常是最后一个 ImageButton）
    return findLastImageButton(root)
}

/** 递归查找最右侧/最后的 ImageButton（ExoPlayer 设置按钮特征） */
private fun findLastImageButton(view: View): ImageButton? {
    if (view is ImageButton) return view
    if (view is ViewGroup) {
        var last: ImageButton? = null
        for (i in 0 until view.childCount) {
            val found = findLastImageButton(view.getChildAt(i))
            if (found != null) last = found
        }
        return last
    }
    return null
}

/** 查找控制器顶部容器（包含设置按钮的那个 FrameLayout） */
private fun findTopContainer(playerView: PlayerView): ViewGroup? {
    // ExoPlayer 控制器的顶部栏通常是 exo_controller 下的第一个 FrameLayout
    val controllerId = playerView.resources.getIdentifier("exo_controller", "id", "com.google.android.exoplayer2.ui")
    val controller = if (controllerId != 0) playerView.findViewById<ViewGroup>(controllerId) else null

    if (controller != null) {
        // 遍历找到包含设置按钮的容器
        for (i in 0 until controller.childCount) {
            val child = controller.getChildAt(i)
            if (child is ViewGroup) {
                val hasSettings = findExoViewById(child, "exo_settings") != null
                if (hasSettings) return child
            }
        }
    }
    return controller
}
