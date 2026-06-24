package com.example.fold.util

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FoldLogger {

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()
    private var bufferedWriter: BufferedWriter? = null
    private var writeFailCount = 0

    fun init(context: Context) {
        // 优先 app 内部目录，不在用户目录创建文件夹
        val candidates = mutableListOf<File>()
        candidates.add(File(context.filesDir, "fold_reader.log"))
        try {
            val ext = context.getExternalFilesDir(null)
            if (ext != null) candidates.add(File(ext, "fold_reader.log"))
        } catch (_: Exception) {}

        for (file in candidates) {
            try {
                val dir = file.parentFile
                if (dir != null && !dir.exists()) dir.mkdirs()
                if (!file.exists()) {
                    file.writeText("========== Fold Reader Log ${dateFormat.format(Date())} ==========\n")
                }
                bufferedWriter = BufferedWriter(FileWriter(file, true), 8192)
                logFile = file
                i("FoldLogger", "init OK: ${file.absolutePath}, canWrite=${file.canWrite()}")
                return
            } catch (e: Exception) {
                Log.w("FoldLogger", "init failed for ${file.absolutePath}: ${e.message}")
            }
        }
        Log.e("FoldLogger", "init FAILED: no writable location")
    }

    fun v(tag: String, msg: String) { Log.v(tag, msg); append("V", tag, msg) }
    fun d(tag: String, msg: String) { Log.d(tag, msg); append("D", tag, msg) }
    fun i(tag: String, msg: String) { Log.i(tag, msg); append("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); append("W", tag, msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); append("E", tag, msg) }

    fun w(tag: String, msg: String, t: Throwable?) {
        Log.w(tag, msg, t); append("W", tag, "$msg\n${t?.stackTraceToString() ?: ""}")
    }

    fun e(tag: String, msg: String, t: Throwable?) {
        Log.e(tag, msg, t); append("E", tag, "$msg\n${t?.stackTraceToString() ?: ""}")
    }

    fun crash(t: Throwable) {
        Log.e("Fold_Crash", "FATAL", t); append("F", "Fold_Crash", "FATAL\n${t.stackTraceToString()}")
    }

    fun getLogFilePath(): String = logFile?.absolutePath ?: "none"

    /** 强制刷盘，确保日志写入文件 */
    fun flush() {
        synchronized(lock) {
            try { bufferedWriter?.flush() } catch (_: Exception) {}
        }
    }

    private fun append(level: String, tag: String, msg: String) {
        val writer = bufferedWriter ?: return
        val line = "${dateFormat.format(Date())} $level/$tag: $msg\n"
        synchronized(lock) {
            try {
                writer.write(line)
                writer.flush() // 每条都刷盘，确保崩溃时日志不丢
                writeFailCount = 0
            } catch (e: Exception) {
                writeFailCount++
                if (writeFailCount <= 3) {
                    Log.e("FoldLogger", "write failed (#$writeFailCount): ${e.message}")
                }
            }
        }
    }
}
