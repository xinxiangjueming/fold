package com.example.fold.ui.player

import android.content.Context
import java.io.File
import android.content.res.Configuration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fold.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton

/**
 * 音频播放器 — 纯 Compose UI，逻辑在 AudioPlayerViewModel
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AudioPlayerScreen(
    filePath: String,
    playlist: List<String> = emptyList(),
    onBack: () -> Unit,
    onNavigateToEq: () -> Unit = {}
) {
    val vm: AudioPlayerViewModel = viewModel()
    val state by vm.state.collectAsState()

    android.util.Log.d("AudioPlayer", "Screen compose: filePath=$filePath, playlist.size=${playlist.size}")

    // filePath 变化时重新初始化（切换歌曲）
    LaunchedEffect(filePath) {
        com.example.fold.util.FoldLogger.i("AudioPlayer", "=== Screen LaunchedEffect === filePath=$filePath, lastFilePath=${MusicPlayerHolder.lastFilePath}, mediaIdx=${MusicPlayerHolder.exoPlayer?.currentMediaItemIndex}, state.title=${state.title}, state.idx=${state.currentIndex}")
        vm.init(filePath, playlist)
    }

    // 沉浸模式日志
    LaunchedEffect(state.isImmersive) {
        android.util.Log.d("AudioPlayer", "isImmersive changed: ${state.isImmersive}")
    }

    // 歌词行自动滚动
    val lyricsListState = rememberLazyListState()
    LaunchedEffect(state.currentLyricIndex, state.title) {
        if (state.currentLyricIndex >= 0) {
            lyricsListState.animateScrollToItem(
                (state.currentLyricIndex - 7).coerceAtLeast(0)
            )
        }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    // 弹窗状态（提升到 Column 外部）
    var showSleepDialog by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    // var showUsbDialog by remember { mutableStateOf(false) }  // USB 独占模式暂时禁用

    // 均衡器按钮点击 → 导航到 EQ 界面
    val handleEqualizerClick: () -> Unit = { onNavigateToEq() }

    // USB 独占模式状态（暂时禁用）
    // val context = androidx.compose.ui.platform.LocalContext.current
    // val scope = rememberCoroutineScope()
    // val isExclusive by MusicPlayerHolder.isExclusiveMode
    // var exclusiveLabel by remember { mutableStateOf("") }
    val context = androidx.compose.ui.platform.LocalContext.current

    // 检测横竖屏
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    AnimatedContent(targetState = isLandscape, label = "orientation") { targetIsLandscape ->
    if (targetIsLandscape) {
        // ===== 横屏布局：左封面+控制，右歌词 =====
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左侧：封面 + 控制
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 封面
                val coverScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (state.isPlaying) 1.4f else 1.3f,
                    animationSpec = androidx.compose.animation.core.tween(300),
                    label = "coverScale"
                )
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .background(surface),
                    contentAlignment = Alignment.Center
                ) {
                    val art = state.albumArt
                    androidx.compose.animation.AnimatedContent(
                        targetState = art,
                        transitionSpec = {
                            fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                        },
                        label = "coverArt"
                    ) { currentArt ->
                        if (currentArt != null) {
                            val imageBitmap = remember(currentArt) { currentArt.asImageBitmap() }
                            Image(bitmap = imageBitmap, contentDescription = null,
                                modifier = Modifier.fillMaxSize())
                        } else {
                            Icon(Icons.Filled.MusicNote, contentDescription = null,
                                modifier = Modifier.size(60.dp),
                                tint = onSurfaceVar.copy(alpha = 0.5f))
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // 曲名
                Text(state.title, style = MaterialTheme.typography.titleSmall,
                    color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.playlistSize > 1) {
                    Text("${state.currentIndex + 1} / ${state.playlistSize}",
                        style = MaterialTheme.typography.bodySmall, color = onSurfaceVar)
                }

                Spacer(Modifier.height(8.dp))

                // 进度条
                if (state.initialized) {
                    IndependentProgressBar(
                        exoPlayer = vm.exoPlayer,
                        duration = state.duration,
                        onSeek = { vm.seekTo(it) }
                    )
                }

                Spacer(Modifier.height(8.dp))

                // 播放控制
                PlaybackControls(
                    isPlaying = state.isPlaying,
                    onPrev = { vm.prev() },
                    onToggle = { vm.togglePlay() },
                    onNext = { vm.next() },
                    onLongPressToggle = { vm.toggleImmersive() },
                    tint = onSurface
                )

                Spacer(Modifier.height(8.dp))

                // 功能按钮（沉浸模式隐藏）
                FeatureButtons(
                    loopMode = state.loopMode,
                    sleepActive = state.sleepRemaining != 0,
                    onLoopChange = { vm.cycleLoopMode() },
                    onSleepClick = { showSleepDialog = true },
                    onEqualizerClick = handleEqualizerClick,
                    onPlaylistClick = { showPlaylist = true },
                    primaryColor = MaterialTheme.colorScheme.primary,
                    variantColor = onSurfaceVar,
                    visible = !state.isImmersive
                )
            }

            // 右侧：歌词
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp)
            ) {
                // 歌词列表
                if (state.lyrics.isNotEmpty()) {
                    LazyColumn(
                        state = lyricsListState,
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        items(state.lyrics.size, key = { it }) { idx ->
                            Text(
                                text = state.lyrics[idx].second,
                                fontSize = if (idx == state.currentLyricIndex) 16.sp else 13.sp,
                                color = if (idx == state.currentLyricIndex) onSurface
                                else onSurfaceVar.copy(alpha = 0.5f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                        }
                    }
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reader_no_content),
                            style = MaterialTheme.typography.bodyMedium, color = onSurfaceVar)
                    }
                }
            }
        }
    } else {
    // ===== 竖屏布局 =====
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== TopBar（沉浸模式隐藏）=====
        val topBarAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (state.isImmersive) 0f else 1f,
            animationSpec = androidx.compose.animation.core.tween(300),
            label = "topBarAlpha"
        )
        TopAppBar(
            modifier = Modifier.graphicsLayer { alpha = topBarAlpha },
                title = { Text(stringResource(R.string.audio_player_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    // USB 独占模式按钮（暂时禁用）
                    // if (MusicPlayerHolder.isExclusiveSupported()) {
                    //     IconButton(onClick = {
                    //         android.util.Log.i("AudioPlayerScreen", "USB button clicked, showUsbDialog=$showUsbDialog")
                    //         showUsbDialog = true
                    //     }) {
                    //         Icon(
                    //             Icons.Default.Usb,
                    //             contentDescription = "USB 独占",
                    //             tint = if (isExclusive) MaterialTheme.colorScheme.primary else onSurfaceVar
                    //         )
                    //     }
                    // }
                    if (state.sleepRemaining > 0) {
                        val totalSec = state.sleepRemaining
                        val h = totalSec / 3600
                        val m = (totalSec % 3600) / 60
                        val s = totalSec % 60
                        val timeStr = if (h > 0) String.format("%d:%02d", h, m) else String.format("%02d:%02d", m, s)
                        Text(timeStr,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp))
                    } else if (state.sleepRemaining == -1) {
                        Text(stringResource(R.string.sleep_timer_waiting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp))
                        Text(stringResource(R.string.sleep_timer_waiting),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background)
            )

        Spacer(Modifier.weight(0.5f))

        // ===== 封面 / 歌词 =====
        AlbumOrLyrics(
            albumArt = state.albumArt,
            showLyrics = state.showLyrics,
            isPlaying = state.isPlaying,
            lyrics = state.lyrics,
            currentLyricIndex = state.currentLyricIndex,
            lyricsListState = lyricsListState,
            onToggleLyrics = { vm.toggleLyrics() },
            onSurface = onSurface,
            onSurfaceVar = onSurfaceVar,
            surface = surface,
            modifier = Modifier.weight(9f)
        )

        Spacer(Modifier.weight(0.5f))

        // ===== 曲目名 + 进度条（沉浸模式隐藏）=====
        val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (state.isImmersive) 0f else 1f,
            animationSpec = androidx.compose.animation.core.tween(300),
            label = "contentAlpha"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            Text(state.title, style = MaterialTheme.typography.titleMedium,
                color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.playlistSize > 1) {
                Text("${state.currentIndex + 1} / ${state.playlistSize}",
                    style = MaterialTheme.typography.bodySmall, color = onSurfaceVar)
            }
            Spacer(Modifier.height(12.dp))
            if (state.initialized) {
                IndependentProgressBar(
                    exoPlayer = vm.exoPlayer,
                    duration = state.duration,
                    onSeek = { vm.seekTo(it) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 播放控制 =====
        PlaybackControls(
            isPlaying = state.isPlaying,
            onPrev = { vm.prev() },
            onToggle = { vm.togglePlay() },
            onNext = { vm.next() },
            onLongPressToggle = { vm.toggleImmersive() },
            tint = onSurface
        )

        Spacer(Modifier.height(12.dp))

        // ===== 功能按钮行（沉浸模式隐藏）=====
        FeatureButtons(
            loopMode = state.loopMode,
            sleepActive = state.sleepRemaining != 0,
            onLoopChange = { vm.cycleLoopMode() },
            onSleepClick = { showSleepDialog = true },
            onEqualizerClick = handleEqualizerClick,
            onPlaylistClick = { showPlaylist = true },
            primaryColor = MaterialTheme.colorScheme.primary,
            variantColor = onSurfaceVar,
            visible = !state.isImmersive
        )

        Spacer(Modifier.weight(0.5f))
    }
    }
    } // end AnimatedContent

    // ===== 弹窗 =====
    if (showSleepDialog) {
        SleepTimerDialog(
            sleepRemaining = state.sleepRemaining,
            onSet = { min, finish -> vm.setSleep(min, finish) },
            onCancel = { vm.cancelSleep() },
            onDismiss = { showSleepDialog = false }
        )
    }
    if (showPlaylist) {
        // 提取到外部：这两个值不随进度变化，避免每 250ms 重组整个播放列表
        val playlistNames = remember(state.playlistPaths) {
            state.playlistPaths.map { it.substringAfterLast('/').substringBeforeLast('.') }
        }
        val curIdx = state.currentIndex
        PlaylistDialog(
            playlist = playlistNames,
            currentIndex = curIdx,
            onSelect = { vm.seekToIndex(it) },
            onDismiss = { showPlaylist = false }
        )
    }

    // ===== USB Exclusive Mode Dialog（暂时禁用）=====
    // if (showUsbDialog) {
    //     android.util.Log.i("AudioPlayerScreen", "Showing USB Exclusive Dialog")
    //     UsbExclusiveDialog(
    //         isExclusive = isExclusive,
    //         exclusiveLabel = exclusiveLabel,
    //         onEnable = { device, format, deviceInfo ->
    //             scope.launch {
    //                 android.util.Log.i("AudioPlayerScreen", "onEnable: device=${device.productName}, format=${format.label}, fd=${deviceInfo.fd}")
    //                 val stream = withContext(Dispatchers.IO) {
    //                     UsbAudioStream.create(deviceInfo, format.sampleRate, format.channels, format.bitDepth)
    //                 }
    //                 android.util.Log.i("AudioPlayerScreen", "Stream created: ${stream != null}, handle=${stream?.nativeHandle ?: 0}")
    //                 if (stream != null) {
    //                     // ── USB initialization sequence (matching decent-player) ──
    //                     withContext(Dispatchers.IO) {
    //                         // Step 1: setAlt(0) — deactivate endpoint, free old ISO rings
    //                         stream.setAltSetting(0)
    //                         android.util.Log.i("AudioPlayerScreen", "Step 1: setAlt(0)")
    //
    //                         // Step 2: SET_CUR — write sample rate to DAC clock
    //                         val rateOk = stream.setSampleRate(format.sampleRate, deviceInfo.clockSourceId)
    //                         android.util.Log.i("AudioPlayerScreen", "Step 2: setSampleRate=${format.sampleRate}, csId=${deviceInfo.clockSourceId}, ok=$rateOk")
    //
    //                         // Step 3: GET_CUR — verify clock is locked
    //                         val clockValid = stream.readClockValid(deviceInfo.clockSourceId)
    //                         android.util.Log.i("AudioPlayerScreen", "Step 3: CLOCK_VALID=$clockValid")
    //                         if (!clockValid) {
    //                             Thread.sleep(50) // Extra wait for PLL lock
    //                         }
    //
    //                         // Step 4: setAlt(0) again — defensive reset after clock change
    //                         stream.setAltSetting(0)
    //                         android.util.Log.i("AudioPlayerScreen", "Step 4: setAlt(0) defensive reset")
    //
    //                         // Step 5: setAlt(N) — activate endpoint with new format
    //                         val altOk = stream.setAltSetting(deviceInfo.bestAltSetting)
    //                         android.util.Log.i("AudioPlayerScreen", "Step 5: setAlt(${deviceInfo.bestAltSetting}): $altOk")
    //
    //                         // Step 6: wait ~50ms for DAC PLL lock
    //                         Thread.sleep(50)
    //
    //                         // Step 7: start — begin USB streaming
    //                         val started = stream.start()
    //                         android.util.Log.i("AudioPlayerScreen", "Step 7: stream.start()=$started")
    //                     }
    //
    //                     MusicPlayerHolder.setStream(stream)
    //                     MusicPlayerHolder.enableExclusiveMode(context, device, format, deviceInfo)
    //                     MusicPlayerHolder.releasePlayer()
    //                     exclusiveLabel = "${device.productName} ${format.label}"
    //                     vm.init(filePath, state.playlistPaths)
    //                 } else {
    //                     android.widget.Toast.makeText(context, context.getString(R.string.player_usb_stream_failed), android.widget.Toast.LENGTH_SHORT).show()
    //                 }
    //                 showUsbDialog = false
    //             }
    //         },
    //         onDisable = {
    //             MusicPlayerHolder.disableExclusiveMode(context)
    //             MusicPlayerHolder.releasePlayer()
    //             exclusiveLabel = ""
    //             vm.init(filePath, state.playlistPaths)
    //             showUsbDialog = false
    //         },
    //         onDismiss = { showUsbDialog = false }
    //     )
    // }
}

// ==================== 子组件 ====================

/** 封面 / 歌词切换区 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumOrLyrics(
    albumArt: android.graphics.Bitmap?,
    showLyrics: Boolean,
    isPlaying: Boolean,
    lyrics: List<Pair<Long, String>>,
    currentLyricIndex: Int,
    lyricsListState: androidx.compose.foundation.lazy.LazyListState,
    onToggleLyrics: () -> Unit,
    onSurface: Color,
    onSurfaceVar: Color,
    surface: Color,
    modifier: Modifier = Modifier
) {
    val coverScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isPlaying) 1.4f else 1.3f,
        animationSpec = androidx.compose.animation.core.tween(300),
        label = "coverScale"
    )

    Box(
        modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(visible = showLyrics && lyrics.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
            LazyColumn(
                state = lyricsListState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部留白，让当前歌词能居中偏下
                item { Spacer(Modifier.height(200.dp)) }
                items(lyrics.size, key = { it }) { idx ->
                    val distance = kotlin.math.abs(idx - currentLyricIndex)
                    val targetAlpha = when {
                        distance == 0 -> 1f
                        distance == 1 -> 0.65f
                        distance == 2 -> 0.4f
                        distance == 3 -> 0.25f
                        else -> 0.15f
                    }
                    val animatedAlpha by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = androidx.compose.animation.core.tween(400),
                        label = "lyricAlpha$idx"
                    )
                    val targetSize = when {
                        distance == 0 -> 20f
                        distance == 1 -> 17f
                        else -> 15f
                    }
                    val animatedSize by androidx.compose.animation.core.animateFloatAsState(
                        targetValue = targetSize,
                        animationSpec = androidx.compose.animation.core.tween(400),
                        label = "lyricSize$idx"
                    )
                    Text(
                        text = lyrics[idx].second,
                        fontSize = animatedSize.sp,
                        color = if (idx == currentLyricIndex) onSurface
                                else onSurfaceVar.copy(alpha = animatedAlpha),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        android.util.Log.d("AlbumOrLyrics", "lyric doubleTap idx=$idx")
                                        onToggleLyrics()
                                    }
                                )
                            }
                    )
                }
                // 底部留白
                item { Spacer(Modifier.height(200.dp)) }
            }
        }

        val coverAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (!showLyrics || lyrics.isEmpty()) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(300),
            label = "coverAlpha"
        )
        Box(
            modifier = Modifier
                .size(240.dp)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                    alpha = coverAlpha
                }
                .clip(RoundedCornerShape(10.dp))
                .background(surface)
                .combinedClickable(
                    onClick = {},
                    onDoubleClick = onToggleLyrics,
                    enabled = coverAlpha > 0.5f
                ),
            contentAlignment = Alignment.Center
        ) {
            val art = albumArt
            androidx.compose.animation.AnimatedContent(
                targetState = art,
                transitionSpec = {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(400))
                },
                label = "coverArt"
            ) { currentArt ->
                if (currentArt != null) {
                    val imageBitmap = remember(currentArt) { currentArt.asImageBitmap() }
                    Image(bitmap = imageBitmap, contentDescription = null,
                        modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, contentDescription = null,
                        modifier = Modifier.size(80.dp),
                        tint = onSurfaceVar.copy(alpha = 0.5f))
                }
            }
        }
    }
}

/**
 * 波浪进度条 — 自己管理 250ms 轮询，不依赖主 StateFlow
 * duration 从外部传入（由 Player.Listener 驱动），position 内部轮询
 */
@Composable
private fun IndependentProgressBar(
    exoPlayer: androidx.media3.exoplayer.ExoPlayer,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    // 始终渲染占位，避免 duration 从 0→实际值时布局抖动
    val effectiveDuration = if (duration > 0) duration else 30000L

    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0L) }
    var displayPos by remember { mutableStateOf(exoPlayer.currentPosition) }

    // 仅这一个组件内部 250ms 轮询，不影响外部任何 composable
    LaunchedEffect(exoPlayer) {
        displayPos = 0L  // player 切换时重置位置
        while (isActive) {
            delay(250)
            if (!isDragging) {
                displayPos = exoPlayer.currentPosition.coerceAtLeast(0L)
            }
        }
    }

    val pos = if (isDragging) dragPosition else displayPos
    val progress = if (duration > 0) pos.toFloat() / duration else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (duration > 0) 1f else 0.3f)
    ) {
        // 波浪进度条（点击跳转区域）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .pointerInput(duration) {
                    detectTapGestures { offset ->
                        if (duration > 0) {
                            val seekPos = (offset.x / size.width * duration).toLong()
                            displayPos = seekPos
                            onSeek(seekPos)
                        }
                    }
                }
        ) {
            WaveProgress(
                progress = progress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp),
                waveColor = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        // 时间文字在进度条下方
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                formatTime(if (duration > 0) pos else 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 播放控制按钮 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    onLongPressToggle: () -> Unit,
    tint: Color
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = null,
                modifier = Modifier.size(36.dp), tint = tint)
        }
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .combinedClickable(
                    onClick = onToggle,
                    onLongClick = {
                        android.util.Log.d("AudioPlayer", "Play button long click → toggleImmersive")
                        onLongPressToggle()
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null, modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.SkipNext, contentDescription = null,
                modifier = Modifier.size(36.dp), tint = tint)
        }
    }
}

/** 功能按钮行 */
@Composable
private fun FeatureButtons(
    loopMode: Int,
    sleepActive: Boolean,
    onLoopChange: () -> Unit,
    onSleepClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    primaryColor: Color,
    variantColor: Color,
    visible: Boolean = true
) {
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = if (visible) 1f else 0f },
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        IconButton(onClick = onLoopChange) {
            Icon(
                when (loopMode) {
                    1 -> Icons.Filled.RepeatOne
                    2 -> Icons.Filled.Shuffle
                    else -> Icons.Filled.Repeat
                }, contentDescription = null,
                tint = if (loopMode > 0) primaryColor else variantColor
            )
        }
        IconButton(onClick = onSleepClick) {
            Icon(Icons.Filled.Timer, contentDescription = null,
                tint = if (sleepActive) primaryColor else variantColor)
        }
        IconButton(onClick = onEqualizerClick) {
            Icon(Icons.Filled.Equalizer, contentDescription = null, tint = variantColor)
        }
        IconButton(onClick = onPlaylistClick) {
            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = variantColor)
        }
    }
}

/** 定时播放弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SleepTimerDialog(
    sleepRemaining: Int,
    onSet: (Int, Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var finishSong by remember { mutableStateOf(false) }
    var showCustomDuration by remember { mutableStateOf(false) }

    if (showCustomDuration) {
        CustomDurationDialog(
            onDismiss = { showCustomDuration = false },
            onConfirm = { minutes ->
                onSet(minutes, finishSong)
                showCustomDuration = false
            }
        )
    }

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            // 播完当前歌曲再暂停
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.sleep_timer_finish_song),
                    style = MaterialTheme.typography.bodyMedium)
                Switch(checked = finishSong, onCheckedChange = { finishSong = it })
            }
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // 时长按钮网格：一行两个
            val durations = listOf(15, 30, 45, 60)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in durations.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        for (min in row) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { onSet(min, finishSong) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${min}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // 自定义时长
            TextButton(onClick = { showCustomDuration = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sleep_timer_custom))
            }

            // 已设定定时，显示倒计时
            if (sleepRemaining > 0) {
                val totalSec = sleepRemaining
                val h = totalSec / 3600
                val m = (totalSec % 3600) / 60
                val s = totalSec % 60
                val timeStr = if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("00:%02d:%02d", m, s)
                Text(
                    text = timeStr,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            if (sleepRemaining > 0) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.sleep_timer_cancel),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** 自定义时长选择弹窗（miuix 风格） */
@Composable
private fun CustomDurationDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var hours by remember { mutableIntStateOf(0) }
    var minutes by remember { mutableIntStateOf(30) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.sleep_timer_custom_title),
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NumberStepper(
                        value = hours,
                        range = 0..23,
                        onValueChange = { hours = it }
                    )

                    Text(
                        text = " : ",
                        fontSize = 28.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    NumberStepper(
                        value = minutes,
                        range = 0..59,
                        onValueChange = { minutes = it }
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = {
                        val totalMinutes = hours * 60 + minutes
                        if (totalMinutes > 0) onConfirm(totalMinutes)
                    }) {
                        Text(
                            text = stringResource(R.string.action_confirm),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** 数字步进器（miuix 风格） */
@Composable
private fun NumberStepper(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TextButton(
            text = "▲",
            onClick = { if (value < range.last) onValueChange(value + 1) },
            colors = ButtonDefaults.textButtonColorsPrimary()
        )

        Text(
            text = String.format("%02d", value),
            fontSize = 36.sp,
            color = MaterialTheme.colorScheme.onSurface
        )

        TextButton(
            text = "▼",
            onClick = { if (value > range.first) onValueChange(value - 1) },
            colors = ButtonDefaults.textButtonColorsPrimary()
        )
    }
}

/** 播放列表弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistDialog(
    playlist: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    // 预处理数据
    val items = remember(playlist) {
        playlist.mapIndexed { idx, path ->
            path.substringAfterLast('/').substringBeforeLast('.') to idx
        }
    }

    val sheetState = rememberModalBottomSheetState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 自动滚动到当前播放项
    LaunchedEffect(currentIndex) {
        if (currentIndex > 0) {
            listState.animateScrollToItem(currentIndex)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.playlist_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                items(items.size) { idx ->
                    val (name, _) = items[idx]
                    val isCurrent = idx == currentIndex
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(idx)
                            }
                            .background(
                                if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                                else Color.Transparent
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCurrent) "▶ $name" else name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** 均衡器弹窗 */
@Composable
private fun EqualizerDialog(audioSessionId: Int, onDismiss: () -> Unit) {
    val vm: AudioPlayerViewModel = viewModel()
    var presets by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentPreset by remember { mutableIntStateOf(-1) }
    var equalizer by remember { mutableStateOf<android.media.audiofx.Equalizer?>(null) }

    DisposableEffect(audioSessionId) {
        if (audioSessionId < 0) return@DisposableEffect onDispose {}
        val eq = vm.getEqualizer(audioSessionId)
        equalizer = eq
        presets = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } } ?: emptyList()
        onDispose { equalizer?.release() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.equalizer_title),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            if (presets.isEmpty()) {
                Text(stringResource(R.string.equalizer_not_available),
                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                    item {
                        TextButton(
                            onClick = { equalizer?.enabled = false; currentPreset = -1 },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.equalizer_off),
                                color = if (currentPreset == -1) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    itemsIndexed(presets) { idx, name ->
                        TextButton(
                            onClick = { equalizer?.apply { enabled = true; usePreset(idx.toShort()) }; currentPreset = idx },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name,
                                color = if (idx == currentPreset) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

private fun formatTime(ms: Long): String {
    val safeMs = ms.coerceAtLeast(0L)
    val totalSeconds = safeMs / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

/**
 * USB 独占模式弹窗（暂时禁用）
 * 显示设备列表 + 格式选择，支持启用/禁用独占模式
 */
// @Composable
// private fun UsbExclusiveDialog(
//     isExclusive: Boolean,
//     exclusiveLabel: String,
//     onEnable: (UsbAudioDevice, AudioFormat, UsbAudioDeviceInfo) -> Unit,
//     onDisable: () -> Unit,
//     onDismiss: () -> Unit,
// ) {
//     val context = androidx.compose.ui.platform.LocalContext.current
//     val scope = rememberCoroutineScope()
//     val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager }
//     var devices by remember { mutableStateOf<List<UsbAudioDevice>>(emptyList()) }
//     var selectedDevice by remember { mutableStateOf<UsbAudioDevice?>(null) }
//     var deviceInfo by remember { mutableStateOf<UsbAudioDeviceInfo?>(null) }
//     var formats by remember { mutableStateOf<List<AudioFormat>>(emptyList()) }
//     var selectedFormat by remember { mutableStateOf<AudioFormat?>(null) }
//     var isScanning by remember { mutableStateOf(false) }
//     var statusMsg by remember { mutableStateOf("") }
//     var scanDone by remember { mutableStateOf(false) }
//
//     LaunchedEffect(Unit) {
//         android.util.Log.i("UsbExclusiveDialog", "LaunchedEffect: isExclusive=$isExclusive, scanDone=$scanDone")
//         if (!isExclusive && !scanDone) {
//             isScanning = true
//             statusMsg = context.getString(R.string.usb_scanning)
//
//             val foundDevices = withContext(Dispatchers.IO) { UsbAudioDeviceManager.scanDevices(usbManager) }
//             devices = foundDevices
//             android.util.Log.i("UsbExclusiveDialog", "Found ${foundDevices.size} devices")
//
//             if (foundDevices.isNotEmpty()) {
//                 selectedDevice = foundDevices.first()
//                 val dev = foundDevices.first()
//
//                 statusMsg = context.getString(R.string.usb_requesting_permission)
//                 val granted = withContext(Dispatchers.IO) {
//                     kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
//                         UsbAudioDeviceManager.requestPermission(dev.usbDevice, context) { g -> cont.resume(g) {} }
//                     }
//                 }
//                 android.util.Log.i("UsbExclusiveDialog", "Permission granted: $granted")
//                 if (granted) {
//                     statusMsg = context.getString(R.string.usb_opening_device)
//                     val info = withContext(Dispatchers.IO) { UsbAudioDeviceManager.openAndInit(dev, context) }
//                     android.util.Log.i("UsbExclusiveDialog", "Device info: $info")
//                     if (info != null) {
//                         deviceInfo = info
//                         statusMsg = context.getString(R.string.usb_scanning_formats)
//                         val bestFormat = AudioFormat(44100, info.bestBitDepth, 2)
//                         formats = listOf(
//                             AudioFormat(44100, 16, 2), AudioFormat(44100, 24, 2), AudioFormat(44100, 32, 2),
//                             AudioFormat(48000, 16, 2), AudioFormat(48000, 24, 2), AudioFormat(48000, 32, 2),
//                             AudioFormat(96000, 16, 2), AudioFormat(96000, 24, 2), AudioFormat(96000, 32, 2),
//                             AudioFormat(192000, 16, 2), AudioFormat(192000, 24, 2), AudioFormat(192000, 32, 2),
//                         )
//                         selectedFormat = formats.firstOrNull { it.bitDepth == bestFormat.bitDepth }
//                             ?: formats.firstOrNull()
//                         statusMsg = if (formats.isNotEmpty()) "" else context.getString(R.string.usb_no_formats_reported)
//                     } else {
//                         statusMsg = context.getString(R.string.usb_open_failed, "")
//                     }
//                 } else {
//                     statusMsg = context.getString(R.string.usb_permission_denied)
//                 }
//             } else {
//                 statusMsg = context.getString(R.string.usb_no_devices_found)
//             }
//             isScanning = false
//             scanDone = true
//         }
//     }
//
//     AlertDialog(
//         onDismissRequest = onDismiss,
//         title = {
//             Text(stringResource(R.string.usb_exclusive_title), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
//         },
//         text = {
//             Column(
//                 modifier = Modifier.fillMaxWidth(),
//                 horizontalAlignment = Alignment.CenterHorizontally,
//                 verticalArrangement = Arrangement.spacedBy(8.dp),
//             ) {
//                 if (isExclusive) {
//                     Text(stringResource(R.string.usb_current_format, exclusiveLabel),
//                         color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
//                     Text(stringResource(R.string.usb_exclusive_active_desc),
//                         fontSize = 13.sp, textAlign = TextAlign.Center)
//                 } else {
//                     Text(stringResource(R.string.usb_select_device_hint),
//                         fontSize = 13.sp, textAlign = TextAlign.Center)
//
//                     Spacer(Modifier.height(4.dp))
//
//                     if (isScanning) {
//                         Row(verticalAlignment = Alignment.CenterVertically) {
//                             CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
//                             Spacer(Modifier.width(8.dp))
//                             Text(statusMsg, fontSize = 13.sp, color = Color.Gray)
//                         }
//                     }
//
//                     if (!isScanning && statusMsg.isNotEmpty()) {
//                         Text(statusMsg, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
//                     }
//
//                     if (devices.isNotEmpty()) {
//                         devices.forEach { dev ->
//                             Row(
//                                 verticalAlignment = Alignment.CenterVertically,
//                                 modifier = Modifier.fillMaxWidth(),
//                             ) {
//                                 RadioButton(
//                                     selected = selectedDevice == dev,
//                                     onClick = { selectedDevice = dev }
//                                 )
//                                 Text(dev.productName, fontSize = 14.sp)
//                             }
//                         }
//                     }
//
//                     if (formats.isNotEmpty()) {
//                         Spacer(Modifier.height(4.dp))
//                         Text(stringResource(R.string.usb_supported_formats), fontSize = 13.sp, color = Color.Gray)
//                         val grouped = formats.groupBy { it.sampleRate }
//                         grouped.forEach { (rate, fmts) ->
//                             FlowRow(
//                                 horizontalArrangement = Arrangement.spacedBy(8.dp),
//                                 verticalArrangement = Arrangement.spacedBy(4.dp),
//                                 maxItemsInEachRow = 2,
//                             ) {
//                                 fmts.forEach { fmt ->
//                                     FilterChip(
//                                         selected = selectedFormat == fmt,
//                                         onClick = { selectedFormat = fmt },
//                                         label = { Text("${rate / 1000}k ${fmt.bitDepth}bit") },
//                                     )
//                                 }
//                             }
//                         }
//                     }
//
//                     if (!isScanning && scanDone) {
//                         Spacer(Modifier.height(4.dp))
//                         TextButton(onClick = {
//                             scanDone = false; devices = emptyList(); formats = emptyList()
//                             selectedFormat = null; statusMsg = ""; deviceInfo = null
//                         }) { Text(stringResource(R.string.usb_rescan), fontSize = 13.sp) }
//                     }
//                 }
//
//                 Spacer(Modifier.height(12.dp))
//                 val hasAction = isExclusive || (selectedDevice != null && selectedFormat != null)
//                 Row(
//                     modifier = Modifier.fillMaxWidth(),
//                     horizontalArrangement = if (hasAction) Arrangement.SpaceBetween else Arrangement.Center,
//                 ) {
//                     TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
//                     if (isExclusive) {
//                         TextButton(onClick = {
//                             scope.launch(Dispatchers.IO) {
//                                 val path = com.example.fold.audio.UsbAttachLogger.exportDiagnostics(context)
//                                 withContext(Dispatchers.Main) {
//                                     val msg = if (path != null) "Exported: ${File(path).name}" else "Export failed"
//                                     android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
//                                 }
//                             }
//                         }) { Text("Export Diag", fontSize = 13.sp) }
//                         TextButton(onClick = onDisable) {
//                             Text(stringResource(R.string.usb_disable_exclusive), color = Color.Red)
//                         }
//                     } else if (selectedDevice != null && selectedFormat != null && deviceInfo != null) {
//                         TextButton(onClick = { onEnable(selectedDevice!!, selectedFormat!!, deviceInfo!!) }) {
//                             Text(stringResource(R.string.usb_enable_exclusive))
//                         }
//                     }
//                 }
//             }
//         },
//         confirmButton = {},
//         dismissButton = {},
//     )
// }
