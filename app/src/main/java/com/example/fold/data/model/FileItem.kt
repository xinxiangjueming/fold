package com.example.fold.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0L,
    val lastModifiedTimestamp: Long = System.currentTimeMillis(),
    val extension: String = "",
    val mimeType: String = ""
) {
    val isReadableFile: Boolean get() = !isDirectory && SUPPORTED_EXTENSIONS.contains(extension.lowercase())
    val isTextEditable: Boolean get() = !isDirectory && TEXT_EDITABLE_EXTENSIONS.contains(extension.lowercase())
    val nameWithoutExtension: String get() = if (extension.isNotEmpty()) name.removeSuffix(".$extension") else name

    companion object {
        val SUPPORTED_EXTENSIONS = setOf("txt", "log", "md", "csv", "json", "xml", "html", "css", "js", "kt", "java", "py", "c", "cpp", "h", "sh", "properties", "cfg", "conf", "ini", "yaml", "yml", "toml", "epub", "pdf")
        val TEXT_EDITABLE_EXTENSIONS = setOf("txt", "log", "md", "csv", "json", "xml", "html", "css", "js", "kt", "java", "py", "c", "cpp", "h", "sh", "properties", "cfg", "conf", "ini", "yaml", "yml", "toml")
    }
}
