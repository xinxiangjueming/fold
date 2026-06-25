package com.example.fold.data.manager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

private const val TAG = "AppHider"
private const val PREFS_NAME = "hidden_apps"
private const val KEY_HIDDEN_PACKAGES = "packages"

/**
 * 隐藏应用管理器
 *
 * 支持三种方式隐藏桌面图标（按优先级）：
 * 1. Root：su -c pm hide / pm unhide
 * 2. Shizuku：通过 ShellService 执行 pm hide / pm unhide
 * 3. PackageManager API：系统 app 直接调用 setComponentEnabledSetting
 */
object AppHider {

    /** 受保护的包名，不允许隐藏 */
    private val PROTECTED_PACKAGES = setOf(
        "com.example.fold",
        "com.android.systemui",
        "com.android.settings",
        "com.android.phone",
        "com.android.dialer",
        "com.android.contacts",
        "com.android.camera",
        "com.android.launcher",
        "com.miui.home",
        "com.miui.securitycenter",
        "com.xiaomi.market",
        "com.android.packageinstaller",
    )

    /** 应用列表缓存 */
    private var cachedApps: List<AppEntry>? = null

    /** 标记缓存需要刷新（安装/卸载 app 时触发） */
    fun invalidateCache() {
        cachedApps = null
        FoldLogger.d(TAG, "cache invalidated")
    }

    /** 可用的隐藏方式 */
    enum class HideMethod { ROOT, SHIZUKU, SYSTEM }

    data class AppEntry(
        val name: String,
        val packageName: String,
        val icon: Drawable,
        val isHidden: Boolean,
        val isProtected: Boolean,
    )

    // ==================== 公开 API ====================

    /**
     * 检测当前可用的隐藏方式（按优先级）
     */
    suspend fun detectMethod(): HideMethod? {
        val hasRoot = checkRoot()
        val shizukuGranted = ShizukuHelper.granted.value
        val shizukuBound = ShizukuHelper.serviceBound.value
        FoldLogger.i(TAG, "detectMethod: root=$hasRoot, shizukuGranted=$shizukuGranted, shizukuBound=$shizukuBound")
        if (hasRoot) return HideMethod.ROOT
        if (shizukuGranted && shizukuBound) return HideMethod.SHIZUKU
        return null
    }

    /**
     * 获取所有第三方应用（含已隐藏的），有缓存，安装/卸载时自动失效
     */
    suspend fun listApps(context: Context): List<AppEntry> = withContext(Dispatchers.IO) {
        cachedApps?.let {
            FoldLogger.d(TAG, "listApps: returning cached ${it.size} apps")
            return@withContext it
        }

        val pm = context.packageManager
        val hiddenPkgs = getHiddenPackages(context)

        val allInstalled = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        FoldLogger.i(TAG, "listApps: getInstalledApplications count=${allInstalled.size}")

        if (allInstalled.isEmpty()) {
            // MIUI 可能拦截了查询，不要缓存空结果
            FoldLogger.w(TAG, "listApps: empty result, not caching (MIUI block?)")
            return@withContext emptyList()
        }

        var skippedSelf = 0
        var skippedSystem = 0
        var skippedProtected = 0

        val apps = allInstalled
            .filter {
                if (it.packageName == context.packageName) { skippedSelf++; return@filter false }
                true
            }
            .filter {
                // FLAG_SYSTEM = 系统预装 app，全部排除
                val isSystem = it.flags and ApplicationInfo.FLAG_SYSTEM != 0
                if (isSystem) { skippedSystem++; return@filter false }
                true
            }
            .filter {
                if (it.packageName in PROTECTED_PACKAGES) { skippedProtected++; return@filter false }
                true
            }
            .map { appInfo ->
                AppEntry(
                    name = appInfo.loadLabel(pm).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm),
                    isHidden = hiddenPkgs.contains(appInfo.packageName),
                    isProtected = false,
                )
            }
            .sortedWith(compareByDescending<AppEntry> { it.isHidden }.thenBy { it.name })

        FoldLogger.i(TAG, "listApps: total=${allInstalled.size}, skipped(self=$skippedSelf, system=$skippedSystem, protected=$skippedProtected), result=${apps.size}")
        apps.forEach { FoldLogger.d(TAG, "  app: ${it.name} (${it.packageName}) hidden=${it.isHidden}") }
        cachedApps = apps
        apps
    }

    /**
     * 隐藏应用桌面图标
     */
    suspend fun hideApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName in PROTECTED_PACKAGES) {
            FoldLogger.w(TAG, "hideApp: $packageName is protected")
            return@withContext false
        }

        val method = detectMethod()
        val success = when (method) {
            HideMethod.ROOT -> execPmRoot("hide", packageName)
            // pm hide 需要 MANAGE_USERS，Shizuku 没有，改用 disable-user
            HideMethod.SHIZUKU -> execPmShizuku("disable-user --user 0", packageName)
            else -> {
                FoldLogger.e(TAG, "hideApp: no elevated method available")
                false
            }
        }

        if (success) {
            addHiddenPackage(context, packageName)
            invalidateCache()
            FoldLogger.i(TAG, "hideApp: $packageName hidden via $method")
        }
        success
    }

    /**
     * 恢复应用桌面图标
     */
    suspend fun unhideApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val method = detectMethod()
        val success = when (method) {
            HideMethod.ROOT -> execPmRoot("unhide", packageName)
            HideMethod.SHIZUKU -> execPmShizuku("enable", packageName)
            else -> {
                FoldLogger.e(TAG, "unhideApp: no elevated method available")
                false
            }
        }

        if (success) {
            removeHiddenPackage(context, packageName)
            invalidateCache()
            FoldLogger.i(TAG, "unhideApp: $packageName restored via $method")
        }
        success
    }

    /**
     * 启动应用（即使已隐藏/禁用，仍可通过 am start 启动）
     */
    suspend fun launchApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        // 1. 先尝试正常 intent（app 未被 disable 时有效）
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            FoldLogger.d(TAG, "launchApp: $packageName via intent")
            return@withContext true
        }

        // 2. app 被 disable-user 后需要临时启用 → 启动 → 再禁用
        val method = detectMethod()
        if (method == null) {
            FoldLogger.e(TAG, "launchApp: no method available")
            return@withContext false
        }

        // 先临时启用（am start 对 disabled app 无效）
        FoldLogger.d(TAG, "launchApp: $packageName is disabled, enabling temporarily")
        val enableCmd = "pm enable $packageName"
        when (method) {
            HideMethod.ROOT -> execCmdRoot(enableCmd)
            HideMethod.SHIZUKU -> ShizukuHelper.exec(enableCmd)
            else -> {}
        }

        // 启动（用 am start -W 等待窗口出现）
        val startCmd = "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER $packageName"
        val startResult = when (method) {
            HideMethod.ROOT -> execCmdRootOutput(startCmd)
            HideMethod.SHIZUKU -> ShizukuHelper.exec(startCmd).getOrNull()
            else -> null
        }
        FoldLogger.d(TAG, "launchApp: am start result=${startResult?.take(100)}")

        // 立即重新禁用（图标短暂闪现约 0.5s，但比不启动好）
        val disableCmd = "pm disable-user --user 0 $packageName"
        when (method) {
            HideMethod.ROOT -> execCmdRoot(disableCmd)
            HideMethod.SHIZUKU -> ShizukuHelper.exec(disableCmd)
            else -> {}
        }

        val success = startResult != null && !startResult.contains("Error")
        if (success) FoldLogger.i(TAG, "launchApp: $packageName launched successfully")
        else FoldLogger.e(TAG, "launchApp: failed for $packageName, result=$startResult")
        success
    }

    // ==================== Root ====================

    private fun checkRoot(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            FoldLogger.d(TAG, "checkRoot: not available (${e.message})")
            false
        }
    }

    private fun execPmRoot(action: String, packageName: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("pm $action $packageName\n")
            os.writeBytes("exit\n")
            os.flush()
            val exitCode = process.waitFor()
            FoldLogger.d(TAG, "execPmRoot: pm $action $packageName → exit=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            FoldLogger.e(TAG, "execPmRoot failed: pm $action $packageName", e)
            false
        }
    }

    private fun execCmdRoot(command: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val exitCode = process.waitFor()
            FoldLogger.d(TAG, "execCmdRoot: $command → exit=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            FoldLogger.e(TAG, "execCmdRoot failed: $command", e)
            false
        }
    }

    private fun execCmdRootOutput(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(process.outputStream)
            os.writeBytes("$command\n")
            os.writeBytes("exit\n")
            os.flush()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            FoldLogger.d(TAG, "execCmdRootOutput: $command → exit=$exitCode, output=${output.take(200)}")
            if (exitCode == 0) output else null
        } catch (e: Exception) {
            FoldLogger.e(TAG, "execCmdRootOutput failed: $command", e)
            null
        }
    }

    // ==================== Shizuku ====================

    private suspend fun execPmShizuku(action: String, packageName: String): Boolean {
        val result = ShizukuHelper.exec("pm $action $packageName")
        result.onFailure { e ->
            FoldLogger.e(TAG, "execPmShizuku failed: pm $action $packageName", e)
        }
        result.onSuccess {
            FoldLogger.d(TAG, "execPmShizuku: pm $action $packageName → ok")
        }
        return result.isSuccess
    }

    // ==================== 内部工具 ====================

    // ==================== 持久化 ====================

    private fun getPrefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun getHiddenPackages(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_HIDDEN_PACKAGES, emptySet()) ?: emptySet()
    }

    private fun addHiddenPackage(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, current).apply()
    }

    private fun removeHiddenPackage(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit().putStringSet(KEY_HIDDEN_PACKAGES, current).apply()
    }
}
