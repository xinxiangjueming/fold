package com.example.fold.ui.player

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.fold.R
import com.example.fold.audio.UsbAudioNative
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
    onBack: () -> Unit
) {
    val vm: AudioPlayerViewModel = viewModel()
    val state by vm.state.collectAsState()

    android.util.Log.d("AudioPlayer", "Screen compose: filePath=$filePath, playlist.size=${playlist.size}")

    // filePath 变化时重新初始化（切换歌曲）
    LaunchedEffect(filePath) {
        android.util.Log.d("AudioPlayer", "init called, filePath=$filePath")
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
    var showEqualizer by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }
    var showUsbDialog by remember { mutableStateOf(false) }

    // USB 独占模式状态
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var isExclusive by remember { mutableStateOf(MusicPlayerHolder.isExclusiveMode) }
    var exclusiveLabel by remember { mutableStateOf("") }

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
                    IconButton(onClick = { showUsbDialog = true }) {
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
            surface = surface
        )

        Spacer(Modifier.height(16.dp))

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
            onEqualizerClick = { showEqualizer = true },
            onPlaylistClick = { showPlaylist = true },
            primaryColor = MaterialTheme.colorScheme.primary,
            variantColor = onSurfaceVar
        )

        Spacer(Modifier.weight(0.5f))
    }

    // ===== 弹窗 =====
    if (showSleepDialog) {
        SleepTimerDialog(
            sleepMinutes = state.sleepMinutes,
            onSet = { min, finish -> showSleepDialog = false; vm.setSleep(min, finish) },
            onCancel = { showSleepDialog = false; vm.cancelSleep() },
            onDismiss = { showSleepDialog = false }
        )
    }
    if (showEqualizer) {
        EqualizerDialog(state.audioSessionId) { showEqualizer = false }
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

    // ===== USB 独占模式弹窗 =====
    if (showUsbDialog) {
        UsbExclusiveDialog(
            isExclusive = isExclusive,
            exclusiveLabel = exclusiveLabel,
            onEnable = { device, format ->
                scope.launch {
                    // USB init 在 IO
                    val usbReady = withContext(Dispatchers.IO) {
                        val ua = MusicPlayerHolder.usbAudio ?: UsbAudioNative(context).also { MusicPlayerHolder.setUsbAudio(it) }
                        val (ok, _) = ua.openAndInit(device)
                        ok
                    }
                    if (usbReady) {
                        // 切换模式 + 释放旧 player
                        MusicPlayerHolder.enableExclusiveMode(context, device, format)
                        MusicPlayerHolder.releasePlayer()
                        isExclusive = true
                        exclusiveLabel = "${device.productName} ${format.label}"
                        // ViewModel 重新 init → getOrCreate 创建独占 player
                        vm.init(filePath, state.playlistPaths)
                    }
                }
                showUsbDialog = false
            },
            onDisable = {
                MusicPlayerHolder.disableExclusiveMode(context)
                MusicPlayerHolder.releasePlayer()
                isExclusive = false
                exclusiveLabel = ""
                // ViewModel 重新 init → getOrCreate 创建普通 player
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
    surface: Color
) {
    Box(
        Modifier.fillMaxWidth().height(280.dp),
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
    if (duration <= 0) return

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
        value = pos.toFloat(),
        onValueChange = { isDragging = true; dragPosition = it.toLong() },
        onValueChangeFinished = {
            displayPos = dragPosition
            onSeek(dragPosition)
            isDragging = false
        },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(pos), style = MaterialTheme.typography.bodySmall,
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDialog(
    playlist: List<String>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.playlist_title),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                itemsIndexed(playlist, key = { idx, _ -> idx }) { idx, name ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (idx == currentIndex) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else Color.Transparent
                            )
                            .combinedClickable(onClick = { onSelect(idx) })
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        if (idx == currentIndex) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(name, style = MaterialTheme.typography.bodyMedium,
                            color = if (idx == currentIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
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
    onEnable: (UsbAudioNative.UsbAudioDevice, UsbAudioNative.AudioFormat) -> Unit,
    onDisable: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<UsbAudioNative.UsbAudioDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<UsbAudioNative.UsbAudioDevice?>(null) }
    var formats by remember { mutableStateOf<List<UsbAudioNative.AudioFormat>>(emptyList()) }
    var selectedFormat by remember { mutableStateOf<UsbAudioNative.AudioFormat?>(null) }
    var isScanning by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf("") }
    var scanDone by remember { mutableStateOf(false) }

    /* 自动扫描：弹窗打开时立即开始 */
    LaunchedEffect(Unit) {
        if (!isExclusive && !scanDone) {
            isScanning = true
            statusMsg = "扫描设备中..."

            val usbNative = UsbAudioNative(context)
            val foundDevices = withContext(Dispatchers.IO) { usbNative.scanDevices() }
            devices = foundDevices

            if (foundDevices.isNotEmpty()) {
                selectedDevice = foundDevices.first()
                val dev = foundDevices.first()

                statusMsg = "请求 USB 权限..."
                val granted = withContext(Dispatchers.IO) {
                    kotlinx.coroutines.suspendCancellableCoroutine<Boolean> { cont ->
                        usbNative.requestPermission(dev.usbDevice) { g -> cont.resume(g) {} }
                    }
                }
                if (granted) {
                    statusMsg = "打开设备..."
                    val (ok, info) = withContext(Dispatchers.IO) { usbNative.openAndInit(dev) }
                    if (ok) {
                        statusMsg = "扫描支持的格式..."
                        formats = withContext(Dispatchers.IO) { usbNative.scanSupportedFormats() }
                        selectedFormat = formats.firstOrNull()
                        statusMsg = if (formats.isNotEmpty()) "" else "设备未报告支持的格式"
                        MusicPlayerHolder.setUsbAudio(usbNative)
                    } else {
                        statusMsg = "打开设备失败: $info"
                    }
                } else {
                    statusMsg = "USB 权限被拒绝"
                }
            } else {
                statusMsg = "未发现 USB Audio 设备"
            }
            isScanning = false
            scanDone = true
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("USB 独占模式", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isExclusive) {
                    Text("当前: $exclusiveLabel", color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
                    Text("独占模式已激活\n音频数据直接送达 DAC，绕过系统 mixer",
                        fontSize = 13.sp, textAlign = TextAlign.Center)
                } else {
                    Text("扫描 USB 音频设备，选择后启用独占模式",
                        fontSize = 13.sp, textAlign = TextAlign.Center)

                    Spacer(Modifier.height(4.dp))

                    /* 扫描进度 */
                    if (isScanning) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(statusMsg, fontSize = 13.sp, color = Color.Gray)
                        }
                    }

                    /* 扫描完成后的状态 */
                    if (!isScanning && statusMsg.isNotEmpty()) {
                        Text(statusMsg, fontSize = 12.sp, color = Color.Gray, textAlign = TextAlign.Center)
                    }

                    /* 设备列表 */
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

                    /* 格式选择 */
                    if (formats.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("支持的格式:", fontSize = 13.sp, color = Color.Gray)
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

                    /* 重新扫描按钮 */
                    if (!isScanning && scanDone) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(onClick = {
                            scanDone = false
                            devices = emptyList()
                            formats = emptyList()
                            selectedFormat = null
                            statusMsg = ""
                        }) { Text("重新扫描", fontSize = 13.sp) }
                    }
                }

                /* 按钮行：单独取消时居中，有操作按钮时取消在左、操作在右 */
                Spacer(Modifier.height(12.dp))
                val hasAction = isExclusive || (selectedDevice != null && selectedFormat != null)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (hasAction) Arrangement.SpaceBetween else Arrangement.Center,
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    if (isExclusive) {
                        TextButton(onClick = onDisable) {
                            Text("关闭独占", color = Color.Red)
                        }
                    } else if (selectedDevice != null && selectedFormat != null) {
                        TextButton(onClick = { onEnable(selectedDevice!!, selectedFormat!!) }) {
                            Text("启用独占")
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}
