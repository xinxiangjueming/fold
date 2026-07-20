package com.example.fold.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.sin

/**
 * 静态曲线进度条
 */
@Composable
fun WaveProgress(
    modifier: Modifier = Modifier,
    progress: Float,
    waveColor: Color = Color(0xFF6200EE),
    trackColor: Color = Color.LightGray,
    strokeWidthDp: Float = 2f,
    amplitudeDp: Float = 3f,
    frequency: Float = 0.05f,
    showThumb: Boolean = false,
    thumbColor: Color = waveColor,
    thumbRadiusDp: Float = 5f,
) {
    val density = LocalDensity.current
    val strokeWidth = with(density) { strokeWidthDp.dp.toPx() }
    val amplitude = with(density) { amplitudeDp.dp.toPx() }

    val wavePath = remember { Path() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val centerY = height / 2

        wavePath.reset()
        wavePath.moveTo(0f, centerY)
        for (x in 0..width.toInt()) {
            val y = centerY + amplitude * sin(x * frequency)
            wavePath.lineTo(x.toFloat(), y)
        }

        // 未播放部分
        drawPath(
            path = wavePath,
            color = trackColor,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )

        // 已播放部分
        clipRect(right = width * progress.coerceIn(0f, 1f)) {
            drawPath(
                path = wavePath,
                color = waveColor,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }

        // 圆点指示器
        if (showThumb) {
            val thumbRadius = with(density) { thumbRadiusDp.dp.toPx() }
            drawCircle(
                color = thumbColor,
                radius = thumbRadius,
                center = Offset(width * progress.coerceIn(0f, 1f), centerY)
            )
        }
    }
}
