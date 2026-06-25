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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    // 进度更新时同步歌词索引
    LaunchedEffect(state.currentPosition) {
        vm.updateLyricIndex(state.currentPosition)
    }

    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVar = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface

    // 弹窗状态（提升到 Column 外部）
    var showSleepDialog by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    var showPlaylist by remember { mutableStateOf(false) }

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

        // ===== 进度条 =====
        ProgressBar(
            position = state.currentPosition,
            duration = state.duration,
            onSeek = { vm.seekTo(it) }
        )

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
        PlaylistDialog(
            playlist = state.playlistPaths.map { it.substringAfterLast('/').substringBeforeLast('.') },
            currentIndex = state.currentIndex,
            onSelect = { vm.seekToIndex(it); showPlaylist = false },
            onDismiss = { showPlaylist = false }
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
                items(lyrics.size) { idx ->
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
                    Image(bitmap = art.asImageBitmap(), contentDescription = null,
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

/** 进度条 + 时间 */
@Composable
private fun ProgressBar(
    position: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    if (duration <= 0) return
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(position) }
    val displayPos = if (isDragging) dragPosition else position

    Slider(
        value = displayPos.toFloat(),
        onValueChange = { isDragging = true; dragPosition = it.toLong() },
        onValueChangeFinished = { onSeek(dragPosition); isDragging = false },
        valueRange = 0f..duration.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(displayPos), style = MaterialTheme.typography.bodySmall,
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
            Icon(Icons.Filled.QueueMusic, contentDescription = null, tint = variantColor)
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
                itemsIndexed(playlist) { idx, name ->
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
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
