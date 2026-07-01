package com.example.fold.data.db

import android.content.Context
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class FileIndexer(private val context: Context) {

    private val dbHelper = FileIndexDatabase(context)
    private val dao by lazy { FileIndexDao(dbHelper.writableDatabase) }

    private val skipDirs = setOf(
        "Android", ".thumbnails", "cache", "Cache",
        "tmp", ".tmp", ".Trash", ".Trashes",
        ".git", ".svn", ".hg", "node_modules",
        "__pycache__", ".gradle", ".idea",
        "build", "Build", "out", "bin",
        "dcim", "DCIM", "Pictures", "Movies",
        "Music", "Podcasts", "Audiobooks", "Recordings",
        "Download", "Downloads", "Documents",
        "Ringtones", "Alarms", "Notifications",
        "MIUI", "Xiaomi", "Tencent", "tencent",
        "Baidu", "baidu", "360", "QQBrowser", "UCDownload"
    )

    private val BATCH_SIZE = 500
    private val MAX_DEPTH = 10

    suspend fun rebuildIndex(onProgress: ((Int) -> Unit)? = null): Int = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        dao.deleteAll()

        var count = 0
        val batch = mutableListOf<FileEntity>()
        val root = android.os.Environment.getExternalStorageDirectory()
        scanDirectory(root, 0, batch) { batchCount ->
            count += batchCount
            onProgress?.invoke(count)
        }
        if (batch.isNotEmpty()) {
            dao.insertAll(batch)
        }

        val elapsed = System.currentTimeMillis() - t0
        FoldLogger.i("FileIndexer", "Rebuilt index: $count files in ${elapsed}ms")
        count
    }

    private suspend fun scanDirectory(
        dir: File,
        depth: Int,
        batch: MutableList<FileEntity>,
        onCount: (Int) -> Unit
    ) {
        coroutineContext.ensureActive()
        if (!dir.exists() || !dir.isDirectory) return
        if (depth > MAX_DEPTH) return
        if (dir.name in skipDirs) return
        if (dir.name.startsWith(".")) return

        try {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    scanDirectory(file, depth + 1, batch, onCount)
                } else {
                    val ext = file.extension.lowercase()
                    batch.add(FileEntity(
                        path = file.absolutePath,
                        name = file.name,
                        nameLower = file.name.lowercase(),
                        isDirectory = false,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        extension = ext,
                        parentPath = file.parent ?: ""
                    ))
                    if (batch.size >= BATCH_SIZE) {
                        dao.insertAll(batch)
                        onCount(batch.size)
                        batch.clear()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun search(query: String, limit: Int = 200): List<FileEntity> {
        return dao.search(query.lowercase(), limit)
    }

    suspend fun searchInPath(query: String, dirPath: String, limit: Int = 200): List<FileEntity> {
        return dao.searchInPath(query.lowercase(), dirPath, limit)
    }

    suspend fun count(): Int = dao.count()

    suspend fun invalidatePath(path: String) = dao.deleteByPath(path)
}
