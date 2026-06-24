package com.example.fold.data.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.example.fold.util.FoldLogger
import com.example.fold.AppContainer
import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "Fold_Pdf"

/**
 * PDF 阅读器
 * 使用 Android 内置 PdfRenderer，轻量但不支持文字选择
 */
class PdfReader : ContentReader {

    private val context: Context = AppContainer.appContext

    private val _state = MutableStateFlow(ReaderState())
    override val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var renderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var chapters: List<Chapter> = emptyList()

    override suspend fun open(filePath: String, encoding: String?) = withContext(Dispatchers.IO) {
        try {
            FoldLogger.i(TAG, "open: filePath=$filePath")
            close()
            _state.value = _state.value.copy(isLoading = true, error = null, filePath = filePath, fileName = File(filePath).name)

            val file = File(filePath)
            if (!file.exists()) {
                FoldLogger.e(TAG, "open: file not found: $filePath")
                _state.value = _state.value.copy(isLoading = false, error = "文件不存在: ${file.name}")
                return@withContext
            }
            FoldLogger.d(TAG, "open: fileSize=${file.length()} bytes")
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor!!)

            val pageCount = renderer!!.pageCount
            FoldLogger.i(TAG, "open: pageCount=$pageCount")

            // PDF 按页作为章节
            chapters = (0 until pageCount).map { i ->
                Chapter(
                    title = "Page ${i + 1}",
                    startIndex = i,
                    endIndex = i + 1
                )
            }

            FoldLogger.i(TAG, "open: success, pageCount=$pageCount")
            _state.value = _state.value.copy(
                isLoading = false,
                chapters = chapters,
                currentChapterIndex = 0,
                currentPage = 0,
                totalPages = pageCount
            )
        } catch (e: Exception) {
            FoldLogger.e(TAG, "open: failed", e)
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    suspend fun renderPage(pageIndex: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        val r = renderer ?: run { FoldLogger.w(TAG, "renderPage: renderer is null"); return@withContext null }
        if (pageIndex !in 0 until r.pageCount) {
            FoldLogger.w(TAG, "renderPage: pageIndex=$pageIndex out of range [0..${r.pageCount})")
            return@withContext null
        }

        try {
            val t0 = System.currentTimeMillis()
            val page = r.openPage(pageIndex)
            val ratio = width.toFloat() / page.width
            val height = (page.height * ratio).toInt()
            val inSampleSize = calculateInSampleSize(page.width, page.height, width)
            val sampledWidth = page.width / inSampleSize
            val sampledHeight = page.height / inSampleSize
            FoldLogger.d(TAG, "renderPage: pageIndex=$pageIndex, srcSize=${page.width}x${page.height}, sampledSize=${sampledWidth}x${sampledHeight}, inSampleSize=$inSampleSize")
            val bitmap = Bitmap.createBitmap(sampledWidth, sampledHeight, Bitmap.Config.ARGB_8888)
            val matrix = android.graphics.Matrix()
            matrix.setScale(1f / inSampleSize, 1f / inSampleSize)
            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            FoldLogger.d(TAG, "renderPage: success, took ${System.currentTimeMillis() - t0}ms")
            bitmap
        } catch (e: Exception) {
            FoldLogger.e(TAG, "renderPage: failed, pageIndex=$pageIndex", e)
            null
        }
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int): Int {
        var inSampleSize = 1
        if (srcWidth > reqWidth * 2) {
            val halfWidth = srcWidth / 2
            while (halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize.coerceAtMost(4)
    }

    override suspend fun seekTo(position: Long) {
        FoldLogger.d(TAG, "seekTo: position=$position")
        val index = position.toInt()
        if (index in chapters.indices) {
            _state.value = _state.value.copy(currentChapterIndex = index, currentPage = index, currentScrollOffset = 0)
        }
    }

    override fun getChapters(): List<Chapter> {
        FoldLogger.d(TAG, "getChapters: count=${chapters.size}")
        return chapters
    }

    fun updatePage(page: Int) {
        val r = renderer ?: return
        val safePage = page.coerceIn(0, r.pageCount - 1)
        FoldLogger.d(TAG, "updatePage: $page → safePage=$safePage")
        _state.value = _state.value.copy(currentChapterIndex = safePage, currentPage = safePage)
    }

    override fun close() {
        FoldLogger.i(TAG, "close")
        try {
            renderer?.close()
            fileDescriptor?.close()
        } catch (_: Exception) {}
        renderer = null
        fileDescriptor = null
        chapters = emptyList()
    }
}
