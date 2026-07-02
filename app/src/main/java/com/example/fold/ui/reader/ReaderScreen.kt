package com.example.fold.ui.reader

import android.net.Uri
import android.os.Build
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fold.MainActivity
import com.example.fold.R
import com.example.fold.data.model.BookmarkEntry
import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import com.example.fold.data.model.ReadingTheme
import com.example.fold.data.reader.TtsState
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    filePath: String,
    onBack: () -> Unit,
    viewModel: ReaderViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsState.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var showChapterList by remember { mutableStateOf(false) }
    var showBookmarkList by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 通知权限申请（Android 13+）
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // 不管是否授权都启动 TTS，通知只是辅助功能
        viewModel.startTts()
    }
    val bookmarkSavedMsg = stringResource(R.string.reader_bookmark_saved)

    // 标记阅读器活跃状态（供 MainActivity 音量键拦截判断）
    DisposableEffect(Unit) {
        MainActivity.readerActive = true
        FoldLogger.i("ReaderVol", "ReaderScreen: readerActive=true")
        onDispose {
            MainActivity.readerActive = false
            FoldLogger.i("ReaderVol", "ReaderScreen: readerActive=false")
        }
    }

    // 息屏归隐：注册 ACTION_SCREEN_OFF 广播接收器
    val screenOffReceiver = remember {
        object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                if (intent?.action == android.content.Intent.ACTION_SCREEN_OFF) {
                    val stealth = viewModel.state.value.stealthMode
                    FoldLogger.i("ReaderScreen", "ACTION_SCREEN_OFF received, stealthMode=$stealth")
                    if (stealth) {
                        viewModel.onScreenOff()
                        MainActivity.pendingStealthNavigate = true
                    }
                }
            }
        }
    }
    DisposableEffect(Unit) {
        val filter = android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)
        context.registerReceiver(screenOffReceiver, filter)
        onDispose {
            try { context.unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
        }
    }

    // 音量键翻页：滚动逻辑在各格式内容组件内处理（TxtReaderContent / EpubReaderContent / PdfReaderContent）

    // 读取计算器伪装状态（用于控制息屏归隐选项的显示）
    val calculatorMode = remember {
        context.getSharedPreferences("file_sort", android.content.Context.MODE_PRIVATE)
            .getBoolean("calculator_mode", false)
    }

    // 返回手势拦截：面板打开时按返回关闭面板，而不是退出阅读器
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showChapterList) { showChapterList = false }
    BackHandler(enabled = showBookmarkList) { showBookmarkList = false }

    // 阅读主题颜色
    val theme = state.readingTheme
    val bgColor = androidx.compose.ui.graphics.Color(theme.bgColor)
    val textColor = androidx.compose.ui.graphics.Color(theme.textColor)
    FoldLogger.d("Reader", "theme=${stringResource(theme.labelResId)}, isLight=${theme.isLight}, bgColor=$bgColor, textColor=$textColor")

    // 用阅读主题颜色覆盖 MaterialTheme，让所有组件跟随主题变化
    val readerColorScheme = MaterialTheme.colorScheme.copy(
        background = bgColor,
        onBackground = textColor,
        surface = bgColor,
        onSurface = textColor,
        surfaceVariant = bgColor,
        onSurfaceVariant = textColor,
        surfaceContainerLowest = bgColor,
        surfaceContainerLow = bgColor,
        surfaceContainer = bgColor,
        surfaceContainerHigh = bgColor,
        surfaceContainerHighest = bgColor,
        outline = textColor.copy(alpha = 0.2f),
        primaryContainer = textColor.copy(alpha = 0.12f),
        onPrimaryContainer = textColor
    )

    LaunchedEffect(filePath) {
        viewModel.openFile(filePath)
    }

    MaterialTheme(colorScheme = readerColorScheme) {
    // 不用 Scaffold，避免 contentWindowInsets 每帧重算布局
    Box(Modifier.fillMaxSize().background(bgColor)) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = state.fileName.ifEmpty { stringResource(R.string.reader_title) },
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium,
                        color = textColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showChapterList = true }) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.reader_chapters))
                    }
                    IconButton(onClick = {
                        viewModel.saveBookmark()
                        scope.launch { snackbarHostState.showSnackbar(bookmarkSavedMsg) }
                    }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = stringResource(R.string.reader_save_bookmark))
                    }
                    IconButton(onClick = {
                        viewModel.loadBookmarks()
                        showBookmarkList = true
                    }) {
                        Icon(Icons.Filled.Bookmark, contentDescription = stringResource(R.string.reader_load_bookmark))
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.reader_settings))
                    }
                    IconButton(onClick = {
                        if (ttsState.isPlaying) {
                            viewModel.stopTts()
                        } else if (!ttsState.isAvailable) {
                            // 引导用户安装 TTS 引擎
                            try {
                                context.startActivity(android.content.Intent("com.android.settings.TTS_SETTINGS").apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (_: Exception) {
                                scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.tts_no_engine)) }
                            }
                        } else {
                            // 申请通知权限后启动 TTS
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                viewModel.startTts()
                            }
                        }
                    }) {
                        Icon(
                            if (ttsState.isPlaying) Icons.Filled.StopCircle
                            else if (!ttsState.isAvailable) Icons.AutoMirrored.Filled.VolumeOff
                            else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.reader_tts),
                            tint = if (ttsState.isPlaying) MaterialTheme.colorScheme.primary else textColor
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bgColor,
                    titleContentColor = textColor,
                    navigationIconContentColor = textColor,
                    actionIconContentColor = textColor
                )
            )

            // 内容区填满剩余空间，背景延伸到导航栏区域（沉浸）
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
            ) {
                when {
                    state.isLoading -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    state.error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(8.dp))
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    else -> {
                        when (state.fileType) {
                            "txt" -> TxtReaderContent(
    viewModel = viewModel, state = state, bgColor = bgColor, textColor = textColor,
    settingsOpen = showSettings,
    ttsParagraphIndex = if (ttsState.isPlaying && state.fileType == "txt") ttsState.currentParagraph else -1,
    ttsHighlightColor = MaterialTheme.colorScheme.primary,
    ttsCharStart = ttsState.speakingCharStart,
    ttsCharEnd = ttsState.speakingCharEnd
)
                            "epub" -> EpubReaderContent(viewModel = viewModel, state = state)
                            "pdf" -> PdfReaderContent(viewModel = viewModel, state = state, bgColor = bgColor, textColor = textColor)
                        }
                    }
                }

                if (showSettings) {
                    com.example.fold.ui.reader.ReaderSettingsPanel(
                        state = state,
                        onFontSizeChange = { viewModel.updateFontSize(it) },
                        onLineSpacingChange = { viewModel.updateLineSpacing(it) },
                        onFontImport = { uri -> viewModel.importFont(uri) },
                        onFontReset = { viewModel.resetFont() },
                        onThemeChange = { viewModel.updateReadingTheme(it) },
                        onMarginsChange = { l, r -> viewModel.updateMargins(l, r) },
                        onReSegmentToggle = { viewModel.toggleReSegment() },
                        onChineseConvertCycle = { viewModel.cycleChineseConvert() },
                        onAddReplacement = { o, r -> viewModel.addWordReplacement(o, r) },
                        onRemoveReplacement = { o -> viewModel.removeWordReplacement(o) },
                        onVolumePageTurnToggle = { viewModel.toggleVolumePageTurn() },
                        onStealthModeToggle = { viewModel.toggleStealthMode() },
                        showStealthMode = calculatorMode,
                        onDismiss = { showSettings = false }
                    )
                }

                if (showChapterList) {
                    ChapterListSheet(
                        chapters = viewModel.getChapters(),
                        currentIndex = state.currentChapterIndex,
                        onSelect = { viewModel.seekToChapter(it) },
                        onDismiss = { showChapterList = false }
                    )
                }

                if (showBookmarkList) {
                    BookmarkListSheet(
                        bookmarks = viewModel.bookmarks.collectAsStateWithLifecycle().value,
                        onSelect = { viewModel.jumpToBookmark(it); showBookmarkList = false },
                        onDelete = { viewModel.deleteBookmark(it) },
                        onDismiss = { showBookmarkList = false }
                    )
                }
            }
        }

        // TTS 浮动控制栏
        if (ttsState.isPlaying || ttsState.currentParagraph > 0) {
            var showSpeedSlider by remember { mutableStateOf(false) }
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 60.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // 进度条
                    if (ttsState.totalParagraphs > 0) {
                        LinearProgressIndicator(
                            progress = { (ttsState.currentParagraph + 1).toFloat() / ttsState.totalParagraphs },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                    // 速度滑块
                    if (showSpeedSlider) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                        ) {
                            Text(
                                "%.1fx".format(ttsState.speed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(36.dp)
                            )
                            Slider(
                                value = ttsState.speed,
                                onValueChange = { viewModel.setTtsSpeed(it) },
                                valueRange = 0.5f..3.0f,
                                steps = 9,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // 控制按钮
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.ttsPrevious() }) {
                            Icon(Icons.Filled.SkipPrevious, contentDescription = null)
                        }
                        IconButton(onClick = {
                            if (ttsState.isPlaying) viewModel.pauseTts() else viewModel.resumeTts()
                        }) {
                            Icon(
                                if (ttsState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.ttsNext() }) {
                            Icon(Icons.Filled.SkipNext, contentDescription = null)
                        }
                        IconButton(onClick = { showSpeedSlider = !showSpeedSlider }) {
                            Icon(Icons.Filled.Speed, contentDescription = null)
                        }
                        IconButton(onClick = { viewModel.stopTts() }) {
                            Icon(Icons.Filled.Close, contentDescription = null)
                        }
                    }
                }
            }
        }

        // Snackbar 叠加层
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
    } // MaterialTheme
}

@Composable
private fun rememberCustomFont(fontPath: String): FontFamily? {
    return remember(fontPath) {
        if (fontPath.isNotEmpty() && File(fontPath).exists()) {
            try { FontFamily(Font(File(fontPath))) } catch (_: Exception) { null }
        } else null
    }
}

/**
 * TXT 阅读器 — 连续滚动，无章节切换感
 */
@Composable
private fun TxtReaderContent(
    viewModel: ReaderViewModel,
    state: ReaderState,
    bgColor: Color,
    textColor: Color,
    settingsOpen: Boolean = false,
    ttsParagraphIndex: Int = -1,  // 当前 TTS 朗读的段落索引，-1 表示不在朗读
    ttsHighlightColor: Color = Color.Unspecified,
    ttsCharStart: Int = -1,
    ttsCharEnd: Int = -1
) {
    val chapters = viewModel.getChapters()
    val customFont = rememberCustomFont(state.fontPath)

    if (chapters.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reader_no_content), color = textColor)
        }
        return
    }

    // 已加载的章节数量（初始从当前章节开始，向下追加）
    val loadedCount = remember { mutableIntStateOf(1) }
    val lazyListState = androidx.compose.foundation.lazy.rememberLazyListState()

    // 音量键翻页（参照 Legado ScrollPageDelegate）
    // 滚动一屏高度，保留一行重叠保证连续性；到底切下一章，到顶切上一章末尾
    val viewHeightPx = with(LocalDensity.current) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * density
    }
    // 保留一行重叠 ≈ 字号 × 行距，滚动量 = 屏高 - 重叠
    val overlapPx = with(LocalDensity.current) { state.fontSize * state.lineSpacing * density }
    val pageScrollPx = viewHeightPx - overlapPx

    LaunchedEffect(Unit) {
        ReaderEventBus.events.collect { event ->
            if (!state.volumePageTurn) return@collect
            FoldLogger.i("ReaderVol", "handling $event, pageScrollPx=$pageScrollPx")
            when (event) {
                ReaderEvent.VOLUME_DOWN -> {
                    val bIdx = lazyListState.firstVisibleItemIndex
                    val bOff = lazyListState.firstVisibleItemScrollOffset
                    lazyListState.scroll { scrollBy(pageScrollPx) }
                    val aIdx = lazyListState.firstVisibleItemIndex
                    val aOff = lazyListState.firstVisibleItemScrollOffset
                    if (bIdx == aIdx && bOff == aOff) {
                        val nextIdx = state.currentChapterIndex + 1
                        if (nextIdx < chapters.size) {
                            FoldLogger.d("ReaderVol", "→ chapter $nextIdx")
                            loadedCount.intValue = 1
                            viewModel.updateTxtChapter(nextIdx)
                        }
                    }
                }
                ReaderEvent.VOLUME_UP -> {
                    val bIdx = lazyListState.firstVisibleItemIndex
                    val bOff = lazyListState.firstVisibleItemScrollOffset
                    lazyListState.scroll { scrollBy(-pageScrollPx) }
                    val aIdx = lazyListState.firstVisibleItemIndex
                    val aOff = lazyListState.firstVisibleItemScrollOffset
                    if (bIdx == aIdx && bOff == aOff && bIdx == 0 && bOff == 0) {
                        val prevIdx = state.currentChapterIndex - 1
                        if (prevIdx >= 0) {
                            FoldLogger.d("ReaderVol", "→ chapter $prevIdx")
                            loadedCount.intValue = 1
                            viewModel.updateTxtChapter(prevIdx)
                        }
                    }
                }
            }
        }
    }

    // 章节变化时重置
    LaunchedEffect(state.currentChapterIndex) {
        loadedCount.intValue = 1
        lazyListState.scrollToItem(0)
    }

    val textStyle = TextStyle(
        fontSize = state.fontSize.sp,
        lineHeight = (state.fontSize * state.lineSpacing).sp,
        color = textColor,
        fontFamily = customFont
    )

    val startIdx = state.currentChapterIndex
    val endIdx = (startIdx + loadedCount.intValue).coerceAtMost(chapters.size)

    // 预处理所有章节段落
    val allParagraphs = remember(startIdx, endIdx, state.reSegment, state.chineseConvert, state.wordReplacements) {
        (startIdx until endIdx).map { idx ->
            idx to viewModel.getProcessedChapterParagraphs(idx)
        }
    }

    var swipeStartX by remember { mutableStateOf(0f) }
    var swipeStartY by remember { mutableStateOf(0f) }
    val swipeTag = "ReaderSwipe"

    Column(modifier = Modifier
        .fillMaxSize()
        .background(bgColor)
        .pointerInput(startIdx, chapters.size, settingsOpen) {
            awaitPointerEventScope {
                while (true) {
                    if (settingsOpen) {
                        awaitPointerEvent()
                        continue
                    }
                    val down = awaitFirstDown(requireUnconsumed = false)
                    swipeStartX = down.position.x
                    swipeStartY = down.position.y
                    FoldLogger.d(swipeTag, "DOWN at (${swipeStartX.toInt()}, ${swipeStartY.toInt()}), startIdx=$startIdx, chapters=${chapters.size}")
                    val up = withTimeoutOrNull(500) {
                        var event = awaitPointerEvent()
                        while (event.changes.any { it.pressed }) {
                            event = awaitPointerEvent()
                        }
                        event
                    }
                    if (up != null) {
                        val endX = up.changes.firstOrNull()?.position?.x ?: swipeStartX
                        val endY = up.changes.firstOrNull()?.position?.y ?: swipeStartY
                        val dx = endX - swipeStartX
                        val dy = endY - swipeStartY
                        val absDx = kotlin.math.abs(dx)
                        val absDy = kotlin.math.abs(dy)
                        val horizontalPass = absDx > 150
                        val dominant = absDx > absDy * 1.5
                        FoldLogger.d(swipeTag, "UP at (${endX.toInt()}, ${endY.toInt()}), dx=${dx.toInt()}, dy=${dy.toInt()}, absDx=${absDx.toInt()}, absDy=${absDy.toInt()}, hPass=$horizontalPass, dominant=$dominant, timeout=false")
                        if (horizontalPass && dominant) {
                            if (dx < 0 && startIdx + loadedCount.intValue < chapters.size) {
                                val nextIdx = (startIdx + loadedCount.intValue).coerceAtMost(chapters.size - 1)
                                FoldLogger.d(swipeTag, "LEFT swipe → next chapter $nextIdx")
                                loadedCount.intValue = 1
                                viewModel.updateTxtChapter(nextIdx)
                            } else if (dx > 0 && startIdx > 0) {
                                val prevIdx = (startIdx - 1).coerceAtLeast(0)
                                FoldLogger.d(swipeTag, "RIGHT swipe → prev chapter $prevIdx")
                                loadedCount.intValue = 1
                                viewModel.updateTxtChapter(prevIdx)
                            } else {
                                FoldLogger.d(swipeTag, "Swipe IGNORED: dx=${dx.toInt()}, startIdx=$startIdx, loadedCount=${loadedCount.intValue}, chapters=${chapters.size}")
                            }
                        } else {
                            FoldLogger.d(swipeTag, "Swipe REJECTED: horizontalPass=$horizontalPass, dominant=$dominant")
                        }
                    } else {
                        FoldLogger.d(swipeTag, "TIMEOUT 500ms — 手势超时，未识别为滑动")
                    }
                }
            }
        }
    ) {
        // LazyColumn — 只组合可见段落，滚动丝滑
        // mergeDescendants = true 合并所有子节点为一个无障碍节点，消除 28KB/帧事务
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
                .padding(start = state.marginLeft.dp, end = state.marginRight.dp, top = 8.dp)
                .semantics(mergeDescendants = true) {},
            contentPadding = PaddingValues(
                start = 0.dp, end = 0.dp, top = 0.dp,
                bottom = with(LocalDensity.current) {
                    WindowInsets.navigationBars.getBottom(this).toDp() + 16.dp
                }
            )
        ) {
            for ((chapterIdx, paragraphs) in allParagraphs) {
                // 章节标题
                if (chapterIdx > startIdx) {
                    item(key = "spacer_$chapterIdx") {
                        Spacer(Modifier.height(32.dp))
                    }
                    item(key = "title_$chapterIdx") {
                        Text(
                            text = chapters[chapterIdx].title.ifBlank { stringResource(R.string.reader_untitled_chapter, chapterIdx + 1) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                } else if (chapterIdx == startIdx) {
                    item(key = "title_$chapterIdx") {
                        Text(
                            text = chapters[chapterIdx].title.ifBlank { stringResource(R.string.reader_untitled_chapter, chapterIdx + 1) },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = textColor,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                    }
                }
                // 段落
                val indent = "　　"
                itemsIndexed(paragraphs, key = { pi, _ -> "p_${chapterIdx}_$pi" }) { pi, para ->
                    val isTtsCurrent = ttsParagraphIndex >= 0 &&
                        chapterIdx == state.currentChapterIndex && pi == ttsParagraphIndex
                    val displayText = if (state.reSegment && para.isNotBlank()) "$indent$para" else para
                    Column {
                        if (isTtsCurrent && ttsCharStart >= 0 && ttsCharEnd > ttsCharStart) {
                            val offset = if (state.reSegment) 2 else 0
                            val s = (ttsCharStart + offset).coerceIn(0, displayText.length)
                            val e = (ttsCharEnd + offset).coerceIn(0, displayText.length)
                            val annotated = androidx.compose.ui.text.AnnotatedString.Builder(displayText.length).apply {
                                append(displayText.substring(0, s))
                                pushStyle(androidx.compose.ui.text.SpanStyle(color = Color(0xFFCC0000)))
                                append(displayText.substring(s, e))
                                pop()
                                append(displayText.substring(e))
                            }.toAnnotatedString()
                            Text(text = annotated, style = textStyle)
                        } else if (isTtsCurrent) {
                            Text(text = displayText, style = textStyle.copy(color = Color(0xFFCC0000)))
                        } else {
                            Text(text = displayText, style = textStyle)
                        }
                        // Paragraph gap: same as line height
                        Spacer(Modifier.height((state.fontSize * state.lineSpacing).dp))
                    }
                }
            }
            // 滚到底部时追加下一章
            if (endIdx < chapters.size) {
                item(key = "load_more") {
                    LaunchedEffect(Unit) {
                        loadedCount.intValue++
                    }
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            } else {
                // 已到末尾
                item(key = "end_of_book") {
                    Spacer(Modifier.height(80.dp))
                    Text(
                        text = stringResource(R.string.reader_end_of_book),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.3f),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * EPUB 阅读器 — 连续滚动，滚动到底追加下一章
 */
@Composable
private fun EpubReaderContent(
    viewModel: ReaderViewModel,
    state: ReaderState
) {
    val epubHtml by viewModel.epubHtml.collectAsStateWithLifecycle()
    val textColor = Color(state.readingTheme.textColor)
    val bgColor = Color(state.readingTheme.bgColor)
    val chapters = viewModel.getChapters()

    // 已加载章节计数
    val loadedCount = remember { mutableIntStateOf(1) }
    val loadedHtml = remember { mutableStateOf("") }
    val isLoadingMore = remember { mutableStateOf(false) }
    val isChapterLoading = remember { mutableStateOf(false) }
    val pendingChapter = remember { mutableIntStateOf(-1) }
    val swipeLoading = remember { mutableStateOf(false) } // 防止连续滑动重复触发

    // 加载当前章节
    LaunchedEffect(state.currentChapterIndex) {
        FoldLogger.d("Reader", "chapterSwitch: index=${state.currentChapterIndex}, pending=${pendingChapter.intValue}, loadedHtml=${loadedHtml.value.length}")
        loadedCount.intValue = 1
        isChapterLoading.value = true
        if (pendingChapter.intValue == -1) loadedHtml.value = ""
        viewModel.loadEpubChapter(state.currentChapterIndex)
    }

    // 当 epubHtml 更新时，写入 loadedHtml（Pair 的 Long 保证每次都触发）
    LaunchedEffect(epubHtml) {
        val (html, _) = epubHtml
        if (html.isNotEmpty()) {
            FoldLogger.d("Reader", "epubHtml updated: len=${html.length}, pending=${pendingChapter.intValue}, loadedHtml=${loadedHtml.value.length}")
            if (pendingChapter.intValue >= 0 || loadedHtml.value.isEmpty()) {
                loadedHtml.value = html
            } else {
                val separator = "<hr style='margin:32px 0;border:none;border-top:1px solid #ccc;'>"
                loadedHtml.value = loadedHtml.value + separator + html
            }
            pendingChapter.intValue = -1
            isChapterLoading.value = false
            isLoadingMore.value = false
            swipeLoading.value = false
            FoldLogger.d("Reader", "epubHtml applied: loadedHtml=${loadedHtml.value.length}, swipeLoading reset")
        }
    }

    FoldLogger.d("Reader", "EpubReader render: loadedHtml=${loadedHtml.value.length}, isChapterLoading=${isChapterLoading.value}, isLoading=${state.isLoading}")

    if (isChapterLoading.value || (loadedHtml.value.isEmpty() && state.isLoading)) {
        Box(Modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    if (loadedHtml.value.isEmpty()) {
        FoldLogger.w("Reader", "loadedHtml empty after loading complete, index=${state.currentChapterIndex}")
        Box(Modifier.fillMaxSize().background(bgColor), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.reader_load_failed), color = textColor)
        }
        return
    }

    val fontCss = if (state.fontPath.isNotEmpty() && File(state.fontPath).exists()) {
        "@font-face{font-family:'CustomFont';src:url('file://${state.fontPath}')}body{font-family:'CustomFont',serif!important}"
    } else "body{font-family:serif}"

    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // 音量键翻页：JS 滚动一屏
    LaunchedEffect(Unit) {
        ReaderEventBus.events.collect { event ->
            if (!state.volumePageTurn) return@collect
            FoldLogger.i("ReaderVol", "EpubReaderContent: handling $event")
            val js = when (event) {
                ReaderEvent.VOLUME_UP -> "window.scrollBy({top:-(window.innerHeight-40),behavior:'smooth'})"
                ReaderEvent.VOLUME_DOWN -> "window.scrollBy({top:(window.innerHeight-40),behavior:'smooth'})"
            }
            webViewRef.value?.evaluateJavascript(js, null)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.defaultTextEncodingName = "UTF-8"
                settings.allowFileAccess = true
                webViewRef.value = this

                // JS 接口：滚动到底加载更多 + 左右滑动切换章节
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface
                    fun loadMore() {
                        post {
                            val endIdx = viewModel.state.value.currentChapterIndex + loadedCount.intValue
                            val chaps = viewModel.getChapters()
                            if (!isLoadingMore.value && endIdx < chaps.size) {
                                isLoadingMore.value = true
                                loadedCount.intValue++
                                viewModel.loadEpubChapter(endIdx)
                            }
                        }
                    }
                    @android.webkit.JavascriptInterface
                    fun onSwipe(direction: String) {
                        post {
                            if (swipeLoading.value) {
                                FoldLogger.d("ReaderSwipe", "EPUB swipe IGNORED: already loading")
                                return@post
                            }
                            val cur = viewModel.state.value.currentChapterIndex
                            val chaps = viewModel.getChapters()
                            FoldLogger.d("ReaderSwipe", "EPUB onSwipe: direction=$direction, cur=$cur, loadedCount=${loadedCount.intValue}, chapters=${chaps.size}")
                            when (direction) {
                                "left" -> {
                                    val next = cur + loadedCount.intValue
                                    if (next < chaps.size) {
                                        FoldLogger.d("ReaderSwipe", "EPUB LEFT → chapter $next, calling loadEpubChapter now")
                                        pendingChapter.intValue = next
                                        loadedCount.intValue = 1
                                        swipeLoading.value = true
                                        viewModel.loadEpubChapter(next)
                                        FoldLogger.d("ReaderSwipe", "EPUB LEFT: loadEpubChapter($next) returned from call")
                                    } else {
                                        FoldLogger.d("ReaderSwipe", "EPUB LEFT IGNORED: next=$next >= chapters=${chaps.size}")
                                    }
                                }
                                "right" -> {
                                    if (cur > 0) {
                                        FoldLogger.d("ReaderSwipe", "EPUB RIGHT → chapter ${cur - 1}")
                                        pendingChapter.intValue = cur - 1
                                        loadedCount.intValue = 1
                                        swipeLoading.value = true
                                        viewModel.loadEpubChapter(cur - 1)
                                    } else {
                                        FoldLogger.d("ReaderSwipe", "EPUB RIGHT IGNORED: cur=$cur")
                                    }
                                }
                            }
                        }
                    }
                }, "Android")

                // 页面加载完成后注入滚动检测
                webChromeClient = object : android.webkit.WebChromeClient() {
                    override fun onConsoleMessage(cm: android.webkit.ConsoleMessage?): Boolean {
                        cm?.let { FoldLogger.d("ReaderSwipe", "JS: ${it.message()}") }
                        return true
                    }
                }
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(SCROLL_DETECT_JS, null)
                    }
                }
            }
        },
        update = { webView ->
            val html = """
                <!DOCTYPE html><html><head>
                <meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1.0">
                <style>$fontCss body{font-size:${state.fontSize}px;line-height:${state.lineSpacing};padding-top:16px;padding-bottom:16px;padding-left:${state.marginLeft.toInt()}px;padding-right:${state.marginRight.toInt()}px;color:#${String.format("%06X", 0xFFFFFF and textColor.toArgb())};background:#${String.format("%06X", 0xFFFFFF and bgColor.toArgb())}}img{max-width:100%;height:auto}</style>
                </head><body>${viewModel.applyWordReplacements(loadedHtml.value)}</body></html>
            """.trimIndent()
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            // 重新注入滚动检测
            webView.evaluateJavascript(SCROLL_DETECT_JS, null)
        },
        modifier = Modifier.fillMaxSize()
    )
}

/** 滚动 + 滑动检测 JS */
private const val SCROLL_DETECT_JS = """
(function() {
    // 滚动检测：距底部 300px 时触发 loadMore
    var ticking = false;
    window.addEventListener('scroll', function() {
        if (!ticking) {
            window.requestAnimationFrame(function() {
                var scrollBottom = document.documentElement.scrollHeight - window.innerHeight - window.scrollY;
                if (scrollBottom < 300) {
                    Android.loadMore();
                }
                ticking = false;
            });
            ticking = true;
        }
    }, {passive: true});

    // 滑动检测：左右滑动切换章节（带冷却防抖）
    var startX = 0, startY = 0, swipeCooldown = false;
    document.addEventListener('touchstart', function(e) {
        startX = e.touches[0].clientX;
        startY = e.touches[0].clientY;
    }, {passive: true});
    document.addEventListener('touchend', function(e) {
        if (swipeCooldown) return;
        var dx = e.changedTouches[0].clientX - startX;
        var dy = e.changedTouches[0].clientY - startY;
        var absDx = Math.abs(dx);
        var absDy = Math.abs(dy);
        var hPass = absDx > 80;
        var dominant = absDx > absDy * 1.5;
        console.log('ReaderSwipe JS: dx=' + dx.toFixed(1) + ', dy=' + dy.toFixed(1) + ', hPass=' + hPass + ', dominant=' + dominant);
        if (hPass && dominant) {
            swipeCooldown = true;
            Android.onSwipe(dx < 0 ? 'left' : 'right');
            setTimeout(function() { swipeCooldown = false; }, 800);
        }
    }, {passive: true});
})();
"""

@Composable
private fun PdfReaderContent(
    viewModel: ReaderViewModel,
    state: ReaderState,
    bgColor: Color,
    textColor: Color
) {
    val totalPages = state.totalPages
    if (totalPages <= 0) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val coroutineScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = state.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))) { totalPages }
    val context = LocalContext.current
    val screenWidthPx = context.resources.displayMetrics.widthPixels

    // 音量键翻页：PDF 翻页
    LaunchedEffect(Unit) {
        ReaderEventBus.events.collect { event ->
            if (!state.volumePageTurn) return@collect
            FoldLogger.i("ReaderVol", "PdfReaderContent: handling $event")
            when (event) {
                ReaderEvent.VOLUME_UP -> {
                    if (pagerState.currentPage > 0) {
                        pagerState.animateScrollToPage(pagerState.currentPage - 1)
                    }
                }
                ReaderEvent.VOLUME_DOWN -> {
                    if (pagerState.currentPage < totalPages - 1) {
                        pagerState.animateScrollToPage(pagerState.currentPage + 1)
                    }
                }
            }
        }
    }

    // 滑动翻页时渲染当前页
    LaunchedEffect(pagerState.currentPage) {
        viewModel.renderPdfPage(pagerState.currentPage, screenWidthPx)
        viewModel.updatePage(pagerState.currentPage)
    }

    // 目录跳转/书签加载时驱动 pager
    LaunchedEffect(state.currentPage) {
        val target = state.currentPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
        if (pagerState.currentPage != target) {
            pagerState.animateScrollToPage(target)
        }
    }

    val bitmap by viewModel.pdfPageBitmap.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxSize().background(bgColor)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) { page ->
            Box(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                if (page == pagerState.currentPage) {
                    val currentBitmap = bitmap
                    if (currentBitmap != null) {
                        androidx.compose.foundation.Image(
                            bitmap = currentBitmap.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        CircularProgressIndicator()
                    }
                } else {
                    Text(
                        text = stringResource(R.string.reader_page_indicator, page + 1, totalPages),
                        style = MaterialTheme.typography.headlineMedium,
                        color = textColor.copy(alpha = 0.3f)
                    )
                }
            }
        }

        // 页码指示
        Text(
            text = stringResource(R.string.reader_page_indicator, pagerState.currentPage + 1, totalPages),
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
        )
    }
}

@Composable
private fun ChapterListSheet(chapters: List<Chapter>, currentIndex: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (currentIndex > 0) listState.scrollToItem(currentIndex)
    }
    // 章节列表滚动日志
    var chapterFlingStart by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            chapterFlingStart = System.currentTimeMillis()
            FoldLogger.d("Fold_Scroll", "chapterList scroll START, first=${listState.firstVisibleItemIndex}")
        } else {
            val elapsed = System.currentTimeMillis() - chapterFlingStart
            FoldLogger.d("Fold_Scroll", "chapterList scroll END, first=${listState.firstVisibleItemIndex}, elapsed=${elapsed}ms")
        }
    }
    // 半透明遮罩
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(onClick = onDismiss)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.6f)
                .align(Alignment.BottomCenter)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(16.dp)
        ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reader_chapters), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurface) }
                }
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(chapters, key = { idx, ch -> ch.startIndex }) { index, chapter ->
                        val isSelected = index == currentIndex
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable { onSelect(index); onDismiss() }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title.ifBlank { stringResource(R.string.reader_untitled_chapter, index + 1) },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

@Composable
private fun BookmarkListSheet(
    bookmarks: List<BookmarkEntry>,
    onSelect: (BookmarkEntry) -> Unit,
    onDelete: (BookmarkEntry) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.reader_bookmarks), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close), tint = MaterialTheme.colorScheme.onSurface) }
                }
                if (bookmarks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.reader_bookmark_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(bookmarks) { _, entry ->
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                onClick = { onSelect(entry) },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.chapterTitle.ifBlank { stringResource(R.string.reader_untitled_chapter, entry.chapterIndex + 1) },
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entry.timestamp)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onDelete(entry) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
