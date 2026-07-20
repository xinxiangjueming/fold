package com.example.fold.ui.player

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fold.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    // 歌词行自动滚动到屏幕中心
    val lyricsListState = rememberLazyListState()
    val lyricScreenHeightPx = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    // 歌词滚动性能诊断
    var lyricScrollCount by remember { mutableIntStateOf(0) }
    var lastScrollTime by remember { mutableLongStateOf(0L) }

    LaunchedEffect(state.currentLyricIndex, state.title) {
        if (state.currentLyricIndex >= 0 && state.currentLyricIndex < state.lyrics.size) {
            val t0 = android.os.SystemClock.elapsedRealtime()
            lyricsListState.animateScrollToItem(
                index = state.currentLyricIndex,
                scrollOffset = (-lyricScreenHeightPx / 2).toInt()
            )
            val t1 = android.os.SystemClock.elapsedRealtime()
            lyricScrollCount++
            val drift = t1 - lastScrollTime - 500
            lastScrollTime = t1
            // 每 10 次滚动记录一次性能
            if (lyricScrollCount % 10 == 0) {
                com.example.fold.util.FoldLogger.i("LyricsPerf", "scroll: #$lyricScrollCount, targetIdx=${state.currentLyricIndex}/${state.lyrics.size}, " +
                    "animTime=${t1-t0}ms, drift=${drift}ms, " +
                    "visibleItems=${lyricsListState.layoutInfo.visibleItemsInfo.size}, " +
                    "totalItems=${lyricsListState.layoutInfo.totalItemsCount}")
            }
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
        // ===== 横屏布局：左封面 + 右控制（椒盐音乐风格）=====
        Row(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左侧：封面（居中，大尺寸）
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                val coverScale by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = if (state.isPlaying) 1.2f else 1.1f,
                    animationSpec = androidx.compose.animation.core.tween(300),
                    label = "coverScale"
                )
                Box(
                    modifier = Modifier
                        .size(200.dp)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                        }
                        .squircleSurface(
                            color = surface,
                            cornerRadius = 10.dp,
                        ),
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
            }

            // 右侧：歌名 + 进度条 + 控制
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(start = 16.dp, end = 16.dp, top = 60.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 歌名
                Text(state.title, style = MaterialTheme.typography.titleSmall,
                    color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (state.playlistSize > 1) {
                    Text("${state.currentIndex + 1} / ${state.playlistSize}",
                        style = MiuixTheme.textStyles.footnote1, color = onSurfaceVar)
                }

                Spacer(Modifier.height(12.dp))

                // 进度条
                if (state.initialized) {
                    IndependentProgressBar(
                        exoPlayer = vm.exoPlayer,
                        duration = state.duration,
                        onSeek = { vm.seekTo(it) }
                    )
                }

                Spacer(Modifier.height(12.dp))

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

                // 功能按钮
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
        }
    } else {
    // ===== 竖屏布局 =====
    var currentPage by remember { mutableIntStateOf(0) }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenWidthPx = with(density) { LocalConfiguration.current.screenWidthDp.dp.toPx() }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    // 页面切换时滑动偏移量
    LaunchedEffect(currentPage) {
        val target = if (currentPage == 0) 0f else -screenWidthPx
        com.example.fold.util.FoldLogger.i("SwipeGesture", "pageSwitch: currentPage=$currentPage, targetOffset=${String.format("%.0f", target)}px")
        offset.animateTo(target, animationSpec = tween(300))
    }

    Box(Modifier.fillMaxSize()) {
        // 两个页面并排，通过偏移量控制显示
        Row(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = offset.value }
        ) {
            // 播放页
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(screenWidth)
            ) {
                PlayerPage(
                    albumArt = state.albumArt,
                    isPlaying = state.isPlaying,
                    title = state.title,
                    currentIndex = state.currentIndex,
                    playlistSize = state.playlistSize,
                    duration = state.duration,
                    initialized = state.initialized,
                    isImmersive = state.isImmersive,
                    loopMode = state.loopMode,
                    sleepRemaining = state.sleepRemaining,
                    onBack = onBack,
                    onPrev = { vm.prev() },
                    onTogglePlay = { vm.togglePlay() },
                    onNext = { vm.next() },
                    onLongPressToggle = { vm.toggleImmersive() },
                    onLoopChange = { vm.cycleLoopMode() },
                    onSleepClick = { showSleepDialog = true },
                    onEqualizerClick = handleEqualizerClick,
                    onPlaylistClick = { showPlaylist = true },
                    exoPlayer = if (state.initialized) vm.exoPlayer else null,
                    onSeek = { vm.seekTo(it) },
                    onSwipeToLyrics = {
                        com.example.fold.util.FoldLogger.i("SwipeGesture", "onSwipeToLyrics: currentPage 0->1")
                        currentPage = 1
                    },
                    gestureEnabled = currentPage == 0
                )
            }
            // 歌词页（垂直滚动由 LazyColumn 自然处理）
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(screenWidth)
            ) {
                LyricsPage(
                    lyrics = state.lyrics,
                    currentLyricIndex = state.currentLyricIndex,
                    listState = lyricsListState,
                    onSwipeToPlayer = {
                        com.example.fold.util.FoldLogger.i("SwipeGesture", "onSwipeToPlayer: currentPage 1->0")
                        currentPage = 0
                    }
                )
            }
        }
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
        val displayOrder = state.shuffledDisplayOrder

        // 随机模式下按随机顺序显示，否则原序
        val orderedNames = if (displayOrder.isNotEmpty()) {
            displayOrder.map { playlistNames[it] }
        } else {
            playlistNames
        }
        // 当前播放项在显示列表中的位置
        val displayCurrentIndex = if (displayOrder.isNotEmpty()) {
            displayOrder.indexOf(curIdx).coerceAtLeast(0)
        } else {
            curIdx
        }

        PlaylistDialog(
            playlist = orderedNames,
            currentIndex = displayCurrentIndex,
            onSelect = { displayIdx ->
                // 映射回原始索引
                val originalIdx = if (displayOrder.isNotEmpty()) displayOrder[displayIdx] else displayIdx
                vm.seekToIndex(originalIdx)
            },
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

/** 播放页 — 完整播放界面（封面 + 标题 + 进度条 + 控制 + 按钮） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerPage(
    albumArt: android.graphics.Bitmap?,
    isPlaying: Boolean,
    title: String,
    currentIndex: Int,
    playlistSize: Int,
    duration: Long,
    initialized: Boolean,
    isImmersive: Boolean,
    loopMode: Int,
    sleepRemaining: Int,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onLongPressToggle: () -> Unit,
    onLoopChange: () -> Unit,
    onSleepClick: () -> Unit,
    onEqualizerClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    exoPlayer: androidx.media3.exoplayer.ExoPlayer?,
    onSeek: (Long) -> Unit,
    onSwipeToLyrics: () -> Unit = {},
    gestureEnabled: Boolean = true,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ===== TopBar（沉浸模式隐藏）=====
        val topBarAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isImmersive) 0f else 1f,
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
                if (sleepRemaining > 0) {
                    val totalSec = sleepRemaining
                    val h = totalSec / 3600
                    val m = (totalSec % 3600) / 60
                    val s = totalSec % 60
                    val timeStr = if (h > 0) String.format("%d:%02d", h, m) else String.format("%02d:%02d", m, s)
                    Text(timeStr,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp))
                } else if (sleepRemaining == -1) {
                    Text(stringResource(R.string.sleep_timer_waiting),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background)
        )

        Spacer(Modifier.weight(1f))

        // ===== 封面（固定大小，居中）=====
        val coverScale by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isPlaying) 1.2f else 1.1f,
            animationSpec = androidx.compose.animation.core.tween(300),
            label = "coverScale"
        )
        var coverDragX by remember { mutableFloatStateOf(0f) }
        var coverDragCount by remember { mutableIntStateOf(0) }
        Box(
            modifier = Modifier
                .size(260.dp)
                .graphicsLayer {
                    scaleX = coverScale
                    scaleY = coverScale
                }
                .squircleSurface(
                    color = MaterialTheme.colorScheme.surface,
                    cornerRadius = 10.dp,
                )
                .pointerInput(gestureEnabled) {
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coverDragX = 0f
                            coverDragCount++
                            com.example.fold.util.FoldLogger.i("SwipeGesture", "coverDragStart: #$coverDragCount, gestureEnabled=$gestureEnabled")
                        },
                        onDragEnd = {
                            val triggered = gestureEnabled && coverDragX < -30
                            com.example.fold.util.FoldLogger.i("SwipeGesture", "coverDragEnd: totalX=${String.format("%.1f", coverDragX)}, gestureEnabled=$gestureEnabled, triggered=$triggered")
                            if (triggered) onSwipeToLyrics()
                            coverDragX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            coverDragX += dragAmount
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = albumArt,
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

        Spacer(Modifier.weight(1f))

        // ===== 曲目名 + 进度条（沉浸模式隐藏）=====
        val contentAlpha by androidx.compose.animation.core.animateFloatAsState(
            targetValue = if (isImmersive) 0f else 1f,
            animationSpec = androidx.compose.animation.core.tween(300),
            label = "contentAlpha"
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { alpha = contentAlpha }
        ) {
            Text(title, style = MiuixTheme.textStyles.main,
                color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (playlistSize > 1) {
                Text("${currentIndex + 1} / $playlistSize",
                    style = MiuixTheme.textStyles.footnote1, color = onSurfaceVar)
            }
            Spacer(Modifier.height(12.dp))
            if (initialized && exoPlayer != null) {
                IndependentProgressBar(
                    exoPlayer = exoPlayer,
                    duration = duration,
                    onSeek = onSeek
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ===== 播放控制 =====
        PlaybackControls(
            isPlaying = isPlaying,
            onPrev = onPrev,
            onToggle = onTogglePlay,
            onNext = onNext,
            onLongPressToggle = onLongPressToggle,
            tint = onSurface
        )

        Spacer(Modifier.height(12.dp))

        // ===== 功能按钮行（沉浸模式隐藏）=====
        FeatureButtons(
            loopMode = loopMode,
            sleepActive = sleepRemaining != 0,
            onLoopChange = onLoopChange,
            onSleepClick = onSleepClick,
            onEqualizerClick = onEqualizerClick,
            onPlaylistClick = onPlaylistClick,
            primaryColor = MaterialTheme.colorScheme.primary,
            variantColor = onSurfaceVar,
            visible = !isImmersive
        )

        Spacer(Modifier.weight(0.3f))
    }
}

/** 歌词页 — 固定中心线，内容滚动 */
@Composable
private fun LyricsPage(
    lyrics: List<Pair<Long, String>>,
    currentLyricIndex: Int,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onSwipeToPlayer: () -> Unit = {},
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val configuration = LocalConfiguration.current

    // 歌词 recomposition 诊断
    var recomposeCount by remember { mutableIntStateOf(0) }
    SideEffect {
        recomposeCount++
        // 每 50 次 recomposition 记录一次
        if (recomposeCount % 50 == 0) {
            com.example.fold.util.FoldLogger.i("LyricsPerf", "recompose: #$recomposeCount, " +
                "lyricsSize=${lyrics.size}, currentIndex=$currentLyricIndex, " +
                "visibleItems=${listState.layoutInfo.visibleItemsInfo.size}")
        }
    }

    var totalDragX by remember { mutableStateOf(0f) }
    var lyricsDragCount by remember { mutableIntStateOf(0) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        totalDragX = 0f
                        lyricsDragCount++
                        com.example.fold.util.FoldLogger.i("SwipeGesture", "lyricsDragStart: #$lyricsDragCount")
                    },
                    onDragEnd = {
                        val triggered = totalDragX > 30
                        com.example.fold.util.FoldLogger.i("SwipeGesture", "lyricsDragEnd: totalX=${String.format("%.1f", totalDragX)}, triggered=$triggered")
                        if (triggered) onSwipeToPlayer()
                        totalDragX = 0f
                    },
                    onHorizontalDrag = { _, dragAmount ->
                        totalDragX += dragAmount
                    }
                )
            }
    ) {
        if (lyrics.isNotEmpty()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 顶部 50% 屏幕高度空白
                item(key = "top_spacer") {
                    Spacer(Modifier.height(configuration.screenHeightDp.dp / 2))
                }

                items(lyrics.size, key = { it }) { idx ->
                    val viewportCenter = listState.layoutInfo.viewportEndOffset / 2f
                    val itemInfo = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == idx }
                    val itemCenter = itemInfo?.let { it.offset + it.size / 2f } ?: viewportCenter
                    val distanceFromCenter = kotlin.math.abs(itemCenter - viewportCenter)
                    val maxDistance = listState.layoutInfo.viewportEndOffset / 2f
                    val normalizedDistance = if (maxDistance > 0f) {
                        (distanceFromCenter / maxDistance).coerceIn(0f, 1f)
                    } else {
                        if (idx == currentLyricIndex) 0f else 1f
                    }

                    // 直接计算，无动画 — 滚动本身就是动画
                    val alpha = (1f - normalizedDistance * 0.8f).coerceIn(0.2f, 1f)
                    val fontSize = (20f - normalizedDistance * 6f).coerceAtLeast(14f)

                    Text(
                        text = lyrics[idx].second,
                        fontSize = fontSize.sp,
                        color = if (idx == currentLyricIndex) onSurface
                                else onSurfaceVar.copy(alpha = alpha),
                        textAlign = TextAlign.Center,
                        fontWeight = if (idx == currentLyricIndex) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }

                // 底部 50% 屏幕高度空白
                item(key = "bottom_spacer") {
                    Spacer(Modifier.height(configuration.screenHeightDp.dp / 2))
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

    // 逐帧更新位置（匹配屏幕刷新率）
    LaunchedEffect(exoPlayer) {
        displayPos = 0L  // player 切换时重置位置
        while (isActive) {
            withFrameNanos {
                if (!isDragging) {
                    displayPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                }
            }
        }
    }

    val pos = if (isDragging) dragPosition else displayPos
    val progress = if (duration > 0) pos.toFloat() / duration else 0f

    val barScale by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (isDragging) 1.05f else 1f,
        animationSpec = tween(150),
        label = "barScale"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (duration > 0) 1f else 0.3f)
    ) {
        // 波浪进度条（点击 + 拖拽跳转）
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
                                dragPosition = (offset.x / size.width * duration).toLong()
                            }
                        },
                        onDragEnd = {
                            if (isDragging) {
                                onSeek(dragPosition)
                                isDragging = false
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            if (duration > 0) {
                                val newPos = (dragPosition + dragAmount.x / size.width * duration).toLong()
                                    .coerceIn(0L, duration)
                                dragPosition = newPos
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
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }

        // 时间文字在进度条下方
        Box(Modifier.fillMaxWidth()) {
            // 底层：固定布局（左右时间）
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTime(if (duration > 0) displayPos else 0),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.4f else 1f)
                )
                Text(
                    formatTime(duration),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDragging) 0.4f else 1f)
                )
            }
            // 上层：拖拽时固定显示在左侧时间右边（不影响布局）
            androidx.compose.animation.AnimatedVisibility(
                visible = isDragging,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text(
                    "  ${formatTime(dragPosition)}",
                    modifier = Modifier.padding(start = 25.dp),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
            }
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
        sheetState = sheetState,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                stringResource(R.string.sleep_timer_title),
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
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
                                    .squircleSurface(
                                        color = MaterialTheme.colorScheme.primary,
                                        cornerRadius = 10.dp,
                                    )
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) { onSet(min, finishSong) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${min}",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MiuixTheme.textStyles.main
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
                    style = MiuixTheme.textStyles.main,
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
        sheetState = sheetState,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Text(
                stringResource(R.string.playlist_title),
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
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
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
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
