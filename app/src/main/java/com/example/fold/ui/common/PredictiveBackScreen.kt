package com.example.fold.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * 预测性返回动画容器：手势过程中缩小当前页面，完成后触发返回
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    var progress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = enabled) { backEvent ->
        try {
            backEvent.collect { event ->
                progress = event.progress
            }
            progress = 1f
            onBack()
        } catch (e: CancellationException) {
            progress = 0f
        }
    }

    val scale = 1f - (progress * 0.08f)
    val translationX = progress * 100f
    val cornerRadius = 16f + (progress * 8f)

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.translationX = translationX
                    shadowElevation = progress * 20f
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(cornerRadius.dp)
                    clip = true
                }
        ) {
            content()
        }
    }
}
