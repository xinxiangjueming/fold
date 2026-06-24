package com.example.fold.util

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.example.fold.shizuku.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

private const val TAG = "Shizuku"

/**
 * Shizuku 集成
 *
 * 初始化流程：
 * 1. Manifest 声明 ShizukuProvider（自动初始化 binder 连接）
 * 2. init() 注册监听器
 * 3. binder 到达 → checkPermission → 绑定 ShellService
 * 4. 用户授权 → bindService → 可执行 shell 命令
 */
object ShizukuHelper {

    // ---- 状态 ----
    private val _available = MutableStateFlow(false)   // Shizuku binder 存活
    val available: StateFlow<Boolean> = _available

    private val _granted = MutableStateFlow(false)     // 已获授权
    val granted: StateFlow<Boolean> = _granted

    private var shellService: IShellService? = null
    var serviceBound = false
        private set

    // 服务就绪回调
    var onServiceReady: (() -> Unit)? = null

    private val SERVICE_COMPONENT =
        ComponentName("com.example.fold", "com.example.fold.shizuku.ShellService")

    private const val REQUEST_CODE = 1001

    // ---- ServiceConnection ----
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "ShellService connected")
            shellService = IShellService.Stub.asInterface(binder)
            serviceBound = true
            onServiceReady?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "ShellService disconnected")
            shellService = null
            serviceBound = false
        }
    }

    // ---- 初始化（Application.onCreate 调用） ----
    fun init() {
        Log.d(TAG, "init() called")
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        } catch (e: Exception) {
            Log.e(TAG, "addBinderReceivedListenerSticky failed", e)
        }
        try {
            Shizuku.addBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.e(TAG, "addBinderDeadListener failed", e)
        }
        try {
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (e: Exception) {
            Log.e(TAG, "addRequestPermissionResultListener failed", e)
        }
        // 检查是否已有 binder
        try {
            val alive = Shizuku.pingBinder()
            Log.d(TAG, "init: pingBinder=$alive")
            if (alive) {
                _available.value = true
                checkAndBind()
            }
        } catch (e: Exception) {
            Log.e(TAG, "init: pingBinder failed", e)
        }
    }

    fun destroy() {
        Log.d(TAG, "destroy: removing listeners")
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Exception) {}
    }

    // ---- Activity onResume 时调用 ----
    fun recheck() {
        try {
            val alive = Shizuku.pingBinder()
            Log.d(TAG, "recheck: pingBinder=$alive, available=${_available.value}, granted=${_granted.value}, serviceBound=$serviceBound")
            if (alive) {
                if (!_available.value) _available.value = true
                checkAndBind()
            } else {
                _available.value = false
                _granted.value = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "recheck failed", e)
        }
    }

    // ---- 检查权限 + 绑定服务 ----
    private fun checkAndBind() {
        try {
            val result = Shizuku.checkSelfPermission()
            Log.d(TAG, "checkSelfPermission=$result (0=GRANTED, -1=DENIED)")
            val isGranted = result == PackageManager.PERMISSION_GRANTED
            _granted.value = isGranted
            if (isGranted && !serviceBound) {
                Log.d(TAG, "Permission granted, binding ShellService...")
                bindService()
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkSelfPermission failed", e)
            _granted.value = false
        }
    }

    // ---- 申请权限 ----
    fun requestPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Log.w(TAG, "requestPermission: binder dead")
                return
            }
            // 先检查是否已授权
            val result = Shizuku.checkSelfPermission()
            Log.d(TAG, "requestPermission: pre-check=$result")
            if (result == PackageManager.PERMISSION_GRANTED) {
                _granted.value = true
                if (!serviceBound) bindService()
                return
            }
            Log.d(TAG, "requestPermission: requesting...")
            Shizuku.requestPermission(REQUEST_CODE)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    // ---- 绑定 ShellService ----
    private fun bindService() {
        try {
            Log.d(TAG, "bindService: $SERVICE_COMPONENT")
            Shizuku.bindUserService(
                Shizuku.UserServiceArgs(SERVICE_COMPONENT)
                    .daemon(false)
                    .processNameSuffix("shell")
                    .debuggable(true)
                    .version(1),
                connection
            )
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed", e)
        }
    }

    // ---- 执行 shell 命令 ----
    suspend fun exec(command: String): Result<String> = withContext(Dispatchers.IO) {
        val svc = shellService
        if (svc == null) {
            Log.e(TAG, "exec: service null, bound=$serviceBound")
            return@withContext Result.failure(Exception("Shizuku 服务未连接"))
        }
        try {
            Log.d(TAG, "exec: $command")
            val output = svc.exec(command)
            Log.d(TAG, "exec result: ${output.take(200)}")
            if (output.startsWith("ERROR:")) {
                val parts = output.removePrefix("ERROR:").split(":", limit = 2)
                Result.failure(Exception(parts.getOrElse(1) { "exit ${parts[0]}" }))
            } else {
                Result.success(output)
            }
        } catch (e: Exception) {
            Log.e(TAG, "exec exception: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun listRestrictedDir(path: String): Result<List<RestrictedFileInfo>> {
        Log.d(TAG, "listRestrictedDir: $path")
        return exec("ls -la '$path'").map { output ->
            output.lines()
                .filter { it.isNotBlank() && !it.startsWith("total") }
                .mapNotNull { parseLsLine(it, path) }
                .also { Log.d(TAG, "listRestrictedDir: ${it.size} items") }
        }
    }

    suspend fun deleteRestricted(path: String): Result<Unit> {
        Log.d(TAG, "deleteRestricted: path=$path")
        return exec("rm -rf '$path'").map { }
    }

    suspend fun renameRestricted(oldPath: String, newName: String): Result<Unit> {
        val parent = oldPath.substringBeforeLast('/')
        Log.d(TAG, "renameRestricted: oldPath=$oldPath, newName=$newName")
        return exec("mv '$oldPath' '$parent/$newName'").map { }
    }

    fun needsShizuku(path: String): Boolean {
        val p = path.trimEnd('/')
        val result = p.contains("/Android/data") || p.contains("/Android/obb")
        Log.v(TAG, "needsShizuku: path=$p, result=$result")
        return result
    }

    // ---- 解析 ls 输出 ----
    private fun parseLsLine(line: String, parentPath: String): RestrictedFileInfo? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 7) return null
        val isDir = parts[0].startsWith("d")
        val size = if (isDir) 0L else parts[4].toLongOrNull() ?: 0L

        // 解析文件名：最后一个非日期部分开始
        // ls -la 格式: perms links owner group size date... name
        // date 可能是 "2024-06-23 17:45" (2 部分) 或 "Jun 23  2024" (3 部分)
        val name: String
        var dateStr = ""
        // 从末尾找文件名，从 parts[5] 开始是日期
        if (parts.size >= 8) {
            // 尝试 8+ 部分（日期 2 部分 + 文件名）
            name = parts.drop(7).joinToString(" ")
            dateStr = "${parts[5]} ${parts[6]}"
        } else if (parts.size == 7) {
            // 7 部分：可能是日期 3 部分 + 无文件名，或日期 2 部分 + 文件名 1 部分
            // 假设 parts[5] 是月份名 (如 "Jun")，则日期 3 部分
            if (parts[5].length == 3 && parts[5][0].isLetter()) {
                name = ""  // 无文件名，跳过
                dateStr = "${parts[5]} ${parts[6]}"
            } else {
                name = parts[6]
                dateStr = parts[5]
            }
        } else {
            return null
        }

        if (name == "." || name == ".." || name.isEmpty()) return null

        // 尝试解析时间戳
        var timestamp = 0L
        val dateFormats = listOf(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US),
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US),
            java.text.SimpleDateFormat("MMM dd HH:mm", java.util.Locale.US),
            java.text.SimpleDateFormat("MMM dd yyyy", java.util.Locale.US)
        )
        for (fmt in dateFormats) {
            try {
                val result = fmt.parse(dateStr)
                if (result != null) {
                    val cal = java.util.Calendar.getInstance()
                    cal.time = result
                    if (cal.get(java.util.Calendar.YEAR) == 1970) {
                        cal.set(java.util.Calendar.YEAR, java.util.Calendar.getInstance().get(java.util.Calendar.YEAR))
                    }
                    timestamp = cal.timeInMillis
                    break
                }
            } catch (_: Exception) {}
        }

        return RestrictedFileInfo(
            name = name,
            path = "$parentPath/$name",
            isDirectory = isDir,
            size = size,
            lastModified = timestamp,
            extension = if (isDir) "" else name.substringAfterLast('.', "").lowercase()
        )
    }

    // ---- 监听器 ----
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "*** Binder received ***")
        _available.value = true
        checkAndBind()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "*** Binder dead ***")
        _available.value = false
        _granted.value = false
        shellService = null
        serviceBound = false
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { code, result ->
            val granted = result == PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "*** Permission result: code=$code, granted=$granted ***")
            _granted.value = granted
            if (granted && !serviceBound) bindService()
        }
}

data class RestrictedFileInfo(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val extension: String
)
