package com.example.fold.data.reader

import com.example.fold.util.FoldLogger
import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import com.example.fold.util.CharsetDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

private const val TAG = "Fold_Txt"
private const val MAX_FILE_SIZE = 50L * 1024 * 1024
private const val CACHE_SIZE = 5 // LRU cache: current + prev + next*3

class TxtReader : ContentReader {

    private val _state = MutableStateFlow(ReaderState())
    override val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var filePath: String = ""
    private var encoding: String = "UTF-8"
    private var chapters: List<Chapter> = emptyList()

    // 章节内容 LRU 缓存，避免重复读盘
    private val chapterCache = object : LinkedHashMap<Int, String>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>): Boolean {
            return size > CACHE_SIZE
        }
    }

    override suspend fun open(filePath: String, encoding: String?) = withContext(Dispatchers.IO) {
        try {
            FoldLogger.i(TAG, "open: filePath=$filePath, forcedEncoding=$encoding")
            _state.value = _state.value.copy(isLoading = true, error = null, filePath = filePath, fileName = File(filePath).name)

            val file = File(filePath)
            if (!file.exists()) {
                FoldLogger.e(TAG, "open: file not found: $filePath")
                _state.value = _state.value.copy(isLoading = false, error = "文件不存在: ${file.name}")
                return@withContext
            }
            val fileSize = file.length()
            FoldLogger.d(TAG, "open: fileSize=$fileSize bytes")
            if (fileSize > MAX_FILE_SIZE) {
                _state.value = _state.value.copy(isLoading = false, error = "File too large (${fileSize / 1024 / 1024}MB, max 50MB)")
                return@withContext
            }
            val detectedEncoding = encoding ?: CharsetDetector.detect(file)
            FoldLogger.i(TAG, "open: detectedEncoding=$detectedEncoding")
            this@TxtReader.filePath = filePath
            this@TxtReader.encoding = detectedEncoding

            chapters = parseChapters(file, detectedEncoding)
            FoldLogger.i(TAG, "open: success, chapters=${chapters.size}, encoding=$detectedEncoding")
            _state.value = _state.value.copy(
                isLoading = false,
                chapters = chapters,
                currentChapterIndex = 0,
                currentScrollOffset = 0,
                encoding = detectedEncoding
            )
        } catch (e: Exception) {
            FoldLogger.e(TAG, "open: failed", e)
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    override suspend fun seekTo(position: Long) {
        FoldLogger.d(TAG, "seekTo: position=$position")
        val chapterIndex = chapters.indexOfFirst { it.startIndex <= position && it.endIndex > position }
        if (chapterIndex >= 0) {
            FoldLogger.d(TAG, "seekTo: found chapterIndex=$chapterIndex")
            _state.value = _state.value.copy(currentChapterIndex = chapterIndex, currentScrollOffset = 0)
        } else {
            FoldLogger.w(TAG, "seekTo: no chapter found for position=$position")
        }
    }

    override fun getChapters(): List<Chapter> = chapters

    override fun close() {
        FoldLogger.i(TAG, "close")
        filePath = ""
        chapters = emptyList()
        chapterCache.clear()
    }

    fun getChapterText(index: Int): String {
        if (chapters.isEmpty()) return ""
        if (index !in chapters.indices) {
            FoldLogger.w(TAG, "getChapterText: index=$index out of range [0..${chapters.size})")
            return ""
        }
        // 命中缓存
        chapterCache[index]?.let { return it }
        val text = readChapterFromDisk(index)
        chapterCache[index] = text
        return text
    }

    /** 预加载当前章节附近的章节到缓存 */
    fun preloadNearbyChapters(currentIndex: Int) {
        val toLoad = listOf(currentIndex - 1, currentIndex + 1, currentIndex + 2)
        for (idx in toLoad) {
            if (idx in chapters.indices && idx !in chapterCache) {
                FoldLogger.d(TAG, "preload: caching chapter $idx")
                chapterCache[idx] = readChapterFromDisk(idx)
            }
        }
    }

    private fun readChapterFromDisk(index: Int): String {
        val chapter = chapters[index]
        return try {
            val file = File(filePath)
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(chapter.startIndex.toLong())
                val bytes = ByteArray((chapter.endIndex - chapter.startIndex).coerceAtMost(1024 * 1024))
                raf.readFully(bytes)
                String(bytes, java.nio.charset.Charset.forName(encoding))
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "readChapterFromDisk: failed index=$index", e)
            ""
        }
    }

    fun getFullText(): String {
        if (filePath.isEmpty()) return ""
        FoldLogger.d(TAG, "getFullText: filePath=$filePath, encoding=$encoding")
        return try {
            java.io.RandomAccessFile(File(filePath), "r").use { raf ->
                val fileSize = raf.length()
                FoldLogger.d(TAG, "getFullText: fileSize=$fileSize bytes")
                val bytes = ByteArray(fileSize.toInt())
                raf.readFully(bytes)
                val text = String(bytes, java.nio.charset.Charset.forName(encoding))
                FoldLogger.d(TAG, "getFullText: success, textLen=${text.length}")
                text
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "getFullText: failed", e)
            ""
        }
    }

    fun updateScrollOffset(offset: Int) {
        FoldLogger.v(TAG, "updateScrollOffset: offset=$offset")
        _state.value = _state.value.copy(currentScrollOffset = offset)
    }

    fun updateChapterIndex(index: Int) {
        FoldLogger.d(TAG, "updateChapterIndex: $index")
        _state.value = _state.value.copy(currentChapterIndex = index, currentScrollOffset = 0)
    }

    private fun parseChapters(file: File, charset: String): List<Chapter> {
        val fileSize = file.length().toInt()
        val charsetObj = java.nio.charset.Charset.forName(charset)
        val patterns = listOf(
            Regex("^第[零一二三四五六七八九十百千万\\d]+[章节回卷集部篇]"),
            Regex("^Chapter\\s+\\d+", RegexOption.IGNORE_CASE),
            Regex("^\\d+[\\.、]\\s*\\S+"),
            Regex("^【[^】]+】"),
            Regex("^={3,}\\s*$")
        )

        val matchOffsets = Array(patterns.size) { mutableListOf<Pair<Int, String>>() }
        var pos = 0
        java.io.BufferedReader(java.io.InputStreamReader(java.io.FileInputStream(file), charsetObj)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                for ((i, pattern) in patterns.withIndex()) {
                    if (pattern.containsMatchIn(l)) {
                        // 提取章节标题：取匹配行的前 50 个字符作为标题
                        val title = l.trim().take(50)
                        matchOffsets[i].add(pos to title)
                    }
                }
                pos += l.toByteArray(charsetObj).size + 1
            }
        }

        var bestPattern = -1
        var bestCount = 0
        for (i in matchOffsets.indices) {
            val count = matchOffsets[i].size
            FoldLogger.d(TAG, "parseChapters: pattern[$i] '${patterns[i].pattern}' matched $count")
            if (count in 3..500 && count > bestCount) {
                bestPattern = i; bestCount = count
            }
        }

        if (bestPattern >= 0) {
            val offsets = matchOffsets[bestPattern]
            FoldLogger.i(TAG, "parseChapters: using pattern[$bestPattern], matches=$bestCount")
            val result = mutableListOf<Chapter>()
            for (j in offsets.indices) {
                val (start, title) = offsets[j]
                val end = if (j + 1 < offsets.size) offsets[j + 1].first else fileSize
                result.add(Chapter(title = title, startIndex = start, endIndex = end))
            }
            if (offsets[0].first > 0) {
                result.add(0, Chapter(title = "", startIndex = 0, endIndex = offsets[0].first))
            }
            return result
        }

        val chunkSize = 100000
        FoldLogger.i(TAG, "parseChapters: no pattern matched, using fixed chunkSize=$chunkSize")
        val result = mutableListOf<Chapter>()
        var offset = 0
        var index = 1
        while (offset < fileSize) {
            val end = minOf(offset + chunkSize, fileSize)
            result.add(Chapter(title = "Part $index", startIndex = offset, endIndex = end))
            offset = end
            index++
        }
        FoldLogger.i(TAG, "parseChapters: generated ${result.size} fixed-size chapters")
        return result
    }
}
