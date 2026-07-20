package com.example.fold.ui.player

import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.fold.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 视频播放器 — ExoPlayer (media3)
 * 波浪进度条 + 圆点指示器 + 自定义控制器
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
    var showControls by remember { mutableStateOf(true) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }

    // 逐帧更新播放状态（匹配屏幕刷新率）
    LaunchedEffect(exoPlayer) {
        while (isActive) {
            withFrameNanos {
                duration = exoPlayer.duration.coerceAtLeast(0)
                position = exoPlayer.currentPosition.coerceAtLeast(0)
                isPlaying = exoPlayer.isPlaying
            }
        }
    }

    // 3秒无操作自动隐藏控制器
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000)
            showControls = false
        }
    }

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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                showControls = !showControls
            }
    ) {
        // 视频画面
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 自定义控制器叠加层
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                Modifier.fillMaxSize()
            ) {
                // 顶部：返回按钮
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 8.dp)
                ) {
                    IconButton(onClick = {
                        if (isFullscreen) {
                            isFullscreen = false
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.player_back),
                            tint = Color.White
                        )
                    }
                }

                Spacer(Modifier.weight(1f))

                // 底部：进度条 + 时间 + 播放控制
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // 波浪进度条 + 圆点
                    var isDragging by remember { mutableStateOf(false) }
                    var dragPos by remember { mutableLongStateOf(0L) }
                    val displayPos = if (isDragging) dragPos else position
                    val progress = if (duration > 0) displayPos.toFloat() / duration else 0f

                    val barScale by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = if (isDragging) 1.05f else 1f,
                        animationSpec = androidx.compose.animation.core.tween(150),
                        label = "barScale"
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .graphicsLayer {
                                scaleX = barScale
                                scaleY = barScale
                            }
                            .pointerInput(duration) {
                                detectDragGestures(
                                    onDragStart = { offset ->
                                        if (duration > 0) {
                                            isDragging = true
                                            dragPos = (offset.x / size.width * duration).toLong()
                                        }
                                    },
                                    onDragEnd = {
                                        if (isDragging) {
                                            exoPlayer.seekTo(dragPos)
                                            position = dragPos
                                            isDragging = false
                                        }
                                    },
                                    onDragCancel = { isDragging = false },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        if (duration > 0) {
                                            dragPos = (dragPos + dragAmount.x / size.width * duration).toLong()
                                                .coerceIn(0L, duration)
                                        }
                                    }
                                )
                            }
                    ) {
                        WaveProgress(
                            progress = progress,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(32.dp),
                            waveColor = MaterialTheme.colorScheme.primary,
                            trackColor = Color.White.copy(alpha = 0.3f),
                            showThumb = true,
                            thumbColor = Color.White,
                            thumbRadiusDp = 5f,
                        )
                    }

                    // 时间文字
                    Box(Modifier.fillMaxWidth()) {
                        // 底层：固定布局
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                formatTime(if (duration > 0) displayPos else 0),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = if (isDragging) 0.4f else 1f)
                            )
                            Text(
                                formatTime(duration),
                                fontSize = 12.sp,
                                color = Color.White.copy(alpha = if (isDragging) 0.4f else 1f)
                            )
                        }
                        // 上层：拖拽时间固定显示
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isDragging,
                            modifier = Modifier.align(Alignment.CenterStart)
                        ) {
                            Text(
                                "  ${formatTime(dragPos)}",
                                modifier = Modifier.padding(start = 25.dp),
                                fontSize = 12.sp,
                                color = Color.White,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // 播放控制
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (exoPlayer.hasPreviousMediaItem()) exoPlayer.seekToPrevious()
                            else exoPlayer.seekTo(0)
                        }) {
                            Icon(
                                Icons.Filled.SkipPrevious,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .background(Color.White.copy(alpha = 0.2f), CircleShape)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        IconButton(onClick = {
                            if (exoPlayer.hasNextMediaItem()) exoPlayer.seekToNext()
                            else exoPlayer.seekTo(0)
                        }) {
                            Icon(
                                Icons.Filled.SkipNext,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/** 在 PlayerView 中递归查找 exo_* ID 的 View */
private fun findExoViewById(root: View, name: String): View? {
    val uiPackage = "com.google.android.exoplayer2.ui"
    val resId = root.resources.getIdentifier(name, "id", uiPackage)
    if (resId != 0) {
        root.findViewById<View>(resId)?.let { return it }
    }
    val appId = root.resources.getIdentifier(name, "id", root.context.packageName)
    if (appId != 0) {
        root.findViewById<View>(appId)?.let { return it }
    }
    return findLastImageButton(root)
}

/** 递归查找最右侧/最后的 ImageButton */
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
