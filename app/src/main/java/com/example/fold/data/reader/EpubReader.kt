package com.example.fold.data.reader

import android.util.Base64
import com.example.fold.AppContainer
import com.example.fold.R
import com.example.fold.util.FoldLogger
import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.StringReader
import java.net.URLDecoder
import java.util.regex.Matcher
import java.util.regex.Pattern
import java.util.zip.ZipFile

private const val TAG = "Fold_Epub"
private const val MAX_CACHED_CHAPTERS = 10

class EpubReader : ContentReader {

    private val _state = MutableStateFlow(ReaderState())
    override val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var zipFile: ZipFile? = null
    private var chapters: List<Chapter> = emptyList()
    private var spineItems: List<String> = emptyList()
    private var manifest: Map<String, String> = emptyMap()
    private var opfDir: String = ""
    private val chapterHtmlCache = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?): Boolean {
            return size > MAX_CACHED_CHAPTERS
        }
    }

    override suspend fun open(filePath: String, encoding: String?) = withContext(Dispatchers.IO) {
        try {
            FoldLogger.i(TAG, "open: filePath=$filePath")
            close()
            _state.value = _state.value.copy(
                isLoading = true, error = null,
                filePath = filePath, fileName = File(filePath).name
            )

            val file = File(filePath)
            if (!file.exists()) {
                FoldLogger.e(TAG, "open: file not found: $filePath")
                _state.value = _state.value.copy(isLoading = false, error = AppContainer.appContext.getString(R.string.reader_file_not_found, file.name))
                return@withContext
            }

            val zip = ZipFile(file)
            zipFile = zip
            FoldLogger.d(TAG, "open: zipEntries=${zip.size()}")

            val opfPath = readContainerXml(zip) ?: throw Exception("Invalid EPUB")
            val opfDirValue = opfPath.substringBeforeLast('/')
            opfDir = opfDirValue
            FoldLogger.d(TAG, "open: opfPath=$opfPath, opfDir=$opfDir")

            val opfEntry = zip.getEntry(opfPath) ?: throw Exception("OPF not found")
            val opfContent = zip.getInputStream(opfEntry).bufferedReader().readText()

            manifest = parseManifest(opfContent)
            spineItems = parseSpine(opfContent)
            FoldLogger.d(TAG, "open: manifest=${manifest.size} items, spineItems=${spineItems.size}")
            val tocMap = parseTocNcx(zip, opfDir)
            FoldLogger.d(TAG, "open: tocEntries=${tocMap.size}")

            chapters = spineItems.mapIndexed { index, href ->
                val fullHref = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
                val title = tocMap[href] ?: tocMap[fullHref] ?: "Chapter ${index + 1}"
                Chapter(title = title, startIndex = index, endIndex = index + 1)
            }

            FoldLogger.i(TAG, "open: success, chapters=${chapters.size}")
            _state.value = _state.value.copy(
                isLoading = false, chapters = chapters,
                currentChapterIndex = 0, totalPages = chapters.size
            )
        } catch (e: Exception) {
            FoldLogger.e(TAG, "open: failed", e)
            _state.value = _state.value.copy(isLoading = false, error = e.message)
        }
    }

    private fun readContainerXml(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        val content = zip.getInputStream(entry).bufferedReader().readText()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(content))
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
            }
            eventType = parser.next()
        }
        return null
    }

    private fun parseManifest(opfContent: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(opfContent))
        var inManifest = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "manifest") inManifest = true
                    else if (inManifest && parser.name == "item") {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (id != null && href != null) result[id] = href
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "manifest") inManifest = false
            }
            eventType = parser.next()
        }
        return result
    }

    private fun parseSpine(opfContent: String): List<String> {
        val ids = mutableListOf<String>()
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(opfContent))
        var inSpine = false
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "spine") inSpine = true
                    else if (inSpine && parser.name == "itemref") {
                        val idref = parser.getAttributeValue(null, "idref")
                        if (idref != null) manifest[idref]?.let { ids.add(it) }
                    }
                }
                XmlPullParser.END_TAG -> if (parser.name == "spine") inSpine = false
            }
            eventType = parser.next()
        }
        return ids
    }

    private fun parseTocNcx(zip: ZipFile, opfDir: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val possiblePaths = listOf("$opfDir/toc.ncx", "toc.ncx", "OEBPS/toc.ncx", "OPS/toc.ncx")
        val tocPath = possiblePaths.firstOrNull { zip.getEntry(it) != null } ?: return result
        val content = zip.getInputStream(zip.getEntry(tocPath)).bufferedReader().readText()

        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(content))
        var title: String? = null
        var href: String? = null
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "content") href = parser.getAttributeValue(null, "src")
                }
                XmlPullParser.TEXT -> {
                    if (parser.text?.isNotBlank() == true) title = parser.text.trim()
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "navPoint" && title != null && href != null) {
                        result[href.substringBefore('#')] = title
                        title = null; href = null
                    }
                }
            }
            eventType = parser.next()
        }
        return result
    }

    suspend fun getChapterHtml(index: Int): String = withContext(Dispatchers.IO) {
        chapterHtmlCache[index]?.let {
            FoldLogger.d(TAG, "getChapterHtml: cache hit, index=$index")
            return@withContext it
        }
        val zip = zipFile ?: run { FoldLogger.e(TAG, "getChapterHtml: zipFile is null"); return@withContext "" }
        if (index !in spineItems.indices) {
            FoldLogger.w(TAG, "getChapterHtml: index=$index out of range (spineItems=${spineItems.size})")
            return@withContext ""
        }

        val href = spineItems[index]
        val fullPath = if (opfDir.isNotEmpty()) "$opfDir/$href" else href
        FoldLogger.d(TAG, "getChapterHtml: index=$index, href=$href, fullPath=$fullPath")

        try {
            val entry = zip.getEntry(fullPath) ?: zip.getEntry(href) ?: run {
                FoldLogger.e(TAG, "getChapterHtml: zip entry not found: fullPath=$fullPath, href=$href")
                return@withContext ""
            }
            val html = zip.getInputStream(entry).bufferedReader().readText()
            FoldLogger.v(TAG, "getChapterHtml: rawHtmlLen=${html.length}")
            val bodyContent = extractBody(html)
            val chapterDir = fullPath.substringBeforeLast('/')
            val resolvedBody = inlineImages(zip, bodyContent, chapterDir)
            val wrapped = """<!DOCTYPE html><html><head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>body{font-family:serif;padding:16px;word-wrap:break-word}img{max-width:100%;height:auto}</style>
                </head><body>$resolvedBody</body></html>"""
            chapterHtmlCache[index] = wrapped
            FoldLogger.i(TAG, "getChapterHtml: success, index=$index, finalLen=${wrapped.length}")
            wrapped
        } catch (e: Exception) {
            FoldLogger.e(TAG, "getChapterHtml: failed, index=$index", e)
            "<p>Error: ${e.message}</p>"
        }
    }

    private fun extractBody(html: String): String {
        val start = html.indexOf("<body", ignoreCase = true)
        val end = html.lastIndexOf("</body>", ignoreCase = true)
        return if (start >= 0 && end > start) html.substring(html.indexOf(">", start) + 1, end) else html
    }

    private fun inlineImages(zip: ZipFile, html: String, chapterDir: String): String {
        val matcher = IMG_SRC_PATTERN.matcher(html)
        val sb = StringBuilder()
        var lastEnd = 0
        var total = 0; var resolved = 0; var failed = 0
        while (matcher.find()) {
            sb.append(html, lastEnd, matcher.start())
            lastEnd = matcher.end()
            val rawSrc = matcher.group(1) ?: continue
            if (rawSrc.startsWith("data:")) { sb.append(matcher.group(0)); continue }
            total++
            val resolvedPath = resolveImagePath(rawSrc, chapterDir)
            if (resolvedPath == null) { failed++; FoldLogger.w(TAG, "inlineImages: resolve failed, src=$rawSrc"); sb.append(matcher.group(0)); continue }
            val dataUri = imageToDataUri(zip, resolvedPath)
            if (dataUri == null) { failed++; FoldLogger.w(TAG, "inlineImages: extract failed, resolvedPath=$resolvedPath"); sb.append(matcher.group(0)); continue }
            resolved++
            val matched = matcher.group(0) ?: continue
            sb.append(matched.replace(rawSrc, dataUri))
        }
        sb.append(html, lastEnd, html.length)
        if (total > 0) FoldLogger.i(TAG, "inlineImages: total=$total, resolved=$resolved, failed=$failed")
        return sb.toString()
    }

    /** 解析相对路径：../img/a.jpg + OEBPS/ch01 → OEBPS/img/a.jpg */
    private fun resolveImagePath(src: String, chapterDir: String): String? {
        val decoded = URLDecoder.decode(src, "UTF-8")
        val base = if (chapterDir.isNotEmpty()) "$chapterDir/" else ""
        val parts = mutableListOf<String>()
        for (part in (base + decoded).split('/')) {
            when (part) { "", "." -> continue; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(part) }
        }
        return if (parts.isEmpty()) null else parts.joinToString("/")
    }

    private fun imageToDataUri(zip: ZipFile, entryPath: String): String? {
        val entry = zip.getEntry(entryPath) ?: run {
            FoldLogger.w(TAG, "imageToDataUri: entry not found: $entryPath")
            return null
        }
        if (entry.size > 2 * 1024 * 1024) {
            FoldLogger.w(TAG, "imageToDataUri: image too large (${entry.size} bytes), skipping: $entryPath")
            return null
        }
        return try {
            val baos = ByteArrayOutputStream()
            zip.getInputStream(entry).use { it.copyTo(baos) }
            val bytes = baos.toByteArray()
            val ext = entryPath.substringAfterLast('.', "").lowercase()
            val mime = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "svg" -> "image/svg+xml"; "webp" -> "image/webp"; else -> "image/png" }
            FoldLogger.v(TAG, "imageToDataUri: $entryPath → $mime, ${bytes.size} bytes")
            "data:$mime;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        } catch (e: Exception) {
            FoldLogger.e(TAG, "imageToDataUri: failed, entryPath=$entryPath", e)
            null
        }
    }

    override suspend fun seekTo(position: Long) {
        FoldLogger.d(TAG, "seekTo: position=$position")
        val index = position.toInt()
        if (index in chapters.indices) {
            FoldLogger.d(TAG, "seekTo: found chapter index=$index")
            _state.value = _state.value.copy(currentChapterIndex = index, currentScrollOffset = 0)
        } else {
            FoldLogger.w(TAG, "seekTo: index=$index out of range [0..${chapters.size})")
        }
    }

    override fun getChapters(): List<Chapter> {
        FoldLogger.d(TAG, "getChapters: count=${chapters.size}")
        return chapters
    }

    fun updateChapterIndex(index: Int) {
        FoldLogger.d(TAG, "updateChapterIndex: index=$index")
        _state.value = _state.value.copy(currentChapterIndex = index, currentScrollOffset = 0)
    }

    override fun close() {
        FoldLogger.i(TAG, "close")
        chapterHtmlCache.clear()
        try { zipFile?.close() } catch (_: Exception) {}
        zipFile = null
        chapters = emptyList(); spineItems = emptyList(); manifest = emptyMap(); opfDir = ""
    }

    companion object {
        /** 匹配 <img src="..."> 或 <img src='...'>，捕获组 1 = src 值 */
        private val IMG_SRC_PATTERN: Pattern = Pattern.compile(
            """<img\s[^>]*\bsrc\s*=\s*["']([^"']*?)["'][^>]*/?>""",
            Pattern.CASE_INSENSITIVE
        )
    }
}
