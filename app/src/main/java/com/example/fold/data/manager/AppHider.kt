package com.example.fold.data.manager

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.Base64
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
            .toMutableList()

        // pm hide 会让 app 从 getInstalledApplications 中消失，补回已隐藏的 app
        val existingPkgs = apps.map { it.packageName }.toSet()
        val missingHidden = hiddenPkgs - existingPkgs
        val defaultIcon = android.graphics.drawable.ColorDrawable(android.graphics.Color.GRAY)
        for (pkg in missingHidden) {
            val name = getStoredAppName(context, pkg)
            val icon = loadIconFromPrefs(context, pkg) ?: defaultIcon
            apps.add(AppEntry(
                name = name,
                packageName = pkg,
                icon = icon,
                isHidden = true,
                isProtected = false,
            ))
            FoldLogger.d(TAG, "  restored hidden app: $pkg ($name)")
        }

        val result = apps.sortedWith(compareByDescending<AppEntry> { it.isHidden }.thenBy { it.name })

        FoldLogger.i(TAG, "listApps: total=${allInstalled.size}, skipped(self=$skippedSelf, system=$skippedSystem, protected=$skippedProtected), result=${result.size}")
        result.forEach { FoldLogger.d(TAG, "  app: ${it.name} (${it.packageName}) hidden=${it.isHidden}") }
        cachedApps = result
        result
    }

    /**
     * 隐藏应用桌面图标
     *
     * 使用 cmd package suspend 代替 pm disable-user：
     * - suspend 的 app 保持 "installed" 状态，Activity 始终可解析
     * - unsuspend 几乎瞬时完成，避免 pm enable 的竞态条件
     * - 从桌面隐藏且无法后台运行
     */
    suspend fun hideApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        if (packageName in PROTECTED_PACKAGES) {
            FoldLogger.w(TAG, "hideApp: $packageName is protected")
            return@withContext false
        }

        val pm = context.packageManager
        val appInfo = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (e: Exception) {
            null
        }
        val appName = appInfo?.loadLabel(pm)?.toString() ?: packageName
        val launcherActivity = pm.getLaunchIntentForPackage(packageName)
            ?.component?.flattenToString()

        val method = detectMethod()
        // 使用 suspend（app 保持 installed，Activity 始终可解析）
        val success = when (method) {
            HideMethod.ROOT -> execCmdRoot("cmd package suspend --user 0 $packageName")
            HideMethod.SHIZUKU -> ShizukuHelper.exec("cmd package suspend --user 0 $packageName").isSuccess
            else -> {
                FoldLogger.e(TAG, "hideApp: no elevated method available")
                false
            }
        }

        if (success) {
            addHiddenPackage(context, packageName, appName)
            appInfo?.loadIcon(pm)?.let { icon ->
                saveIconToPrefs(context, packageName, icon)
                FoldLogger.d(TAG, "hideApp: saved icon for $packageName")
            }
            if (launcherActivity != null) {
                getPrefs(context).edit().putString("launcher_$packageName", launcherActivity).apply()
                FoldLogger.d(TAG, "hideApp: saved launcher=$launcherActivity for $packageName")
            }
            // 标记为 suspend 方式隐藏
            getPrefs(context).edit().putBoolean("suspended_$packageName", true).apply()
            invalidateCache()
            FoldLogger.i(TAG, "hideApp: $packageName ($appName) suspended via $method")
        }
        success
    }

    /**
     * 恢复应用桌面图标
     */
    suspend fun unhideApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val method = detectMethod()
        val wasSuspended = getPrefs(context).getBoolean("suspended_$packageName", false)

        val success = when (method) {
            HideMethod.ROOT -> {
                if (wasSuspended) {
                    execCmdRoot("cmd package unsuspend --user 0 $packageName")
                } else {
                    // 兼容旧版本用 disable-user 隐藏的 app
                    execPmRoot("enable", packageName)
                }
            }
            HideMethod.SHIZUKU -> {
                if (wasSuspended) {
                    ShizukuHelper.exec("cmd package unsuspend --user 0 $packageName").isSuccess
                } else {
                    execPmShizuku("enable", packageName)
                }
            }
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
     * 启动已隐藏的应用
     *
     * suspend 的 app 必须先 unsuspend，否则系统会显示 SuspendedAppActivity 拦截对话框
     */
    suspend fun launchApp(context: Context, packageName: String): Boolean = withContext(Dispatchers.IO) {
        val method = detectMethod()
        if (method == null) {
            FoldLogger.e(TAG, "launchApp: no method available")
            return@withContext false
        }

        val wasSuspended = getPrefs(context).getBoolean("suspended_$packageName", false)

        // 1. 先恢复（无论哪种方式都先恢复，避免系统拦截）
        FoldLogger.d(TAG, "launchApp: $packageName restoring (suspended=$wasSuspended)")
        when (method) {
            HideMethod.ROOT -> {
                if (wasSuspended) execCmdRoot("cmd package unsuspend --user 0 $packageName")
                else execCmdRoot("pm enable $packageName")
            }
            HideMethod.SHIZUKU -> {
                if (wasSuspended) ShizukuHelper.exec("cmd package unsuspend --user 0 $packageName")
                else ShizukuHelper.exec("pm enable $packageName")
            }
            else -> return@withContext false
        }

        // 2. 等待系统完成状态更新
        Thread.sleep(500)

        // 3. 启动
        var started = false

        // 方案A：使用存储的 launcher 组件名
        val storedLauncher = getPrefs(context).getString("launcher_$packageName", null)
        if (storedLauncher != null) {
            val startCmd = "am start --user 0 -n $storedLauncher"
            val startResult = when (method) {
                HideMethod.ROOT -> execCmdRootOutput(startCmd)
                HideMethod.SHIZUKU -> ShizukuHelper.exec(startCmd).getOrNull()
                else -> null
            }
            FoldLogger.d(TAG, "launchApp: am start -n $storedLauncher → ${startResult?.take(100)}")
            started = startResult != null && !startResult.contains("Error")
        }

        // 方案B：使用 getLaunchIntentForPackage
        if (!started) {
            val pm = context.packageManager
            val newIntent = pm.getLaunchIntentForPackage(packageName)
            if (newIntent != null) {
                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(newIntent)
                    started = true
                    FoldLogger.d(TAG, "launchApp: $packageName via intent")
                } catch (e: Exception) {
                    FoldLogger.w(TAG, "launchApp: startActivity failed: ${e.message}")
                }
            }
        }

        // 方案C：resolve-activity
        if (!started) {
            val resolveCmd = "cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName"
            val resolveOutput = when (method) {
                HideMethod.ROOT -> execCmdRootOutput(resolveCmd)
                HideMethod.SHIZUKU -> ShizukuHelper.exec(resolveCmd).getOrNull()
                else -> null
            }
            if (resolveOutput != null && !resolveOutput.contains("No activity")) {
                val component = resolveOutput.lines().map { it.trim() }.lastOrNull { it.contains("/") }
                if (component != null) {
                    val startCmd = "am start --user 0 -n $component"
                    val startResult = when (method) {
                        HideMethod.ROOT -> execCmdRootOutput(startCmd)
                        HideMethod.SHIZUKU -> ShizukuHelper.exec(startCmd).getOrNull()
                        else -> null
                    }
                    started = startResult != null && !startResult.contains("Error")
                }
            }
        }

        // 4. 启动后不重新隐藏——suspend/disable 都会杀死正在运行的进程
        //    用户返回 fold 后可手动重新隐藏

        if (started) FoldLogger.i(TAG, "launchApp: $packageName launched successfully")
        else FoldLogger.e(TAG, "launchApp: failed for $packageName")
        started
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

    private fun addHiddenPackage(context: Context, packageName: String, appName: String) {
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(packageName)
        prefs.edit()
            .putStringSet(KEY_HIDDEN_PACKAGES, current)
            .putString("name_$packageName", appName)
            .apply()
    }

    private fun removeHiddenPackage(context: Context, packageName: String) {
        val prefs = getPrefs(context)
        val current = prefs.getStringSet(KEY_HIDDEN_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
        current.remove(packageName)
        prefs.edit()
            .putStringSet(KEY_HIDDEN_PACKAGES, current)
            .remove("name_$packageName")
            .remove("icon_$packageName")
            .remove("launcher_$packageName")
            .apply()
    }

    private fun getStoredAppName(context: Context, packageName: String): String {
        return getPrefs(context).getString("name_$packageName", null) ?: packageName
    }

    private fun getIconDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "hidden_icons")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveIconToPrefs(context: Context, packageName: String, drawable: Drawable) {
        try {
            val bitmap = drawableToBitmap(drawable)
            val file = java.io.File(getIconDir(context), "$packageName.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            // 迁移：删除旧的 SP 存储
            getPrefs(context).edit().remove("icon_$packageName").apply()
        } catch (e: Exception) {
            FoldLogger.w(TAG, "saveIconToPrefs failed for $packageName: ${e.message}")
        }
    }

    private fun loadIconFromPrefs(context: Context, packageName: String): Drawable? {
        return try {
            // 优先从文件加载
            val file = java.io.File(getIconDir(context), "$packageName.png")
            if (file.exists()) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
                return BitmapDrawable(context.resources, bitmap)
            }
            // 回退：从 SP 迁移（兼容旧版本）
            val base64 = getPrefs(context).getString("icon_$packageName", null) ?: return null
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // 迁移到文件
            saveIconToPrefs(context, packageName, BitmapDrawable(context.resources, bitmap))
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            FoldLogger.w(TAG, "loadIconFromPrefs failed for $packageName: ${e.message}")
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, w, h)
        drawable.draw(canvas)
        return bitmap
    }
}
