package com.example.fold

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.example.fold.ui.common.PermissionHandler
import com.example.fold.ui.navigation.AppNavGraph
import com.example.fold.ui.theme.FoldTheme
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper
import java.io.File

private const val TAG = "FoldMain"

class MainActivity : AppCompatActivity() {

    companion object {
        /** 通知栏点击后，等待 NavGraph 就绪后跳转到播放页 */
        var pendingOpenPlayer = androidx.compose.runtime.mutableStateOf(false)
        /** 息屏归隐：等待 Compose 就绪后导航到计算器 */
        var pendingNavigateCalculator = androidx.compose.runtime.mutableStateOf(false)
        /** 系统打开文件：等待 Compose 就绪后导航到对应查看器 */
        var pendingOpenFile = androidx.compose.runtime.mutableStateOf<String?>(null)
        /** TTS 通知点击：等待 NavGraph 就绪后导航到阅读器 */
        var pendingOpenReader = androidx.compose.runtime.mutableStateOf<String?>(null)
        /** 阅读器是否处于活跃状态（用于音量键翻页判断） */
        @Volatile
        var readerActive = false
        /** 息屏归隐：等待 onResume 时导航到计算器 */
        @Volatile
        var pendingStealthNavigate = false
        /** 息屏归隐：强制首帧为计算器（onCreate 中设置，NavGraph 读取后清除） */
        @Volatile
        var forceStealthStart = false
        /** Activity 重建时恢复的路由 */
        var pendingRestoreRoute = androidx.compose.runtime.mutableStateOf<String?>(null)
        /** 当前导航路由（由 NavGraph 更新） */
        @Volatile
        var currentNavRoute: String? = null

        /** 切换伪装后更新 Activity intent，防止系统用已禁用的 alias 恢复任务 */
        fun updateActivityIntent(activity: MainActivity, calculatorMode: Boolean) {
            val alias = if (calculatorMode) "${activity.packageName}.AliasCalc" else "${activity.packageName}.AliasFold"
            activity.intent = android.content.Intent().apply {
                component = android.content.ComponentName(activity.packageName, alias)
                action = android.content.Intent.ACTION_MAIN
                addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            }
            FoldLogger.i("FoldMain", "updateActivityIntent: alias=$alias")
        }
    }

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        val t1 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: savedInstanceState=${savedInstanceState != null}, nightMode=${isNightMode()}, super=${t1-t0}ms")

        // 深浅底色，避免开屏白闪
        val forcedDark = com.example.fold.ui.theme.getDarkModePref(this)
        val isDark = when (forcedDark) { 1 -> true; 2 -> false; else -> isNightMode() }
        window.setBackgroundDrawable(ColorDrawable(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#F5F5F5")))
        val t2 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: theme setup=${t2-t1}ms")

        // 息屏归隐：首帧直接是计算器，不能恢复旧导航栈
        val isStealthResume = pendingStealthNavigate
        if (isStealthResume) {
            pendingStealthNavigate = false
            forceStealthStart = true
            savedInstanceState?.remove("android:support:navigation:nav_graph:navControllerState")
            getSharedPreferences("nav_state", MODE_PRIVATE).edit().remove("current_route").apply()
            FoldLogger.i(TAG, "onCreate: stealth mode, cleared saved navigation state")
        }

        // 沉浸式系统栏全部在 FoldTheme 的 SideEffect 中处理
        setContent {
            FoldTheme {
                PermissionHandler {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
        val t3 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: setContent=${t3-t2}ms, total=${t3-t0}ms")

        // 通知栏点击 → 打开播放页
        if (intent?.getBooleanExtra("OPEN_PLAYER", false) == true) {
            pendingOpenPlayer.value = true
        }
        // TTS 通知栏点击 → 打开阅读器
        if (intent?.getBooleanExtra("OPEN_READER", false) == true) {
            val path = intent.getStringExtra("READER_PATH")
            if (!path.isNullOrEmpty()) {
                pendingOpenReader.value = path
            }
        }

        // 系统打开文件（ACTION_VIEW）
        handleViewIntent(intent)

        // 初始化时同步 intent 指向当前启用的 alias
        val isCalcMode = getSharedPreferences("file_sort", MODE_PRIVATE)
            .getBoolean("calculator_mode", false)
        updateActivityIntent(this, isCalcMode)

        // Activity 重建时恢复路由（从 savedInstanceState 或 SharedPreferences）
        // 息屏归隐时跳过，避免闪现真实页面
        // 不恢复需要文件路径的路由（audio/reader/image/video等），避免调试安装后跳到播放页
        if (!isStealthResume) {
            val savedRoute = savedInstanceState?.getString("current_route")
                ?: getSharedPreferences("nav_state", MODE_PRIVATE).getString("current_route", null)
            val restorableRoutes = emptyList<String>()
            if (savedRoute != null && savedRoute in restorableRoutes) {
                FoldLogger.i(TAG, "onCreate: restoring route=$savedRoute")
                pendingRestoreRoute.value = savedRoute
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val route = currentNavRoute
        val restorableRoutes = emptyList<String>()
        if (route != null && route in restorableRoutes) {
            outState.putString("current_route", route)
            FoldLogger.d(TAG, "onSaveInstanceState: saved route=$route")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("OPEN_PLAYER", false)) {
            FoldLogger.i(TAG, "onNewIntent: OPEN_PLAYER")
            pendingOpenPlayer.value = true
        }
        if (intent.getBooleanExtra("OPEN_READER", false)) {
            val path = intent.getStringExtra("READER_PATH")
            if (!path.isNullOrEmpty()) {
                FoldLogger.i(TAG, "onNewIntent: OPEN_READER path=$path")
                pendingOpenReader.value = path
            }
        }
        handleViewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        FoldLogger.d(TAG, "onResume: rechecking Shizuku, pendingStealth=$pendingStealthNavigate")
        ShizukuHelper.recheck()
        // 息屏归隐：延迟一帧触发导航，确保 Activity 完全就绪
        if (pendingStealthNavigate) {
            pendingStealthNavigate = false
            android.os.Handler(mainLooper).post {
                pendingNavigateCalculator.value = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // 释放空闲超过 5 分钟的 reader，避免后台资源泄漏
        AppContainer.releaseIdleReader()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val isVolume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        FoldLogger.d(TAG, "onKeyDown: keyCode=$keyCode, isVolume=$isVolume, readerActive=$readerActive")
        if (isVolume && readerActive) {
            val prefs = getSharedPreferences("reader_prefs", MODE_PRIVATE)
            val volPref = prefs.getBoolean("volume_page_turn", false)
            FoldLogger.d(TAG, "onKeyDown: volume_page_turn pref=$volPref")
            if (volPref) {
                val readerEvent = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    com.example.fold.ui.reader.ReaderEvent.VOLUME_UP
                } else {
                    com.example.fold.ui.reader.ReaderEvent.VOLUME_DOWN
                }
                FoldLogger.i(TAG, "onKeyDown: emitting $readerEvent")
                com.example.fold.ui.reader.ReaderEventBus.emit(readerEvent)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleViewIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // Convert content:// URI to file path
        val filePath = try {
            if (uri.scheme == "file") {
                uri.path ?: return
            } else {
                // content:// URI — copy to cache first
                val cacheDir = File(cacheDir, "shared")
                cacheDir.mkdirs()
                val fileName = uri.lastPathSegment ?: "shared_file"
                val cacheFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    cacheFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return
                cacheFile.absolutePath
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "handleViewIntent: failed to resolve URI $uri", e)
            return
        }
        FoldLogger.i(TAG, "handleViewIntent: filePath=$filePath")
        pendingOpenFile.value = filePath
    }
}
