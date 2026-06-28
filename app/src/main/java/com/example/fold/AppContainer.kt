package com.example.fold

import android.content.Context
import com.example.fold.data.provider.LocalFileProvider
import com.example.fold.data.reader.EpubReader
import com.example.fold.data.reader.PdfReader
import com.example.fold.data.reader.TxtReader
import com.example.fold.data.server.HttpServer
import com.example.fold.data.server.PairingManager
import com.example.fold.audio.UsbAttachLogger
import com.example.fold.util.FoldLogger
import java.io.File
import java.util.concurrent.TimeUnit

object AppContainer {
    lateinit var appContext: Context

    val localFileProvider by lazy { LocalFileProvider() }
    private var _txtReader: TxtReader? = null
    private var _epubReader: EpubReader? = null
    private var _pdfReader: PdfReader? = null
    private var _readerLastUsed = 0L

    private var httpServer: HttpServer? = null
    val pairingManager by lazy { PairingManager(appContext) }

    val isServerRunning: Boolean get() = httpServer != null

    private fun markReaderUsed() { _readerLastUsed = System.currentTimeMillis() }

    fun getTxtReader(): TxtReader = (_txtReader ?: TxtReader().also { _txtReader = it }).also { markReaderUsed() }
    fun getEpubReader(): EpubReader = (_epubReader ?: EpubReader().also { _epubReader = it }).also { markReaderUsed() }
    fun getPdfReader(): PdfReader = (_pdfReader ?: PdfReader().also { _pdfReader = it }).also { markReaderUsed() }

    fun releaseReaders() {
        _txtReader?.close(); _txtReader = null
        _epubReader?.close(); _epubReader = null
        _pdfReader?.close(); _pdfReader = null
    }

    /**
     * 释放空闲超过指定时间的 reader。
     * 由 MainActivity.onPause 调用，避免后台泄漏。
     */
    fun releaseIdleReader(idleMs: Long = TimeUnit.MINUTES.toMillis(5)) {
        if (_readerLastUsed == 0L) return
        if (System.currentTimeMillis() - _readerLastUsed < idleMs) return
        releaseReaders()
    }

    fun startHttpServer(port: Int = 8080, rootDir: File? = null): String? {
        try {
            stopHttpServer()
            val dir = rootDir ?: File(localFileProvider.getRootPath())
            val server = HttpServer(port, appContext, dir, pairingManager)
            server.start()
            httpServer = server
            return getServerUrl(port)
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun stopHttpServer() {
        try { httpServer?.stop() } catch (_: Exception) {}
        httpServer = null
    }

    fun getServerUrl(port: Int = 8080): String {
        val ip = getLocalIp()
        return "http://$ip:$port"
    }

    @Suppress("DEPRECATION")
    private fun getLocalIp(): String {
        try {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        FoldLogger.init(appContext)

        // 全局崩溃捕获 — 写入文件日志
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FoldLogger.crash(throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
        FoldLogger.i("Fold_App", "AppContainer initialized, log file: ${FoldLogger.getLogFilePath()}")
        UsbAttachLogger.start(appContext)
    }

    /**
     * 释放所有资源。在 Application.onTerminate 或进程退出前调用。
     */
    fun close() {
        releaseReaders()
        stopHttpServer()
        localFileProvider.clearAllCaches()
        com.example.fold.ui.filebrowser.ThumbnailLoader.clearCache()
    }
}
