package com.example.fold.data.archive

import com.example.fold.util.FoldLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val TAG = "ArchiveHelper"

data class ArchiveEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val compressedSize: Long
)

object ArchiveHelper {

    /** 获取压缩包内容列表 */
    suspend fun listEntries(archivePath: String): List<ArchiveEntry> = withContext(Dispatchers.IO) {
        val file = File(archivePath)
        val ext = file.extension.lowercase()
        try {
            when {
                ext == "zip" -> listZip(file)
                ext == "7z" -> list7z(file)
                ext == "rar" -> listRar(file)
                ext == "tar" -> listTar(file)
                ext == "gz" && file.nameWithoutExtension.endsWith(".tar") -> listTarGz(file)
                else -> emptyList()
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "listEntries failed: ${e.message}")
            emptyList()
        }
    }

    /** 解压到目标目录 */
    suspend fun extract(
        archivePath: String,
        destDir: String,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val file = File(archivePath)
        val dest = File(destDir)
        val ext = file.extension.lowercase()
        dest.mkdirs()

        try {
            when {
                ext == "zip" -> extractZip(file, dest, onProgress)
                ext == "7z" -> extract7z(file, dest, onProgress)
                ext == "rar" -> extractRar(file, dest, onProgress)
                ext == "tar" -> extractTar(file, dest, onProgress)
                ext == "gz" && file.nameWithoutExtension.endsWith(".tar") -> extractTarGz(file, dest, onProgress)
                else -> return@withContext Result.failure(Exception("Unsupported format: $ext"))
            }
            FoldLogger.i(TAG, "extracted to $destDir")
            Result.success(destDir)
        } catch (e: Exception) {
            FoldLogger.e(TAG, "extract failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 压缩为 ZIP */
    suspend fun compressZip(
        sourceDir: String,
        outputPath: String,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val source = File(sourceDir)
        val output = File(outputPath)
        try {
            val allFiles = source.walkTopDown().filter { it.isFile }.toList()
            val totalBytes = allFiles.sumOf { it.length() }
            var written = 0L
            var lastReport = 0L
            val buf = ByteArray(512 * 1024)
            ZipOutputStream(FileOutputStream(output)).use { zos ->
                zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                allFiles.forEachIndexed { _, file ->
                    ensureActive()
                    zos.putNextEntry(ZipEntry(file.relativeTo(source).path.replace('\\', '/')))
                    BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            ensureActive()
                            zos.write(buf, 0, len)
                            written += len
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastReport >= 100) {
                                lastReport = now
                                onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                            }
                        }
                    }
                    zos.closeEntry()
                }
            }
            onProgress?.invoke("", (totalBytes ushr 10).toInt(), (totalBytes ushr 10).toInt())
            FoldLogger.i(TAG, "compressed ZIP: $outputPath (${output.length() / 1024}KB)")
            Result.success(outputPath)
        } catch (e: Exception) {
            FoldLogger.e(TAG, "compressZip failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 压缩为 TAR.GZ */
    suspend fun compressTarGz(
        sourceDir: String,
        outputPath: String,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val source = File(sourceDir)
        val output = File(outputPath)
        try {
            val allFiles = source.walkTopDown().filter { it.isFile }.toList()
            val totalBytes = allFiles.sumOf { it.length() }
            var written = 0L
            var lastReport = 0L
            val buf = ByteArray(512 * 1024)
            TarArchiveOutputStream(java.util.zip.GZIPOutputStream(BufferedOutputStream(FileOutputStream(output), 512 * 1024))).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                allFiles.forEachIndexed { _, file ->
                    ensureActive()
                    tar.putArchiveEntry(TarArchiveEntry(file, file.relativeTo(source).path))
                    BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            ensureActive()
                            tar.write(buf, 0, len)
                            written += len
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastReport >= 100) {
                                lastReport = now
                                onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                            }
                        }
                    }
                    tar.closeArchiveEntry()
                }
            }
            onProgress?.invoke("", (totalBytes ushr 10).toInt(), (totalBytes ushr 10).toInt())
            FoldLogger.i(TAG, "compressed TAR.GZ: $outputPath (${output.length() / 1024}KB)")
            Result.success(outputPath)
        } catch (e: Exception) {
            FoldLogger.e(TAG, "compressTarGz failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 压缩为 7Z */
    suspend fun compress7z(
        sourceDir: String,
        outputPath: String,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val source = File(sourceDir)
        val output = File(outputPath)
        try {
            val allFiles = source.walkTopDown().filter { it.isFile }.toList()
            val totalBytes = allFiles.sumOf { it.length() }
            var written = 0L
            var lastReport = 0L
            val buf = ByteArray(512 * 1024)
            SevenZOutputFile(output).use { szof ->
                allFiles.forEachIndexed { _, file ->
                    ensureActive()
                    szof.putArchiveEntry(szof.createArchiveEntry(file, file.relativeTo(source).path))
                    BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                        var len: Int
                        while (input.read(buf).also { len = it } != -1) {
                            ensureActive()
                            szof.write(buf, 0, len)
                            written += len
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastReport >= 100) {
                                lastReport = now
                                onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                            }
                        }
                    }
                    szof.closeArchiveEntry()
                }
            }
            onProgress?.invoke("", (totalBytes ushr 10).toInt(), (totalBytes ushr 10).toInt())
            FoldLogger.i(TAG, "compressed 7Z: $outputPath (${output.length() / 1024}KB)")
            Result.success(outputPath)
        } catch (e: Exception) {
            FoldLogger.e(TAG, "compress7z failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 压缩多个文件到同一个压缩包 */
    suspend fun compressFiles(
        files: List<File>,
        baseDir: File,
        outputPath: String,
        format: String,
        onProgress: ((String, Int, Int) -> Unit)? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val totalBytes = files.sumOf { it.length() }
            var written = 0L
            var lastReport = 0L
            val buf = ByteArray(512 * 1024)
            when (format) {
                "zip" -> {
                    ZipOutputStream(FileOutputStream(outputPath)).use { zos ->
                        zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
                        for (file in files) {
                            ensureActive()
                            val entryName = file.relativeTo(baseDir).path.replace('\\', '/')
                            zos.putNextEntry(ZipEntry(entryName))
                            BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                                var len: Int
                                while (input.read(buf).also { len = it } != -1) {
                                    ensureActive()
                                    zos.write(buf, 0, len)
                                    written += len
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastReport >= 100) {
                                        lastReport = now
                                        onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                                    }
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }
                "targz" -> {
                    TarArchiveOutputStream(java.util.zip.GZIPOutputStream(BufferedOutputStream(FileOutputStream(outputPath), 512 * 1024))).use { tar ->
                        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        for (file in files) {
                            ensureActive()
                            tar.putArchiveEntry(TarArchiveEntry(file, file.relativeTo(baseDir).path))
                            BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                                var len: Int
                                while (input.read(buf).also { len = it } != -1) {
                                    ensureActive()
                                    tar.write(buf, 0, len)
                                    written += len
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastReport >= 100) {
                                        lastReport = now
                                        onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                                    }
                                }
                            }
                            tar.closeArchiveEntry()
                        }
                    }
                }
                "7z" -> {
                    SevenZOutputFile(File(outputPath)).use { szof ->
                        for (file in files) {
                            ensureActive()
                            szof.putArchiveEntry(szof.createArchiveEntry(file, file.relativeTo(baseDir).path))
                            BufferedInputStream(FileInputStream(file), 512 * 1024).use { input ->
                                var len: Int
                                while (input.read(buf).also { len = it } != -1) {
                                    ensureActive()
                                    szof.write(buf, 0, len)
                                    written += len
                                    val now = android.os.SystemClock.uptimeMillis()
                                    if (now - lastReport >= 100) {
                                        lastReport = now
                                        onProgress?.invoke(file.name, (written ushr 10).toInt(), (totalBytes ushr 10).toInt())
                                    }
                                }
                            }
                            szof.closeArchiveEntry()
                        }
                    }
                }
                else -> return@withContext Result.failure(Exception("Unsupported format: $format"))
            }
            onProgress?.invoke("", (totalBytes ushr 10).toInt(), (totalBytes ushr 10).toInt())
            FoldLogger.i(TAG, "compressedFiles: $outputPath (${File(outputPath).length() / 1024}KB)")
            Result.success(outputPath)
        } catch (e: Exception) {
            FoldLogger.e(TAG, "compressFiles failed: ${e.message}")
            Result.failure(e)
        }
    }

    /** 判断文件是否是支持的压缩格式 */
    fun isArchive(fileName: String): Boolean {
        val ext = File(fileName).extension.lowercase()
        return ext in setOf("zip", "7z", "rar", "tar", "gz")
    }

    fun isGzippedTar(fileName: String): Boolean =
        fileName.lowercase().let { it.endsWith(".tar.gz") || it.endsWith(".tgz") }

    // ========== ZIP ==========

    private fun listZip(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, entry.size, 0))
            }
        }
        return entries
    }

    private fun extractZip(file: File, dest: File, onProgress: ((String, Int, Int) -> Unit)?) {
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList()
            entries.forEachIndexed { i, entry ->
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
                onProgress?.invoke(entry.name, i + 1, entries.size)
            }
        }
    }

    // ========== 7Z ==========

    @Suppress("DEPRECATION")
    private fun list7z(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        val opts = org.apache.commons.compress.archivers.sevenz.SevenZFileOptions.builder().build()
        SevenZFile(file, opts).use { szf ->
            szf.entries.forEach { entry ->
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, entry.size, 0))
            }
        }
        return entries
    }

    @Suppress("DEPRECATION")
    private fun extract7z(file: File, dest: File, onProgress: ((String, Int, Int) -> Unit)?) {
        val opts = org.apache.commons.compress.archivers.sevenz.SevenZFileOptions.builder().build()
        SevenZFile(file, opts).use { szf ->
            var entry = szf.nextEntry
            var count = 0
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var len: Int
                        while (szf.read(buffer).also { len = it } != -1) {
                            fos.write(buffer, 0, len)
                        }
                    }
                }
                count++
                onProgress?.invoke(entry.name, count, -1)
                entry = szf.nextEntry
            }
        }
    }

    // ========== RAR ==========

    @Suppress("UNCHECKED_CAST")
    private fun listRar(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        try {
            val ais = org.apache.commons.compress.archivers.ArchiveStreamFactory()
                .createArchiveInputStream(FileInputStream(file)) as org.apache.commons.compress.archivers.ArchiveInputStream<org.apache.commons.compress.archivers.ArchiveEntry>
            var entry = ais.nextEntry
            while (entry != null) {
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, entry.size, 0))
                entry = ais.nextEntry
            }
            ais.close()
        } catch (e: Exception) {
            FoldLogger.e(TAG, "listRar: ${e.message}")
        }
        return entries
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRar(file: File, dest: File, onProgress: ((String, Int, Int) -> Unit)?) {
        val ais = org.apache.commons.compress.archivers.ArchiveStreamFactory()
            .createArchiveInputStream(FileInputStream(file)) as org.apache.commons.compress.archivers.ArchiveInputStream<org.apache.commons.compress.archivers.ArchiveEntry>
        var entry = ais.nextEntry
        var count = 0
        while (entry != null) {
            val outFile = File(dest, entry.name)
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    ais.copyTo(fos)
                }
            }
            count++
            onProgress?.invoke(entry.name, count, -1)
            entry = ais.nextEntry
        }
        ais.close()
    }

    // ========== TAR ==========

    private fun listTar(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        TarArchiveInputStream(FileInputStream(file)).use { tais ->
            var entry = tais.nextEntry
            while (entry != null) {
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, entry.size, 0))
                entry = tais.nextEntry
            }
        }
        return entries
    }

    private fun extractTar(file: File, dest: File, onProgress: ((String, Int, Int) -> Unit)?) {
        TarArchiveInputStream(FileInputStream(file)).use { tais ->
            var entry = tais.nextEntry
            var count = 0
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        tais.copyTo(fos)
                    }
                }
                count++
                onProgress?.invoke(entry.name, count, -1)
                entry = tais.nextEntry
            }
        }
    }

    // ========== TAR.GZ ==========

    private fun listTarGz(file: File): List<ArchiveEntry> {
        val entries = mutableListOf<ArchiveEntry>()
        val gis = GzipCompressorInputStream(FileInputStream(file))
        TarArchiveInputStream(gis).use { tais ->
            var entry = tais.nextEntry
            while (entry != null) {
                entries.add(ArchiveEntry(entry.name, entry.isDirectory, entry.size, 0))
                entry = tais.nextEntry
            }
        }
        return entries
    }

    private fun extractTarGz(file: File, dest: File, onProgress: ((String, Int, Int) -> Unit)?) {
        val gis = GzipCompressorInputStream(FileInputStream(file))
        TarArchiveInputStream(gis).use { tais ->
            var entry = tais.nextEntry
            var count = 0
            while (entry != null) {
                val outFile = File(dest, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos ->
                        tais.copyTo(fos)
                    }
                }
                count++
                onProgress?.invoke(entry.name, count, -1)
                entry = tais.nextEntry
            }
        }
    }
}
