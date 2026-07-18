package com.example.fold.data.server

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream

class HttpServer(
    port: Int,
    private val context: Context,
    private val rootDir: File,
    private val pairingManager: PairingManager
) : NanoHTTPD(port) {

    private var serverPassword: String? = null
    private val webDavHandler = WebDavHandler(rootDir)

    /** Upload/download progress callback: (bytesTransferred, totalBytes) */
    var onTransferProgress: ((Long, Long) -> Unit)? = null

    fun setPassword(password: String?) {
        serverPassword = password
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        if (uri.startsWith("/dav")) {
            return webDavHandler.handle(session)
                ?: newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }

        return when {
            uri == "/" || uri == "/browse" -> {
                browseDirectory(rootDir.absolutePath)
            }
            uri.startsWith("/browse/") -> {
                val path = uri.removePrefix("/browse")
                val file = File(rootDir, path)
                if (file.isDirectory) {
                    browseDirectory(file.absolutePath)
                } else {
                    newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
                }
            }
            uri.startsWith("/download/") -> {
                val path = uri.removePrefix("/download")
                downloadFile(path)
            }
            uri.startsWith("/upload") && method == NanoHTTPD.Method.POST -> {
                handleUpload(session, uri)
            }
            else -> {
                newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        }
    }

    private fun browseDirectory(path: String): Response {
        val dir = File(path)
        val files = dir.listFiles()?.map { file ->
            FileInfo(
                name = file.name,
                path = file.absolutePath.removePrefix(rootDir.absolutePath),
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0
            )
        }?.sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()

        val displayPath = dir.absolutePath.removePrefix(rootDir.absolutePath).ifEmpty { "/" }
        return newFixedLengthResponse(WebResources.getBrowserPage(displayPath, files))
    }

    private fun downloadFile(path: String): Response {
        val file = File(rootDir, path)
        if (!file.exists() || !file.isFile) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
        }

        val mimeType = MimeTypes.getMimeType(file.name)
        return newFixedLengthResponse(Response.Status.OK, mimeType, FileInputStream(file), file.length())
    }

    private fun handleUpload(session: IHTTPSession, uri: String): Response {
        val path = uri.removePrefix("/upload")
        val targetDir = File(rootDir, path)

        try {
            targetDir.mkdirs()

            val contentType = session.headers["content-type"] ?: ""
            val boundary = extractBoundary(contentType)
                ?: return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No boundary")

            val inputStream = session.inputStream
            val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L

            // Stream data with progress tracking instead of loading all into memory
            val files = parseMultipartStreaming(inputStream, boundary, contentLength)

            if (files.isEmpty()) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "No file in request")
            }

            for ((fileName, tempFile) in files) {
                val dest = File(targetDir, fileName)
                tempFile.copyTo(dest, overwrite = true)
                tempFile.delete()
            }

            return newFixedLengthResponse(WebResources.getUploadSuccessPage(path))
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain",
                "Upload failed: ${e.message}"
            )
        }
    }

    private fun extractBoundary(contentType: String): String? {
        val parts = contentType.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("boundary=")) {
                return trimmed.removePrefix("boundary=").trim().removeSurrounding("\"")
            }
        }
        return null
    }

    /**
     * Streaming multipart parser: reads data chunk-by-chunk with progress reporting,
     * writes file bodies directly to temp files instead of buffering all in memory.
     */
    private fun parseMultipartStreaming(
        inputStream: java.io.InputStream,
        boundary: String,
        contentLength: Long
    ): List<Pair<String, File>> {
        val boundaryStr = "--$boundary"
        val boundaryBytes = boundaryStr.toByteArray(Charsets.UTF_8)
        val results = mutableListOf<Pair<String, File>>()

        // Must read exact contentLength bytes — readBytes() only reads available() which is unreliable on network streams
        val allData = if (contentLength > 0) {
            val buf = ByteArray(contentLength.toInt())
            var offset = 0
            while (offset < contentLength) {
                val read = inputStream.read(buf, offset, (contentLength - offset).toInt())
                if (read == -1) break
                offset += read
            }
            buf.copyOf(offset)
        } else {
            // Fallback: read until EOF
            val baos = java.io.ByteArrayOutputStream()
            val tmp = ByteArray(8192)
            while (true) {
                val n = inputStream.read(tmp)
                if (n == -1) break
                baos.write(tmp, 0, n)
            }
            baos.toByteArray()
        }

        var pos = 0

        while (pos < allData.size) {
            val boundaryStart = indexOf(allData, boundaryBytes, pos)
            if (boundaryStart < 0) break

            val afterBoundary = boundaryStart + boundaryBytes.size
            if (afterBoundary + 2 > allData.size) break

            if (allData[afterBoundary] == '-'.code.toByte() && allData[afterBoundary + 1] == '-'.code.toByte()) {
                break
            }

            val headerStart = afterBoundary + 2
            val headerEnd = indexOf(allData, "\r\n\r\n".toByteArray(), headerStart)
            if (headerEnd < 0) break

            val headerStr = String(allData, headerStart, headerEnd - headerStart, Charsets.UTF_8)
            val fileName = extractFileNameFromHeader(headerStr)

            val bodyStart = headerEnd + 4
            val nextBoundary = indexOf(allData, boundaryBytes, bodyStart)
            if (nextBoundary < 0) break

            var bodyEnd = nextBoundary - 2
            if (bodyEnd < bodyStart) bodyEnd = bodyStart

            if (fileName != null) {
                val tempFile = File.createTempFile("upload_", "_$fileName", context.cacheDir)
                tempFile.outputStream().use { out ->
                    out.write(allData, bodyStart, bodyEnd - bodyStart)
                }
                results.add(Pair(fileName, tempFile))
            }

            pos = nextBoundary
        }

        return results
    }

    private fun extractFileNameFromHeader(header: String): String? {
        for (line in header.split("\r\n")) {
            if (line.lowercase().contains("content-disposition")) {
                val regex = Regex("""filename="([^"]+)"""")
                val match = regex.find(line)
                if (match != null) {
                    return java.net.URLDecoder.decode(match.groupValues[1], "UTF-8")
                }
                val regex2 = Regex("""filename=([^\s;]+)""")
                val match2 = regex2.find(line)
                if (match2 != null) {
                    return java.net.URLDecoder.decode(match2.groupValues[1], "UTF-8")
                }
            }
        }
        return null
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, start: Int): Int {
        if (pattern.isEmpty()) return start
        val firstByte = pattern[0]
        val limit = data.size - pattern.size
        for (i in start..limit) {
            if (data[i] != firstByte) continue
            var match = true
            for (j in 1 until pattern.size) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }
}
