package com.example.fold.data.provider

import android.os.Environment
import com.example.fold.data.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream

class LocalFileProvider : FileProvider {

    // 目录列表缓存，避免低速存储设备重复读取
    // 限制：最多 32 个目录 + 总数据量不超过 ~2MB
    private var listCacheSizeBytes = 0L
    private val listCache = object : LinkedHashMap<String, List<FileItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>): Boolean {
            val tooMany = size > 32
            val tooLarge = listCacheSizeBytes > 2_000_000
            if ((tooMany || tooLarge) && eldest.value != null) {
                listCacheSizeBytes -= estimateSize(eldest.value)
                return true
            }
            return false
        }
    }
    private var fastCacheSizeBytes = 0L
    private val fastCache = object : LinkedHashMap<String, List<FileItem>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FileItem>>): Boolean {
            val tooMany = size > 32
            val tooLarge = fastCacheSizeBytes > 2_000_000
            if ((tooMany || tooLarge) && eldest.value != null) {
                fastCacheSizeBytes -= estimateSize(eldest.value)
                return true
            }
            return false
        }
    }

    private fun estimateSize(items: List<FileItem>): Long {
        // 每个 FileItem 约 200 字节（name + path + overhead）
        return items.size * 200L
    }

    fun invalidateCache(path: String) {
        listCache.remove(path)?.let { listCacheSizeBytes -= estimateSize(it) }
        fastCache.remove(path)?.let { fastCacheSizeBytes -= estimateSize(it) }
    }

    fun clearAllCaches() {
        listCache.clear(); listCacheSizeBytes = 0
        fastCache.clear(); fastCacheSizeBytes = 0
    }

    override suspend fun listFiles(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        listCache[path]?.let { return@withContext it }

        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        val result = dir.listFiles()?.map { file ->
            FileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0L,
                lastModifiedTimestamp = file.lastModified(),
                extension = if (file.isFile) file.extension.lowercase() else ""
            )
        }?.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()

        listCache[path] = result
        listCacheSizeBytes += estimateSize(result)
        result
    }

    /**
     * 快速列出文件，只读文件名，跳过所有 stat I/O。
     * 在低速 eMMC 设备上，File.isDirectory 每个文件也要 45ms。
     * 根目录特殊处理：Android、data 等已知目录名硬编码为文件夹。
     */
    suspend fun listFilesFast(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        fastCache[path]?.let { return@withContext it }

        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()

        val names = dir.list() ?: return@withContext emptyList()
        val isRoot = path == Environment.getExternalStorageDirectory().absolutePath
        val result = names.map { name ->
            val child = File(dir, name)
            val isDir = if (isRoot) {
                name in KNOWN_ROOT_DIRS || !name.contains('.')
            } else {
                child.isDirectory
            }
            FileItem(
                name = name,
                path = child.absolutePath,
                isDirectory = isDir,
                size = 0L,
                lastModifiedTimestamp = 0L,
                extension = if (!isDir) name.substringAfterLast('.', "").lowercase() else ""
            )
        }.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })

        fastCache[path] = result
        fastCacheSizeBytes += estimateSize(result)
        result
    }

    companion object {
        // 根目录下已知的文件夹名称，避免 stat() 调用
        private val KNOWN_ROOT_DIRS = setOf(
            "Android", "DCIM", "Download", "Movies", "Music", "Pictures",
            "Documents", "Ringtones", "Alarms", "Notifications", "Podcasts",
            "Audiobooks", "Recordings", "MIUI", "Xiaomi", "Tencent",
            "Baidu", "360", "QQBrowser", "UCDownload", "tencent",
            "baidu", "backups", "data", "obb", "system", "vendor",
            "fold", "temp", "tmp", "log", "logs"
        )
    }

    override suspend fun openFile(path: String): InputStream = withContext(Dispatchers.IO) {
        FileInputStream(File(path))
    }

    override suspend fun getFileSize(path: String): Long = withContext(Dispatchers.IO) {
        File(path).length()
    }

    override suspend fun downloadFile(
        remotePath: String,
        outputStream: OutputStream,
        onProgress: ((Long, Long) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        val file = File(remotePath)
        val totalSize = file.length()
        var copied = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                outputStream.write(buffer, 0, read)
                copied += read
                onProgress?.invoke(copied, totalSize)
            }
        }
    }

    override suspend fun uploadFile(
        inputStream: InputStream,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)?
    ) = withContext(Dispatchers.IO) {
        File(remotePath).outputStream().use { output ->
            val buffer = ByteArray(8192)
            var read: Int
            var written = 0L
            while (inputStream.read(buffer).also { read = it } != -1) {
                output.write(buffer, 0, read)
                written += read
                onProgress?.invoke(written, -1L)
            }
        }
    }

    /** 检查是否有写入权限 */
    fun hasPermission(): Boolean {
        return true // Android 9 只需 WRITE_EXTERNAL_STORAGE，已在 Manifest 声明
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) return@withContext true
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> child.delete() }
            file.delete()
        } else {
            file.delete()
        }
    }

    override suspend fun rename(oldPath: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val old = File(oldPath)
        val new = File(old.parent, newName)
        old.renameTo(new)
    }

    override suspend fun createDirectory(path: String): Boolean = withContext(Dispatchers.IO) {
        File(path).mkdirs()
    }

    override fun getRootPath(): String = Environment.getExternalStorageDirectory().absolutePath
}
