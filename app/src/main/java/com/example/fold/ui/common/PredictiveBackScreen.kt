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
        val newScreenshot = try {
            if (view.width <= 0 || view.height <= 0) {
                null
            } else {
                val bmp = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                view.draw(canvas)
                bmp
            }
        } catch (e: Exception) {
            android.util.Log.e("PredictiveBack", "captureCurrentScreen failed: ${e.message}")
            null
        }
        if (newScreenshot != null) {
            previousScreenshot?.recycle()
            previousScreenshot = newScreenshot
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
    var isFinishing by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = true) { backEvent ->
        try {
            android.util.Log.d("PredictiveBack", "Gesture STARTED, screenshot=${PredictiveBackManager.previousScreenshot != null}")
            backEvent.collect { event ->
                progress = event.progress
                isGestureActive = true
                android.util.Log.d("PredictiveBack", "Gesture PROGRESS: progress=${event.progress}")
            }
            // 手势完成
            isFinishing = true
            progress = 1f
            android.util.Log.d("PredictiveBack", "Gesture COMPLETED, isFinishing=true, progress=1f")
            onBack()
            android.util.Log.d("PredictiveBack", "onBack() RETURNED")
        } catch (e: CancellationException) {
            android.util.Log.d("PredictiveBack", "Gesture CANCELLED, resetting state")
            isGestureActive = false
            isFinishing = false
            progress = 0f
        }
    }

    val scale = 1f - (progress * 0.08f)
    val translationX = progress * 100f
    val cornerRadius = 16f + (progress * 8f)

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
                    shape = RoundedCornerShape(cornerRadius.dp)
                    clip = true
                }
        ) {
            content()
        }
    }
}
