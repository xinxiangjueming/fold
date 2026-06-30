package com.example.fold.data.db

import android.content.Context
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class FileIndexer(private val context: Context) {

    private val dbHelper = FileIndexDatabase(context)
    private val dao by lazy { FileIndexDao(dbHelper.writableDatabase) }

    private val skipDirs = setOf(
        "Android", ".thumbnails", "cache", "Cache",
        "tmp", ".tmp", ".Trash", ".Trashes"
    )

    suspend fun rebuildIndex(): Int = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        dao.deleteAll()

        val allFiles = mutableListOf<FileEntity>()
        val root = android.os.Environment.getExternalStorageDirectory()
        scanDirectory(root, allFiles)

        if (allFiles.isNotEmpty()) {
            dao.insertAll(allFiles)
        }

        val elapsed = System.currentTimeMillis() - t0
        FoldLogger.i("FileIndexer", "Rebuilt index: ${allFiles.size} files in ${elapsed}ms")
        allFiles.size
    }

    private fun scanDirectory(dir: File, result: MutableList<FileEntity>) {
        if (!dir.exists() || !dir.isDirectory) return
        if (dir.name in skipDirs) return

        try {
            dir.listFiles()?.forEach { file ->
                if (file.isDirectory) {
                    scanDirectory(file, result)
                } else {
                    val ext = file.extension.lowercase()
                    result.add(FileEntity(
                        path = file.absolutePath,
                        name = file.name,
                        nameLower = file.name.lowercase(),
                        isDirectory = false,
                        size = file.length(),
                        lastModified = file.lastModified(),
                        extension = ext,
                        parentPath = file.parent ?: ""
                    ))
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
