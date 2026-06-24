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

    fun setPassword(password: String?) {
        serverPassword = password
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

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
            uri.startsWith("/upload") && method == Method.POST -> {
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

        val mimeType = getMimeType(file.name)
        return newFixedLengthResponse(
            Response.Status.OK,
            mimeType,
            FileInputStream(file),
            file.length()
        )
    }

    private fun handleUpload(session: IHTTPSession, uri: String): Response {
        val path = uri.removePrefix("/upload")
        val targetDir = File(rootDir, path)

        try {
            val body = HashMap<String, String>()
            session.parseBody(body)

            val tmpFile = File(body["content"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "No file"
            ))

            val files = session.headers["content-disposition"]?.let {
                listOf(tmpFile)
            } ?: listOf(tmpFile)

            for (file in files) {
                val fileName = file.nameWithoutExtension + "." + file.extension
                file.copyTo(File(targetDir, fileName), overwrite = true)
            }

            return newFixedLengthResponse(WebResources.getUploadSuccessPage(path))
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Upload failed: ${e.message}"
            )
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".html", true) -> "text/html"
            fileName.endsWith(".css", true) -> "text/css"
            fileName.endsWith(".js", true) -> "application/javascript"
            fileName.endsWith(".json", true) -> "application/json"
            fileName.endsWith(".txt", true) -> "text/plain"
            fileName.endsWith(".pdf", true) -> "application/pdf"
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".gif", true) -> "image/gif"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mp3", true) -> "audio/mpeg"
            fileName.endsWith(".zip", true) -> "application/zip"
            else -> "application/octet-stream"
        }
    }
}
