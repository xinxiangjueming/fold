package com.example.fold.data.server

object MimeTypes {

    private val extensionMap = mapOf(
        "html" to "text/html",
        "css" to "text/css",
        "js" to "application/javascript",
        "json" to "application/json",
        "txt" to "text/plain",
        "xml" to "application/xml",
        "pdf" to "application/pdf",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "flv" to "video/x-flv",
        "wmv" to "video/x-ms-wmv",
        "webm" to "video/webm",
        "3gp" to "video/3gpp",
        "ts" to "video/mp2t",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "flac" to "audio/flac",
        "aac" to "audio/aac",
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "opus" to "audio/opus",
        "zip" to "application/zip",
        "7z" to "application/x-7z-compressed",
        "rar" to "application/vnd.rar",
        "tar" to "application/x-tar",
        "gz" to "application/gzip",
    )

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return extensionMap[ext] ?: "application/octet-stream"
    }
}
