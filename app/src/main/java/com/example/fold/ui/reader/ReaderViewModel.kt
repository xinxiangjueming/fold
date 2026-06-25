package com.example.fold.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.fold.util.FoldLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fold.AppContainer
import com.example.fold.data.model.BookmarkEntry
import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import com.example.fold.data.model.ReadingTheme
import com.example.fold.data.reader.EpubReader
import com.example.fold.data.reader.PdfReader
import com.example.fold.data.reader.TxtReader
import com.example.fold.data.reader.ContentProcessor
import com.example.fold.data.reader.TtsManager
import com.example.fold.data.reader.TtsState
import com.example.fold.data.reader.ChineseConverter
import com.example.fold.service.ReaderNotificationService
import com.example.fold.service.TtsEvent
import com.example.fold.service.TtsEventBus
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

private const val TAG = "Fold_VM"

class ReaderViewModel : ViewModel() {

    private val context: Context = AppContainer.appContext
    private val txtReader: TxtReader = AppContainer.getTxtReader()
    private val epubReader: EpubReader = AppContainer.getEpubReader()
    private val pdfReader: PdfReader = AppContainer.getPdfReader()
    private val ttsManager: TtsManager = TtsManager(context)
    val ttsState: StateFlow<TtsState> = ttsManager.state

    private val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private val _epubHtml = MutableStateFlow("" to 0L)
    val epubHtml: StateFlow<Pair<String, Long>> = _epubHtml.asStateFlow()

    private val _pdfPageBitmap = MutableStateFlow<Bitmap?>(null)
    val pdfPageBitmap: StateFlow<Bitmap?> = _pdfPageBitmap.asStateFlow()

    // 书签列表
    private val _bookmarks = MutableStateFlow<List<BookmarkEntry>>(emptyList())
    val bookmarks: StateFlow<List<BookmarkEntry>> = _bookmarks.asStateFlow()

    private var currentFileType: String = ""
    private var currentFilePath: String = ""
    private var readerJob: Job? = null

    // 用户设置持久化
    private var userFontSize = prefs.getFloat(KEY_FONT_SIZE, 16f)
    private var userLineSpacing = prefs.getFloat(KEY_LINE_SPACING, 1.5f)
    private var userFontPath = prefs.getString(KEY_FONT_PATH, "") ?: ""
    private var userThemeName = run {
        val saved = prefs.getString(KEY_READING_THEME, null)
        if (saved != null) {
            saved
        } else {
            // 首次使用：跟随系统深浅色模式
            val nightModeFlags = context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) ReadingTheme.DARK.label
            else ReadingTheme.LIGHT.label
        }
    }
    private var userMarginLeft = prefs.getFloat(KEY_MARGIN_LEFT, 20f)
    private var userMarginRight = prefs.getFloat(KEY_MARGIN_RIGHT, 20f)
    private var userReSegment = prefs.getBoolean(KEY_RE_SEGMENT, false)
    private var userChineseConvert = prefs.getInt(KEY_CHINESE_CONVERT, 0)

    init {
        FoldLogger.i(TAG, "init: fontPath='$userFontPath', fontSize=$userFontSize, lineSpacing=$userLineSpacing, theme=$userThemeName")
        if (userFontPath.isNotEmpty() && !File(userFontPath).exists()) {
            FoldLogger.w(TAG, "init: font file missing, resetting: $userFontPath")
            userFontPath = ""
        }
        _state.update {
            it.copy(
                fontSize = userFontSize,
                lineSpacing = userLineSpacing,
                fontPath = userFontPath,
                readingTheme = ReadingTheme.fromName(userThemeName),
                marginLeft = userMarginLeft,
                marginRight = userMarginRight,
                reSegment = userReSegment,
                chineseConvert = userChineseConvert
            )
        }
        // 监听通知栏 TTS 控制事件
        viewModelScope.launch {
            TtsEventBus.events.collect { event ->
                FoldLogger.d(TAG, "TtsEvent: $event")
                when (event) {
                    TtsEvent.PLAY, TtsEvent.RESUME -> resumeTts()
                    TtsEvent.PAUSE -> pauseTts()
                    TtsEvent.STOP -> stopTts()
                    TtsEvent.NEXT -> ttsNext()
                    TtsEvent.PREV -> ttsPrevious()
                }
            }
        }
        // TTS 状态变化 → 更新通知
        viewModelScope.launch {
            ttsState.collect { tts ->
                if (tts.isPlaying || tts.currentParagraph > 0) {
                    val paragraphs = ttsParagraphs
                    val currentText = paragraphs.getOrNull(tts.currentParagraph) ?: ""
                    val title = state.value.fileName.ifEmpty { "朗读中" }
                    ReaderNotificationService.updateTts(
                        title = title,
                        paragraph = currentText,
                        current = tts.currentParagraph,
                        total = tts.totalParagraphs,
                        isPlaying = tts.isPlaying
                    )
                }
            }
        }
        // 阅读进度变化 → 更新通知
        viewModelScope.launch {
            state.collect { s ->
                if (s.chapters.isNotEmpty()) {
                    val chapter = s.chapters.getOrNull(s.currentChapterIndex)
                    val percent = if (s.chapters.isNotEmpty()) ((s.currentChapterIndex + 1) * 100 / s.chapters.size) else 0
                    ReaderNotificationService.updateProgress(
                        bookName = s.fileName,
                        chapterName = chapter?.title ?: "",
                        currentChapter = s.currentChapterIndex,
                        totalChapters = s.chapters.size,
                        percent = percent
                    )
                }
            }
        }
    }

    fun openFile(filePath: String) {
        val ext = File(filePath).extension.lowercase()
        FoldLogger.i(TAG, "openFile: filePath=$filePath, ext=$ext")
        currentFileType = ext
        currentFilePath = filePath
        readerJob?.cancel()

        // 加载该书的替换词
        val replacements = loadWordReplacements()

        // 恢复阅读进度（章节 + 滚动位置）
        val savedChapter = prefs.getInt("progress_chapter_$filePath", 0)
        val savedScroll = prefs.getInt("progress_scroll_$filePath", 0)
        FoldLogger.d(TAG, "openFile: savedChapter=$savedChapter, savedScroll=$savedScroll")

        readerJob = viewModelScope.launch {
            when (ext) {
                "txt" -> {
                    txtReader.open(filePath)
                    txtReader.state.collect { readerState ->
                        val cur = _state.value
                        val wasNew = cur.filePath != filePath
                        _state.value = readerState.copy(
                            fileType = "txt",
                            fontSize = cur.fontSize,
                            lineSpacing = cur.lineSpacing,
                            fontPath = cur.fontPath,
                            readingTheme = cur.readingTheme,
                            marginLeft = cur.marginLeft,
                            marginRight = cur.marginRight,
                            wordReplacements = cur.wordReplacements,
                            reSegment = cur.reSegment,
                            chineseConvert = cur.chineseConvert,
                            currentChapterIndex = if (wasNew) savedChapter else cur.currentChapterIndex,
                            currentScrollOffset = if (wasNew) savedScroll else cur.currentScrollOffset
                        )
                        if (wasNew) txtReader.preloadNearbyChapters(savedChapter)
                    }
                }
                "epub" -> {
                    epubReader.open(filePath)
                    epubReader.state.collect { readerState ->
                        val cur = _state.value
                        _state.value = readerState.copy(
                            fileType = "epub",
                            fontSize = cur.fontSize,
                            lineSpacing = cur.lineSpacing,
                            fontPath = cur.fontPath,
                            readingTheme = cur.readingTheme,
                            marginLeft = cur.marginLeft,
                            marginRight = cur.marginRight,
                            wordReplacements = cur.wordReplacements,
                            reSegment = cur.reSegment,
                            chineseConvert = cur.chineseConvert,
                            currentChapterIndex = if (cur.filePath != filePath) savedChapter else cur.currentChapterIndex
                        )
                    }
                }
                "pdf" -> {
                    pdfReader.open(filePath)
                    pdfReader.state.collect { readerState ->
                        val cur = _state.value
                        _state.value = readerState.copy(
                            fileType = "pdf",
                            fontSize = cur.fontSize,
                            lineSpacing = cur.lineSpacing,
                            fontPath = cur.fontPath,
                            readingTheme = cur.readingTheme,
                            marginLeft = cur.marginLeft,
                            marginRight = cur.marginRight,
                            wordReplacements = cur.wordReplacements,
                            reSegment = cur.reSegment,
                            chineseConvert = cur.chineseConvert,
                            currentPage = if (cur.filePath != filePath) savedChapter else cur.currentPage
                        )
                    }
                }
            }
        }
    }

    fun getTxtChapterText(index: Int): String {
        FoldLogger.d(TAG, "getTxtChapterText: index=$index")
        return txtReader.getChapterText(index)
    }

    fun updateTxtChapter(index: Int) {
        FoldLogger.d(TAG, "updateTxtChapter: index=$index")
        txtReader.updateChapterIndex(index)
        _state.update { it.copy(currentChapterIndex = index) }
        saveProgress(index)
        // 预加载附近章节
        txtReader.preloadNearbyChapters(index)
    }

    fun updateTxtScrollOffset(offset: Int) {
        FoldLogger.v(TAG, "updateTxtScrollOffset: offset=$offset")
        txtReader.updateScrollOffset(offset)
    }

    fun loadEpubChapter(index: Int) {
        FoldLogger.i(TAG, "loadEpubChapter: index=$index, launching coroutine")
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                epubReader.updateChapterIndex(index)
                FoldLogger.d(TAG, "loadEpubChapter: updateChapterIndex done in ${System.currentTimeMillis()-t0}ms")
                val t1 = System.currentTimeMillis()
                val html = epubReader.getChapterHtml(index)
                FoldLogger.i(TAG, "loadEpubChapter: getChapterHtml len=${html.length} in ${System.currentTimeMillis()-t1}ms")
                _epubHtml.value = html to System.nanoTime()
                _state.update { it.copy(currentChapterIndex = index) }
                saveProgress(index)
                FoldLogger.d(TAG, "loadEpubChapter: DONE total=${System.currentTimeMillis()-t0}ms, index=$index")
            } catch (e: Exception) {
                FoldLogger.e(TAG, "loadEpubChapter: FAILED index=$index", e)
                _epubHtml.value = "" to 0L
            }
        }
    }

    fun renderPdfPage(pageIndex: Int, width: Int) {
        FoldLogger.d(TAG, "renderPdfPage: pageIndex=$pageIndex, width=$width")
        viewModelScope.launch {
            val oldBitmap = _pdfPageBitmap.value
            val bitmap = pdfReader.renderPage(pageIndex, width)
            _pdfPageBitmap.value = bitmap
            oldBitmap?.recycle()
            pdfReader.updatePage(pageIndex)
            _state.update { it.copy(currentPage = pageIndex) }
            saveProgress(pageIndex)
            FoldLogger.d(TAG, "renderPdfPage: done, bitmap=${bitmap != null}")
        }
    }

    fun updatePage(page: Int) {
        FoldLogger.d(TAG, "updatePage: page=$page")
        pdfReader.updatePage(page)
    }

    fun getChapters(): List<Chapter> {
        val result = when (currentFileType) {
            "txt" -> txtReader.getChapters()
            "epub" -> epubReader.getChapters()
            "pdf" -> pdfReader.getChapters()
            else -> emptyList()
        }
        FoldLogger.d(TAG, "getChapters: fileType=$currentFileType, count=${result.size}")
        return result
    }

    fun seekToChapter(index: Int) {
        FoldLogger.i(TAG, "seekToChapter: index=$index, fileType=$currentFileType")
        when (currentFileType) {
            "txt" -> {
                txtReader.updateChapterIndex(index)
                _state.update { it.copy(currentChapterIndex = index, currentScrollOffset = 0) }
            }
            "epub" -> {
                _state.update { it.copy(currentChapterIndex = index) }
                loadEpubChapter(index)
            }
            "pdf" -> {
                pdfReader.updatePage(index)
                _state.update { it.copy(currentPage = index, currentChapterIndex = index) }
            }
        }
        saveProgress(index)
    }

    fun updateFontSize(size: Float) {
        FoldLogger.d(TAG, "updateFontSize: oldSize=$userFontSize, newSize=$size")
        userFontSize = size
        _state.update { it.copy(fontSize = size) }
        prefs.edit().putFloat(KEY_FONT_SIZE, size).apply()
    }

    fun updateLineSpacing(spacing: Float) {
        FoldLogger.d(TAG, "updateLineSpacing: oldSpacing=$userLineSpacing, newSpacing=$spacing")
        userLineSpacing = spacing
        _state.update { it.copy(lineSpacing = spacing) }
        prefs.edit().putFloat(KEY_LINE_SPACING, spacing).apply()
    }

    fun updateEncoding(encoding: String) {
        FoldLogger.i(TAG, "updateEncoding: encoding=$encoding, fileType=$currentFileType")
        if (currentFileType == "txt") viewModelScope.launch { txtReader.open(_state.value.filePath, encoding) }
    }

    fun importFont(uri: Uri) {
        FoldLogger.i(TAG, "importFont: uri=$uri")
        viewModelScope.launch {
            try {
                val input = context.contentResolver.openInputStream(uri) ?: run {
                    FoldLogger.w(TAG, "importFont: unable to open input stream")
                    return@launch
                }
                // 从 URI 提取原始文件名
                val originalName = getFileNameFromUri(uri) ?: "custom_font.ttf"
                val dir = File(context.filesDir, "fonts"); if (!dir.exists()) dir.mkdirs()
                val dest = File(dir, originalName)
                FileOutputStream(dest).use { input.copyTo(it) }; input.close()
                FoldLogger.i(TAG, "importFont: saved to ${dest.absolutePath}, size=${dest.length()}, originalName=$originalName")
                userFontPath = dest.absolutePath
                _state.update { it.copy(fontPath = dest.absolutePath) }
                prefs.edit().putString(KEY_FONT_PATH, dest.absolutePath).apply()
            } catch (e: Exception) {
                FoldLogger.e(TAG, "importFont: failed", e)
                e.printStackTrace()
            }
        }
    }

    private fun getFileNameFromUri(uri: android.net.Uri): String? {
        // 尝试从 ContentResolver 获取文件名
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {}
        // fallback: 从 URI path 提取
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun resetFont() {
        FoldLogger.i(TAG, "resetFont")
        userFontPath = ""
        _state.update { it.copy(fontPath = "") }
        prefs.edit().remove(KEY_FONT_PATH).apply()
    }

    // ===== 阅读主题 =====

    fun updateReadingTheme(theme: ReadingTheme) {
        FoldLogger.d(TAG, "updateReadingTheme: oldTheme=${_state.value.readingTheme}, newTheme=$theme")
        _state.update { it.copy(readingTheme = theme) }
        prefs.edit().putString(KEY_READING_THEME, theme.label).apply()
    }

    // ===== 边距 =====

    fun updateMargins(left: Float, right: Float) {
        FoldLogger.d(TAG, "updateMargins: left=$left, right=$right")
        _state.update { it.copy(marginLeft = left, marginRight = right) }
        prefs.edit().putFloat(KEY_MARGIN_LEFT, left).putFloat(KEY_MARGIN_RIGHT, right).apply()
    }

    // ===== 替换词 =====

    fun addWordReplacement(original: String, replacement: String) {
        if (original.isBlank()) return
        FoldLogger.d(TAG, "addWordReplacement: original='$original', replacement='$replacement'")
        val map = _state.value.wordReplacements.toMutableMap()
        map[original] = replacement
        _state.update { it.copy(wordReplacements = map) }
        saveWordReplacements(map)
    }

    fun removeWordReplacement(original: String) {
        FoldLogger.d(TAG, "removeWordReplacement: original='$original'")
        val map = _state.value.wordReplacements.toMutableMap()
        map.remove(original)
        _state.update { it.copy(wordReplacements = map) }
        saveWordReplacements(map)
    }

    private fun saveWordReplacements(map: Map<String, String>) {
        if (currentFilePath.isEmpty()) return
        val serialized = map.entries.joinToString("||") { "${it.key}::${it.value}" }
        prefs.edit().putString("word_repl_$currentFilePath", serialized).apply()
    }

    private fun loadWordReplacements(): Map<String, String> {
        if (currentFilePath.isEmpty()) return emptyMap()
        val raw = prefs.getString("word_repl_$currentFilePath", null) ?: return emptyMap()
        return raw.split("||").mapNotNull { part ->
            val idx = part.indexOf("::")
            if (idx > 0) part.substring(0, idx) to part.substring(idx + 2) else null
        }.toMap()
    }

    /** 对文本应用替换词 */
    fun applyWordReplacements(text: String): String {
        FoldLogger.v(TAG, "applyWordReplacements: textLen=${text.length}, replacements=${_state.value.wordReplacements.size}")
        var result = text
        for ((original, replacement) in _state.value.wordReplacements) {
            result = result.replace(original, replacement)
        }
        return result
    }

    // ===== 重新分段 =====

    /** 切换重新分段开关 */
    fun toggleReSegment() {
        val newValue = !_state.value.reSegment
        FoldLogger.d(TAG, "toggleReSegment: reSegment=$newValue")
        _state.update { it.copy(reSegment = newValue) }
        prefs.edit().putBoolean(KEY_RE_SEGMENT, newValue).apply()
    }

    // ===== 繁简转换 =====

    /** 循环切换繁简转换：关→繁→简→关 */
    fun cycleChineseConvert() {
        val next = (_state.value.chineseConvert + 1) % 3
        FoldLogger.d(TAG, "cycleChineseConvert: $next")
        _state.update { it.copy(chineseConvert = next) }
        prefs.edit().putInt(KEY_CHINESE_CONVERT, next).apply()
    }

    /** 获取章节文本：如果开启重新分段则返回段落列表，否则返回单元素列表 */
    fun getProcessedChapterParagraphs(index: Int): List<String> {
        val raw = getTxtChapterText(index)
        val replaced = applyWordReplacements(raw)
        // 繁简转换
        val mode = _state.value.chineseConvert
        val converted = when (mode) {
            1 -> {
                val result = ChineseConverter.toTraditional(replaced)
                FoldLogger.d(TAG, "chineseConvert: toTraditional, inLen=${replaced.length}, outLen=${result.length}, changed=${result != replaced}")
                result
            }
            2 -> {
                val result = ChineseConverter.toSimplified(replaced)
                FoldLogger.d(TAG, "chineseConvert: toSimplified, inLen=${replaced.length}, outLen=${result.length}, changed=${result != replaced}")
                result
            }
            else -> replaced
        }
        return if (_state.value.reSegment) {
            ContentProcessor.reSegment(converted)
        } else {
            listOf(converted)
        }
    }

    // ===== TTS 朗读 =====

    /** 缓存当前 TTS 段落列表，供通知栏引用 */
    private var ttsParagraphs: List<String> = emptyList()

    fun startTts() {
        val idx = _state.value.currentChapterIndex
        val paragraphs = getProcessedChapterParagraphs(idx)
        ttsParagraphs = paragraphs
        FoldLogger.d(TAG, "startTts: chapter=$idx, paragraphs=${paragraphs.size}")
        ttsManager.startSpeaking(paragraphs)
        ReaderNotificationService.start(context)
    }

    fun pauseTts() = ttsManager.pause()
    fun resumeTts() = ttsManager.resume()
    fun stopTts() {
        ttsManager.stop()
        ttsParagraphs = emptyList()
        ReaderNotificationService.stop(context)
    }

    fun ttsNext() = ttsManager.skipToNext()
    fun ttsPrevious() = ttsManager.skipToPrevious()
    fun setTtsSpeed(speed: Float) = ttsManager.setSpeed(speed)

    fun saveBookmark() {
        val s = _state.value
        if (s.filePath.isEmpty()) return
        val pos = when (s.fileType) { "pdf" -> s.currentPage; else -> s.currentChapterIndex }
        val chapterTitle = getChapters().getOrNull(pos)?.title ?: ""
        FoldLogger.i(TAG, "saveBookmark: pos=$pos, title='$chapterTitle'")
        val entry = BookmarkEntry(
            chapterIndex = pos,
            chapterTitle = chapterTitle,
            scrollOffset = s.currentScrollOffset,
            timestamp = System.currentTimeMillis()
        )
        val list = _bookmarks.value.toMutableList()
        list.add(0, entry)
        // 最多保存 50 个
        if (list.size > 50) list.removeAt(list.lastIndex)
        _bookmarks.value = list
        saveBookmarksToPrefs(s.filePath, list)
    }

    fun loadBookmarks() {
        val s = _state.value
        if (s.filePath.isEmpty()) return
        FoldLogger.d(TAG, "loadBookmarks: filePath=${s.filePath}")
        val saved = prefs.getString("bm_list_${s.filePath}", null)
        _bookmarks.value = if (saved != null) parseBookmarks(saved) else emptyList()
        FoldLogger.d(TAG, "loadBookmarks: loaded ${_bookmarks.value.size} bookmarks")
    }

    fun jumpToBookmark(entry: BookmarkEntry) {
        FoldLogger.i(TAG, "jumpToBookmark: chapter=${entry.chapterIndex}, title='${entry.chapterTitle}', scroll=${entry.scrollOffset}")
        seekToChapter(entry.chapterIndex)
        // 恢复滚动位置（写入 SharedPreferences 供 UI 读取）
        _state.update { it.copy(currentScrollOffset = entry.scrollOffset) }
        if (currentFilePath.isNotEmpty()) {
            prefs.edit().putInt("progress_scroll_$currentFilePath", entry.scrollOffset).apply()
        }
    }

    fun deleteBookmark(entry: BookmarkEntry) {
        FoldLogger.i(TAG, "deleteBookmark: chapter=${entry.chapterIndex}, title='${entry.chapterTitle}'")
        val list = _bookmarks.value.toMutableList()
        list.remove(entry)
        _bookmarks.value = list
        val s = _state.value
        if (s.filePath.isNotEmpty()) saveBookmarksToPrefs(s.filePath, list)
    }

    private fun saveBookmarksToPrefs(filePath: String, list: List<BookmarkEntry>) {
        val serialized = list.joinToString("|") {
            "${it.chapterIndex}:${it.chapterTitle.replace(":", "\\:").replace("|", "\\|")}:${it.scrollOffset}:${it.timestamp}"
        }
        prefs.edit().putString("bm_list_$filePath", serialized).apply()
    }

    private fun parseBookmarks(raw: String): List<BookmarkEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split("|").mapNotNull { part ->
            val pieces = part.split(":")
            if (pieces.size >= 4) {
                BookmarkEntry(
                    chapterIndex = pieces[0].toIntOrNull() ?: return@mapNotNull null,
                    chapterTitle = pieces[1].replace("\\:", ":").replace("\\|", "|"),
                    scrollOffset = pieces[2].toIntOrNull() ?: 0,
                    timestamp = pieces[3].toLongOrNull() ?: 0L
                )
            } else null
        }
    }

    /** 自动保存阅读进度（章节 + 滚动位置） */
    private fun saveProgress(chapterIndex: Int, scrollOffset: Int = 0) {
        if (currentFilePath.isNotEmpty()) {
            prefs.edit()
                .putInt("progress_chapter_$currentFilePath", chapterIndex)
                .putInt("progress_scroll_$currentFilePath", scrollOffset)
                .apply()
        }
    }

    /** 保存滚动位置（由 UI 层调用） */
    fun saveScrollPosition(offset: Int) {
        FoldLogger.v(TAG, "saveScrollPosition: filePath=$currentFilePath, offset=$offset")
        if (currentFilePath.isNotEmpty()) {
            prefs.edit().putInt("progress_scroll_$currentFilePath", offset).apply()
        }
    }

    /** 获取保存的滚动位置 */
    fun getSavedScrollOffset(): Int {
        val offset = prefs.getInt("progress_scroll_$currentFilePath", 0)
        FoldLogger.v(TAG, "getSavedScrollOffset: filePath=$currentFilePath, offset=$offset")
        return offset
    }

    override fun onCleared() {
        FoldLogger.i(TAG, "onCleared")
        super.onCleared()
        readerJob?.cancel()
        ttsManager.release()
        _pdfPageBitmap.value?.recycle()
        _pdfPageBitmap.value = null
        AppContainer.releaseReaders()
    }

    companion object {
        private const val KEY_FONT_PATH = "custom_font_path"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_LINE_SPACING = "line_spacing"
        private const val KEY_READING_THEME = "reading_theme"
        private const val KEY_MARGIN_LEFT = "margin_left"
        private const val KEY_MARGIN_RIGHT = "margin_right"
        private const val KEY_RE_SEGMENT = "re_segment"
        private const val KEY_CHINESE_CONVERT = "chinese_convert"
    }
}
