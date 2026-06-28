package com.example.fold.ui.common

import android.graphics.Bitmap
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.drawToBitmap
import kotlinx.coroutines.CancellationException

/**
 * 全局截图管理器：进入新界面前截取当前页面，返回时显示
 */
object PredictiveBackManager {
    // 存储上一页的截图
    var previousScreenshot: Bitmap? = null
        private set

    // 调用时机：在 navigate() 之前调用，截取当前页面
    fun captureCurrentScreen(view: android.view.View) {
        previousScreenshot?.recycle()
        previousScreenshot = try {
            view.drawToBitmap()
        } catch (e: Exception) {
            null
        }
        android.util.Log.d("PredictiveBack", "captureCurrentScreen: screenshot=${previousScreenshot != null}, size=${previousScreenshot?.width}x${previousScreenshot?.height}")
    }

    fun clear() {
        previousScreenshot?.recycle()
        previousScreenshot = null
        android.util.Log.d("PredictiveBack", "clear: screenshot cleared")
    }
}

/**
 * 预测性返回动画容器：返回时显示上一页截图 + 当前页缩小
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var isGestureActive by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = true) { backEvent ->
        try {
            android.util.Log.d("PredictiveBack", "Gesture started, screenshot=${PredictiveBackManager.previousScreenshot != null}")
            backEvent.collect { event ->
                progress = event.progress
                isGestureActive = true
            }
            // 手势完成：直接导航，不回弹
            android.util.Log.d("PredictiveBack", "Gesture completed, calling onBack() immediately")
            onBack()
        } catch (e: CancellationException) {
            android.util.Log.d("PredictiveBack", "Gesture cancelled")
            isGestureActive = false
            progress = 0f
        }
    }

    val scale = 1f - (progress * 0.08f)
    val translationX = progress * 100f
    val cornerRadius = progress * 24f

    if (progress > 0.01f) {
        android.util.Log.d("PredictiveBack", "Rendering: progress=$progress scale=$scale")
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 背景：上一页的截图
        PredictiveBackManager.previousScreenshot?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 当前页面：缩小 + 右移 + 圆角
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.translationX = translationX
                    shadowElevation = progress * 20f
                }
                .clip(RoundedCornerShape(cornerRadius.dp))
        ) {
            content()
        }
    }
}
