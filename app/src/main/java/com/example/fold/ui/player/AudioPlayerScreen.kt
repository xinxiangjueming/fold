package com.example.fold.ui.player

import android.content.Context
import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider
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
import com.example.fold.audio.AudioFormat
import com.example.fold.audio.UsbAudioDevice
import com.example.fold.audio.UsbAudioDeviceInfo
import com.example.fold.audio.UsbAudioDeviceManager
import com.example.fold.audio.UsbAudioStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    // 歌词行自动滚动
    val lyricsListState = rememberLazyListState()
    LaunchedEffect(state.currentLyricIndex) {
        if (state.currentLyricIndex > 0) {
            lyricsListState.animateScrollToItem(
                (state.currentLyricIndex - 2).coerceAtLeast(0)
            )
        }
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    // 弹窗状态（提升到 Column 外部）
    var showSleepDialog by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showUsbDialog by remember { mutableStateOf(false) }

    // 均衡器按钮点击 → 导航到 EQ 界面
    val handleEqualizerClick: () -> Unit = { onNavigateToEq() }

    // USB 独占模式状态
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val isExclusive by MusicPlayerHolder.isExclusiveMode
    var exclusiveLabel by remember { mutableStateOf("") }

    // 检测横竖屏
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
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
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(surface),
                    contentAlignment = Alignment.Center
                ) {
                    val art = state.albumArt
                    if (art != null) {
                        val imageBitmap = remember(art) { art.asImageBitmap() }
                        Image(bitmap = imageBitmap, contentDescription = null,
                            modifier = Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Filled.MusicNote, contentDescription = null,
                            modifier = Modifier.size(60.dp),
                            tint = onSurfaceVar.copy(alpha = 0.5f))
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
                    variantColor = onSurfaceVar
                )
            }

            // 右侧：歌词
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(end = 16.dp, top = 8.dp, bottom = 8.dp)
            ) {
                // 歌词标题
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.reader_chapters),
                        style = MaterialTheme.typography.labelMedium, color = onSurfaceVar)
                    if (state.lyrics.isNotEmpty()) {
                        IconButton(onClick = { vm.toggleLyrics() }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                if (state.showLyrics) Icons.Filled.MusicNote else Icons.Filled.Lyrics,
                                contentDescription = null, modifier = Modifier.size(18.dp),
                                tint = onSurfaceVar
                            )
                        }
                    }
                }

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
        // ===== TopBar =====
        TopAppBar(
            title = { Text(stringResource(R.string.audio_player_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                // USB 独占模式按钮
                if (MusicPlayerHolder.isExclusiveSupported()) {
                    IconButton(onClick = {
                        android.util.Log.i("AudioPlayerScreen", "USB button clicked, showUsbDialog=$showUsbDialog")
                        showUsbDialog = true
                    }) {
                        Icon(
                            Icons.Default.Usb,
                            contentDescription = "USB 独占",
                            tint = if (isExclusive) MaterialTheme.colorScheme.primary else onSurfaceVar
                        )
                    }
                }
                if (state.sleepRemaining > 0) {
                    Text("${state.sleepRemaining}min",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 8.dp))
                } else if (state.sleepRemaining == -1) {
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

        // ===== 曲目名 =====
        Text(state.title, style = MaterialTheme.typography.titleMedium,
            color = onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (state.playlistSize > 1) {
            Text("${state.currentIndex + 1} / ${state.playlistSize}",
                style = MaterialTheme.typography.bodySmall, color = onSurfaceVar)
        }

        Spacer(Modifier.height(12.dp))

        // ===== 进度条（独立轮询，不触发整屏重组）=====
        if (state.initialized) {
            IndependentProgressBar(
                exoPlayer = vm.exoPlayer,
                duration = state.duration,
                onSeek = { vm.seekTo(it) }
            )
        }

        Spacer(Modifier.height(12.dp))

        // ===== 播放控制 =====
        PlaybackControls(
            isPlaying = state.isPlaying,
            onPrev = { vm.prev() },
            onToggle = { vm.togglePlay() },
            onNext = { vm.next() },
            tint = onSurface
        )

        Spacer(Modifier.height(12.dp))

        // ===== 功能按钮行 =====
        FeatureButtons(
            loopMode = state.loopMode,
            sleepActive = state.sleepRemaining != 0,
            onLoopChange = { vm.cycleLoopMode() },
            onSleepClick = { showSleepDialog = true },
            onEqualizerClick = handleEqualizerClick,
            onPlaylistClick = { showPlaylist = true },
            primaryColor = MaterialTheme.colorScheme.primary,
            variantColor = onSurfaceVar
        )

        Spacer(Modifier.weight(0.5f))
    }
    } // end else (竖屏)

    // ===== 弹窗 =====
    if (showSleepDialog) {
        SleepTimerDialog(
            sleepMinutes = state.sleepMinutes,
            onSet = { min, finish -> showSleepDialog = false; vm.setSleep(min, finish) },
            onCancel = { showSleepDialog = false; vm.cancelSleep() },
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
            onSelect = { vm.seekToIndex(it); showPlaylist = false },
            onDismiss = { showPlaylist = false }
        )
    }

    // ===== USB Exclusive Mode Dialog =====
    if (showUsbDialog) {
        android.util.Log.i("AudioPlayerScreen", "Showing USB Exclusive Dialog")
        UsbExclusiveDialog(
            isExclusive = isExclusive,
            exclusiveLabel = exclusiveLabel,
            onEnable = { device, format, deviceInfo ->
                scope.launch {
                    android.util.Log.i("AudioPlayerScreen", "onEnable: device=${device.productName}, format=${format.label}, fd=${deviceInfo.fd}")
                    val stream = withContext(Dispatchers.IO) {
                        UsbAudioStream.create(deviceInfo, format.sampleRate, format.channels, format.bitDepth)
                    }
                    android.util.Log.i("AudioPlayerScreen", "Stream created: ${stream != null}, handle=${stream?.nativeHandle ?: 0}")
                    if (stream != null) {
                        MusicPlayerHolder.setStream(stream)
                        MusicPlayerHolder.enableExclusiveMode(context, device, format, deviceInfo)
                        MusicPlayerHolder.releasePlayer()
                        exclusiveLabel = "${device.productName} ${format.label}"
                        vm.init(filePath, state.playlistPaths)
                    } else {
                        android.widget.Toast.makeText(context, "USB 流创建失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    showUsbDialog = false
                }
            },
            onDisable = {
                MusicPlayerHolder.disableExclusiveMode(context)
                MusicPlayerHolder.releasePlayer()
                exclusiveLabel = ""
                vm.init(filePath, state.playlistPaths)
                showUsbDialog = false
            },
            onDismiss = { showUsbDialog = false }
        )
    }
}

// ==================== 子组件 ====================

/** 封面 / 歌词切换区 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumOrLyrics(
    albumArt: android.graphics.Bitmap?,
    showLyrics: Boolean,
    lyrics: List<Pair<Long, String>>,
    currentLyricIndex: Int,
    lyricsListState: androidx.compose.foundation.lazy.LazyListState,
    onToggleLyrics: () -> Unit,
    onSurface: Color,
    onSurfaceVar: Color,
    surface: Color,
    modifier: Modifier = Modifier
) {
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
                items(lyrics.size, key = { it }) { idx ->
                    Text(
                        text = lyrics[idx].second,
                        fontSize = if (idx == currentLyricIndex) 20.sp else 15.sp,
                        color = if (idx == currentLyricIndex) onSurface
                                else onSurfaceVar.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    )
                }
            }
        }

        AnimatedVisibility(visible = !showLyrics || lyrics.isEmpty(), enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .size(240.dp)
                    .clip(CircleShape)
                    .background(surface)
                    .combinedClickable(onClick = {}, onDoubleClick = onToggleLyrics),
                contentAlignment = Alignment.Center
            ) {
                val art = albumArt
                if (art != null) {
                    // remember 缓存：bitmap 不变就不重建 ImageBitmap，避免每帧重绘
                    val imageBitmap = remember(art) { art.asImageBitmap() }
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
 * 独立进度条 — 自己管理 250ms 轮询，不依赖主 StateFlow
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

    MiuixSlider(
        value = if (duration > 0) pos.toFloat() else 0f,
        onValueChange = { isDragging = true; dragPosition = it.toLong() },
        onValueChangeFinished = {
            if (duration > 0) {
                displayPos = dragPosition
                onSeek(dragPosition)
            }
            isDragging = false
        },
        valueRange = 0f..effectiveDuration.toFloat(),
        modifier = Modifier.fillMaxWidth().alpha(if (duration > 0) 1f else 0.3f),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(if (duration > 0) pos else 0), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatTime(duration), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 播放控制按钮 */
@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    onPrev: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
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
        FilledIconButton(onClick = onToggle, modifier = Modifier.size(64.dp)) {
            Icon(
                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = null, modifier = Modifier.size(36.dp)
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
    variantColor: Color
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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

/** 睡眠定时弹窗 */
@Composable
private fun SleepTimerDialog(
    sleepMinutes: Int,
    onSet: (Int, Boolean) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    var finishSong by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.sleep_timer_title),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            Column {
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
                Spacer(Modifier.height(8.dp))
                listOf(15, 30, 45, 60, 90).forEach { min ->
                    TextButton(onClick = { onSet(min, finishSong) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sleep_timer_minutes, min))
                    }
                }
                if (sleepMinutes > 0) {
                    TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.sleep_timer_cancel),
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

/** 播放列表弹窗 */
@Composable
private fun PlaylistDialog(
    playlist: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    // 预处理数据
    val items = remember(playlist) {
        playlist.mapIndexed { idx, path ->
            PlaylistItem(
                path = path,
                displayName = path.substringAfterLast('/').substringBeforeLast('.'),
                index = idx
            )
        }
    }

    val adapter = remember {
        PlaylistAdapter { idx ->
            onSelect(idx)
            onDismiss()
        }
    }

    // 更新数据
    LaunchedEffect(items, currentIndex) {
        adapter.submitList(items)
        adapter.currentIndex = currentIndex
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.playlist_title),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            AndroidView(
                factory = { ctx ->
                    androidx.recyclerview.widget.RecyclerView(ctx).apply {
                        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                        this.adapter = adapter
                        // 自动滚动到当前播放项
                        if (currentIndex > 0) {
                            (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                                ?.scrollToPositionWithOffset(currentIndex, 200)
                        }
                    }
                },
                modifier = Modifier.heightIn(max = 400.dp)
            )
        },
        confirmButton = {},
        dismissButton = {}
    )
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
 * USB 独占模式弹窗
 * 显示设备列表 + 格式选择，支持启用/禁用独占模式
 */
@Composable
private fun UsbExclusiveDialog(
    isExclusive: Boolean,
    exclusiveLabel: String,
    onEnable: (UsbAudioDevice, AudioFormat, UsbAudioDeviceInfo) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager }
    var devices by remember { mutableStateOf<List<UsbAudioDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<UsbAudioDevice?>(null) }
    var deviceInfo by remember { mutableStateOf<UsbAudioDeviceInfo?>(null) }
    var formats by remember { mutableStateOf<List<AudioFormat>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<AudioFormat?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var scanDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        android.util.Log.i("UsbExclusiveDialog", "LaunchedEffect: isExclusive=$isExclusive, scanDone=$scanDone")
        if (!isExclusive && !scanDone) {
            isScanning = true
            statusMsg = context.getString(R.string.usb_scanning)

            val foundDevices = withContext(Dispatchers.IO) { UsbAudioDeviceManager.scanDevices(usbManager) }
            devices = foundDevices
            android.util.Log.i("UsbExclusiveDialog", "Found ${foundDevices.size} devices")

            if (foundDevices.isNotEmpty()) {
                selectedDevice = foundDevices.first()
                val dev = foundDevices.first()

                statusMsg = context.getString(R.string.usb_requesting_permission)
                val granted = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        UsbAudioDeviceManager.requestPermission(dev.usbDevice, context) { g -> cont.resume(g) {} }
                    }
                }
                android.util.Log.i("UsbExclusiveDialog", "Permission granted: $granted")
                if (granted) {
                    statusMsg = context.getString(R.string.usb_opening_device)
                    val info = withContext(Dispatchers.IO) { UsbAudioDeviceManager.openAndInit(dev, context) }
                    android.util.Log.i("UsbExclusiveDialog", "Device info: $info")
                    if (info != null) {
                        deviceInfo = info
                        statusMsg = context.getString(R.string.usb_scanning_formats)
                        val bestFormat = AudioFormat(44100, info.bestBitDepth, 2)
                        formats = listOf(
                            AudioFormat(44100, 16, 2), AudioFormat(44100, 24, 2), AudioFormat(44100, 32, 2),
                            AudioFormat(48000, 16, 2), AudioFormat(48000, 24, 2), AudioFormat(48000, 32, 2),
                            AudioFormat(96000, 16, 2), AudioFormat(96000, 24, 2), AudioFormat(96000, 32, 2),
                            AudioFormat(192000, 16, 2), AudioFormat(192000, 24, 2), AudioFormat(192000, 32, 2),
                        )
                        selectedFormat = formats.firstOrNull { it.bitDepth == bestFormat.bitDepth }
                            ?: formats.firstOrNull()
                        statusMsg = if (formats.isNotEmpty()) "" else context.getString(R.string.usb_no_formats_reported)
                    } else {
                        statusMsg = context.getString(R.string.usb_open_failed, "")
                    }
                } else {
                    statusMsg = context.getString(R.string.usb_permission_denied)
                }
            } else {
                statusMsg = context.getString(R.string.usb_no_devices_found)
            }
            isScanning = false
            scanDone = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.usb_exclusive_title), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isExclusive) {
                    Text(stringResource(R.string.usb_current_format, exclusiveLabel),
                        color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.usb_exclusive_active_desc),
                        fontSize = 13.sp, textAlign = TextAlign.Center)
                } else {
                    Text(stringResource(R.string.usb_select_device_hint),
                        fontSize = 13.sp, textAlign = TextAlign.Center)

                    Spacer(Modifier.height(4.dp))

                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(statusMsg, fontSize = 13.sp, color = Color.Gray)
                        }
                    }

                    if (!isScanning && statusMsg.isNotEmpty()) {
                        Text(statusMsg, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    }

                    if (devices.isNotEmpty()) {
                        devices.forEach { dev ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                RadioButton(
                                    selected = selectedDevice == dev,
                                    onClick = { selectedDevice = dev }
                                )
                                Text(dev.productName, fontSize = 14.sp)
                            }
                        }
                    }

                    if (formats.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.usb_supported_formats), fontSize = 13.sp, color = Color.Gray)
                        val grouped = formats.groupBy { it.sampleRate }
                        grouped.forEach { (rate, fmts) ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                fmts.forEach { fmt ->
                                    FilterChip(
                                        selected = selectedFormat == fmt,
                                        onClick = { selectedFormat = fmt },
                                        label = { Text("${rate / 1000}k ${fmt.bitDepth}bit") },
                                    )
                                }
                            }
                        }
                    }

                    if (!isScanning && scanDone) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            scanDone = false; devices = emptyList(); formats = emptyList()
                            selectedFormat = null; statusMsg = ""; deviceInfo = null
                        }) { Text(stringResource(R.string.usb_rescan), fontSize = 13.sp) }
                    }
                }

                Spacer(Modifier.height(12.dp))
                val hasAction = isExclusive || (selectedDevice != null && selectedFormat != null)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (hasAction) Arrangement.SpaceBetween else Arrangement.Center,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    if (isExclusive) {
                        TextButton(onClick = onDisable) {
                            Text(stringResource(R.string.usb_disable_exclusive), color = Color.Red)
                        }
                    } else if (selectedDevice != null && selectedFormat != null && deviceInfo != null) {
                        TextButton(onClick = { onEnable(selectedDevice!!, selectedFormat!!, deviceInfo!!) }) {
                            Text(stringResource(R.string.usb_enable_exclusive))
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
