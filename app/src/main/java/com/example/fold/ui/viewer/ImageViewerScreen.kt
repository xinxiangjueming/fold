package com.example.fold.ui.viewer

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.fold.R
import java.io.File

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    val dir = remember(file) { file.parentFile }
    val images = remember(dir) {
        dir?.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            ?.sortedBy { it.name }
            ?: listOf(file)
    }
    val startIndex = remember(file, images) {
        images.indexOfFirst { it.absolutePath == file.absolutePath }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = startIndex) { images.size }

    // 当前图片名
    val currentFile = images.getOrNull(pagerState.currentPage) ?: file

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            ZoomableImage(file = images[page])
        }

        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    text = currentFile.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back), tint = Color.White)
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.5f)
            )
        )

        // 底部信息
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 32.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${pagerState.currentPage + 1} / ${images.size}",
                color = Color.White.copy(alpha = 0.8f)
            )
            Text(
                text = "  ·  ${currentFile.length() / 1024} KB",
                color = Color.White.copy(alpha = 0.5f)
            )
        }
    }
}

@Composable
private fun ZoomableImage(file: java.io.File) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    AsyncImage(
        model = file,
        contentDescription = file.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY
            )
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }

                        if (pressed.size >= 2) {
                            // 双指：缩放 + 平移
                            val p0 = pressed[0].position
                            val p1 = pressed[1].position
                            val span = kotlin.math.hypot(
                                (p0.x - p1.x).toDouble(),
                                (p0.y - p1.y).toDouble()
                            ).toFloat()
                            val centroid = androidx.compose.ui.geometry.Offset(
                                (p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f
                            )

                            if (pressed[0].previousPressed && pressed[1].previousPressed) {
                                val prevP0 = pressed[0].previousPosition
                                val prevP1 = pressed[1].previousPosition
                                val prevSpan = kotlin.math.hypot(
                                    (prevP0.x - prevP1.x).toDouble(),
                                    (prevP0.y - prevP1.y).toDouble()
                                ).toFloat()
                                val prevCentroid = androidx.compose.ui.geometry.Offset(
                                    (prevP0.x + prevP1.x) / 2f, (prevP0.y + prevP1.y) / 2f
                                )

                                if (prevSpan > 0f) {
                                    scale = (scale * span / prevSpan).coerceIn(0.5f, 5f)
                                    if (scale > 1f) {
                                        offsetX += centroid.x - prevCentroid.x
                                        offsetY += centroid.y - prevCentroid.y
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            Log.d("ImageViewer", "pinch: scale=$scale")
                        } else if (pressed.size == 1 && scale > 1f) {
                            // 单指 + 已缩放：平移
                            val change = pressed[0]
                            if (change.previousPressed) {
                                val pan = change.position - change.previousPosition
                                offsetX += pan.x
                                offsetY += pan.y
                                change.consume()
                            }
                        }
                        // 单指 + 未缩放：不 consume，交给 HorizontalPager

                        // 手指全部抬起时重置
                        if (event.changes.all { !it.pressed }) {
                            if (scale <= 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            }
                            Log.d("ImageViewer", "gestureEnd: scale=$scale, offset=($offsetX,$offsetY)")
                        }
                    }
                }
            }
    )
}
