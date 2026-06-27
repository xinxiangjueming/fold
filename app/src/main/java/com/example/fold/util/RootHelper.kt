package com.example.fold.util

import android.util.Log
import com.example.fold.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

object RootHelper {
    private const val TAG = "RootHelper"

    private var _available: Boolean? = null

    suspend fun isAvailable(): Boolean {
        _available?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
                val exitCode = process.waitFor()
                val ok = exitCode == 0
                _available = ok
                Log.d(TAG, "isAvailable: $ok")
                ok
            } catch (e: Exception) {
                Log.d(TAG, "isAvailable: false (${e.message})")
                _available = false
                false
            }
        }
    }

    suspend fun listDir(path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            // 优先用 stat（输出格式稳定），回退到 ls -la
            os.writeBytes("stat -c '%A %s %n' '$path'/* 2>/dev/null || ls -la '$path'\n")
            os.writeBytes("exit\n")
            os.flush()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            Log.d(TAG, "listDir: $path → exit=$exitCode, lines=${output.lines().size}")

            if (exitCode != 0) return@withContext Result.failure(Exception("root ls failed: exit=$exitCode"))

            val lines = output.lines().filter { it.isNotBlank() && !it.startsWith("total ") }
            val useStat = lines.firstOrNull()?.let { line ->
                val parts = line.trim().split(Regex("\\s+"), limit = 3)
                parts.size == 3 && parts[0].length >= 9 && parts[0].startsWith("d") || parts[0].startsWith("-")
            } == true

            val files = lines.mapNotNull { if (useStat) parseStatLine(it, path) else parseLsLine(it, path) }

            Result.success(files)
        } catch (e: Exception) {
            Log.e(TAG, "listDir failed: $path", e)
            Result.failure(e)
        }
    }

    suspend fun readFile(path: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("cat '$path'\n")
            os.writeBytes("exit\n")
            os.flush()

            val bytes = process.inputStream.readBytes()
            val exitCode = process.waitFor()
            Log.d(TAG, "readFile: $path → exit=$exitCode, size=${bytes.size}")

            if (exitCode != 0) return@withContext Result.failure(Exception("root cat failed: exit=$exitCode"))
            Result.success(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "readFile failed: $path", e)
            Result.failure(e)
        }
    }

    private fun parseStatLine(line: String, parentPath: String): FileItem? {
        val trimmed = line.trim()
        if (trimmed.length < 4) return null
        val firstSpace = trimmed.indexOf(' ')
        val secondSpace = trimmed.indexOf(' ', firstSpace + 1)
        if (firstSpace < 0 || secondSpace < 0) return null
        val perms = trimmed.substring(0, firstSpace)
        val sizeStr = trimmed.substring(firstSpace + 1, secondSpace).trim()
        val name = trimmed.substring(secondSpace + 1).trim().substringAfterLast('/')
        if (name.isEmpty() || name == "." || name == "..") return null
        val isDir = perms.startsWith("d")
        return FileItem(
            name = name,
            path = "$parentPath/$name",
            isDirectory = isDir,
            size = if (isDir) 0L else sizeStr.toLongOrNull() ?: 0L,
            lastModifiedTimestamp = 0L,
            extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
        )
    }

    private fun parseLsLine(line: String, parentPath: String): FileItem? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 7) return null
        if (parts[0] == "total") return null

        val isDir = parts[0].startsWith("d")
        val name = parts.drop(7).joinToString(" ").ifEmpty { return null }
        if (name == "." || name == "..") return null

        val size = if (isDir) 0L else parts[4].toLongOrNull() ?: 0L
        val extension = if (!isDir) name.substringAfterLast('.', "").lowercase() else ""

        return FileItem(
            name = name,
            path = "$parentPath/$name",
            isDirectory = isDir,
            size = size,
            lastModifiedTimestamp = 0L,
            extension = extension
        )
    }
}
