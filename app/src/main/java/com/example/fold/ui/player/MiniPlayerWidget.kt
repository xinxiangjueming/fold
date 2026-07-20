package com.example.fold.ui.player

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.fold.R
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.shader.isRuntimeShaderSupported

/**
 * 悬浮小窗播放器 — 显示在文件浏览器右下角
 * 圆形封面 + 暂停/播放 + 关闭按钮
 */
@Composable
fun MiniPlayerFloatingWidget(
    backdrop: Backdrop,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onClose: () -> Unit,
) {
    val miniState by MiniPlayerState.state.collectAsState()
    val blurEnabled = isRuntimeShaderSupported() && Build.VERSION.SDK_INT >= 33

    AnimatedVisibility(
        visible = miniState.filePath.isNotEmpty(),
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomEnd
        ) {
            val blurColors = BlurColors(
                blendColors = listOf(
                    BlendColorEntry(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                        mode = BlurBlendMode.SrcOver
                    )
                ),
                brightness = 0f,
                contrast = 1f,
                saturation = 1f
            )

            Row(
                modifier = Modifier
                    .padding(end = 16.dp, bottom = 80.dp)
                    .then(
                        if (blurEnabled) {
                            Modifier
                                .clip(RoundedCornerShape(28.dp))
                                .textureBlur(
                                    backdrop = backdrop,
                                    shape = RoundedCornerShape(28.dp),
                                    blurRadius = 10f,
                                    colors = blurColors,
                                    highlight = Highlight.GlassStrokeMiddleLight
                                )
                        } else {
                            Modifier.squircleSurface(
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
                                cornerRadius = 28.dp,
                            )
                        }
                    )
                    .clickable { onOpenPlayer() }
                    .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 左侧圆形封面（播放时缓慢旋转，暂停时停在当前位置）
                val art = miniState.albumArt
                val rotationAnim = remember { androidx.compose.animation.core.Animatable(0f) }
                val scope = rememberCoroutineScope()

                LaunchedEffect(miniState.isPlaying) {
                    if (miniState.isPlaying) {
                        while (true) {
                            rotationAnim.animateTo(
                                targetValue = rotationAnim.value + 360f,
                                animationSpec = tween(durationMillis = 8000, easing = LinearEasing)
                            )
                        }
                    } else {
                        rotationAnim.stop()
                    }
                }

                val currentRotation = rotationAnim.value

                if (art != null) {
                    Image(
                        bitmap = art.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .graphicsLayer {
                                rotationZ = currentRotation
                            },
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // 暂停/播放按钮
                IconButton(
                    onClick = { onTogglePlay() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        if (miniState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (miniState.isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // 关闭按钮
                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.player_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
