package com.example.fold.ui.filebrowser

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fold.AppContainer
import com.example.fold.R
import com.example.fold.data.model.FileItem
import com.example.fold.data.provider.LocalFileProvider
import com.example.fold.data.db.FileIndexer
import com.example.fold.util.FoldLogger
import com.example.fold.util.RootHelper
import com.example.fold.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class SortMode(val prefValue: String) {
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    DATE_NEWEST("date_newest"),
    DATE_OLDEST("date_oldest"),
    SIZE_LARGEST("size_largest"),
    SIZE_SMALLEST("size_smallest"),
    TYPE_GROUP("type_group");

    companion object {
        fun fromPref(value: String?) = entries.find { it.prefValue == value } ?: NAME_ASC
    }
}

enum class ViewMode(val prefValue: String) {
    LIST("list"),
    GRID("grid");

    companion object {
        fun fromPref(value: String?) = entries.find { it.prefValue == value } ?: LIST
    }
}

data class FileBrowserState(
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pathHistory: List<String> = emptyList(),
    val renamingFile: FileItem? = null,
    val copiedPath: String? = null,
    val sharingFile: FileItem? = null,
    val sharingFiles: List<FileItem> = emptyList(),
    val propertiesFile: FileItem? = null,
    val isServerRunning: Boolean = false,
    val serverUrl: String = "",
    val showHttpDialog: Boolean = false,
    val showSortDialog: Boolean = false,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val sortFolderOnly: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val rootAvailable: Boolean = false,
    val isRestrictedPath: Boolean = false,
    val showHiddenFiles: Boolean = false,
    // 复制/移动剪贴板
    val clipboardFiles: List<FileItem> = emptyList(),
    val clipboardMove: Boolean = false,  // true=移动, false=复制
    // 搜索
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val searchResults: List<FileItem> = emptyList(),
    val searchInCurrentFolder: Boolean = false,
    // 多选
    val selectionMode: Boolean = false,
    val selectedFiles: Set<String> = emptySet(),  // file paths
    // 压缩进度
    val compressProgress: Float = -1f,  // -1=隐藏, 0..1=进度
    val compressFileName: String = "",
    val showBatchCompressDialog: Boolean = false,
    // 外部存储设备
    val storageVolumes: List<StorageVolume> = emptyList(),
    // 视图模式
    val viewMode: ViewMode = ViewMode.LIST,
    // 新建文件夹
    val showNewFolderDialog: Boolean = false,
    // 新建文本
    val showNewTxtDialog: Boolean = false,
)

data class StorageVolume(
    val name: String,
    val path: String,
    val isRemovable: Boolean,
    val isReadOnly: Boolean = false,
)

private const val TAG = "FoldVM"

class FileBrowserViewModel : ViewModel() {

    private val app: Application = AppContainer.appContext as Application
    private val localFileProvider: LocalFileProvider = AppContainer.localFileProvider
    private val prefs = app.getSharedPreferences("file_sort", Context.MODE_PRIVATE)
    private val fileIndexer = FileIndexer(app)

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    // 文件列表独立 StateFlow，避免 UI 状态变化触发 LazyColumn 重组
    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    // 回收站开关
    private val _trashEnabled = MutableStateFlow(prefs.getBoolean("trash_enabled", false))
    val trashEnabled: StateFlow<Boolean> = _trashEnabled.asStateFlow()

    // 文件夹级排序覆盖: path -> SortMode
    private val folderSortOverrides = mutableMapOf<String, SortMode>()

    // 滚动位置记忆：path -> Pair(index, offset)
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    // 计算器伪装模式（小爱老师等学习机）
    private val _calculatorMode = MutableStateFlow(prefs.getBoolean("calculator_mode", false))
    val calculatorMode: StateFlow<Boolean> = _calculatorMode.asStateFlow()

    // FileObserver：监听当前目录外部变化（MTP/ADB/其他App写入）
    private var fileObserver: FileObserver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isObserverRefresh = false
    private val refreshRunnable = Runnable {
        FoldLogger.i(TAG, "FileObserver: debounced refresh triggered")
        isObserverRefresh = true
        val path = _state.value.currentPath
        localFileProvider.invalidateCache(path)
        navigateTo(path)
    }
    private var observerEventCount = 0

    /** 切换伪装后回调（用于更新 Activity intent） */
    var onCalculatorModeChanged: ((Boolean) -> Unit)? = null

    fun toggleCalculatorMode() {
        val newValue = !_calculatorMode.value
        FoldLogger.i(TAG, "toggleCalculatorMode: ${!newValue} -> $newValue")
        _calculatorMode.value = newValue
        prefs.edit().putBoolean("calculator_mode", newValue).apply()

        // 切换桌面图标和名称
        switchLauncherAlias(newValue)
        // 通知外部更新 Activity intent
        onCalculatorModeChanged?.invoke(newValue)
    }

    private fun switchLauncherAlias(calcMode: Boolean) {
        try {
            val pm = AppContainer.appContext.packageManager
            val pkg = AppContainer.appContext.packageName
            val foldAlias = ComponentName(pkg, "$pkg.AliasFold")
            val calcAlias = ComponentName(pkg, "$pkg.AliasCalc")

            if (calcMode) {
                // 先启用计算器入口，等 Launcher 刷新后再禁用 Fold 入口
                pm.setComponentEnabledSetting(calcAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                viewModelScope.launch {
                    kotlinx.coroutines.delay(500)
                    pm.setComponentEnabledSetting(foldAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            } else {
                pm.setComponentEnabledSetting(foldAlias,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                viewModelScope.launch {
                    kotlinx.coroutines.delay(500)
                    pm.setComponentEnabledSetting(calcAlias,
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }
            FoldLogger.i(TAG, "switchLauncherAlias: calcMode=$calcMode, done")
        } catch (e: Exception) {
            FoldLogger.e(TAG, "switchLauncherAlias failed", e)
        }
    }

    init {
        FoldLogger.i(TAG, "init: starting FileBrowserViewModel")
        switchLauncherAlias(_calculatorMode.value)
        val globalMode = SortMode.fromPref(prefs.getString("global_sort", null))
        val showHidden = prefs.getBoolean("show_hidden", false)
        val savedViewMode = ViewMode.fromPref(prefs.getString("view_mode", null))
        _state.update { it.copy(
            isServerRunning = AppContainer.isServerRunning,
            sortMode = globalMode,
            showHiddenFiles = showHidden,
            viewMode = savedViewMode,
            shizukuAvailable = ShizukuHelper.available.value,
            shizukuGranted = ShizukuHelper.granted.value
        ) }
        navigateTo(localFileProvider.getRootPath())

        // 后台构建文件索引（搜索用）
        viewModelScope.launch {
            val count = fileIndexer.count()
            if (count == 0) {
                FoldLogger.i(TAG, "File index empty, rebuilding...")
                fileIndexer.rebuildIndex()
            }
        }

        viewModelScope.launch {
            val hasRoot = RootHelper.isAvailable()
            _state.update { it.copy(rootAvailable = hasRoot) }
        }

        // 检测外部存储设备（OTG、SD卡）
        detectStorageVolumes()

        // Shizuku 服务绑定成功后，自动刷新受限目录
        ShizukuHelper.onServiceReady = {
            if (ShizukuHelper.needsShizuku(_state.value.currentPath)) {
                refresh()
            }
        }

        // 持续监听 Shizuku 状态变化（binder 可能在 ViewModel 创建后才连接）
        viewModelScope.launch {
            ShizukuHelper.available.collect { available ->
                _state.update { it.copy(shizukuAvailable = available) }
            }
        }
        viewModelScope.launch {
            ShizukuHelper.granted.collect { granted ->
                val wasGranted = _state.value.shizukuGranted
                _state.update { it.copy(shizukuGranted = granted) }
                // 授权后自动刷新受限目录
                if (granted && !wasGranted && ShizukuHelper.needsShizuku(_state.value.currentPath)) {
                    kotlinx.coroutines.delay(500)
                    refresh()
                }
            }
        }
    }

    private fun detectStorageVolumes() {
        val volumes = mutableListOf<StorageVolume>()
        try {
            // 内部存储
            volumes.add(StorageVolume(app.getString(R.string.storage_internal), "/storage/emulated/0", false))
            // 检测外部存储
            val sm = app.getSystemService(Context.STORAGE_SERVICE) as android.os.storage.StorageManager
            for (volume in sm.storageVolumes) {
                val path = volume.directory?.absolutePath ?: continue
                if (path == "/storage/emulated/0") continue
                val isRemovable = volume.isRemovable
                val isReadOnly = volume.state == "mounted_ro"
                val name = when {
                    path.contains("usb") || path.contains("otg") -> "OTG"
                    path.contains("sdcard1") || path.contains("ext_sd") -> app.getString(R.string.storage_sdcard)
                    isRemovable -> app.getString(R.string.storage_external)
                    else -> continue
                }
                volumes.add(StorageVolume(name, path, isRemovable, isReadOnly))
                FoldLogger.i(TAG, "detectStorageVolumes: found $name at $path (removable=$isRemovable, readOnly=$isReadOnly, state=${volume.state})")
            }
        } catch (e: Exception) {
            FoldLogger.e(TAG, "detectStorageVolumes: failed", e)
        }
        _state.update { it.copy(storageVolumes = volumes) }
    }

    private fun startObserver(path: String) {
        stopObserver()
        if (ShizukuHelper.needsShizuku(path)) return
        val dir = File(path)
        if (!dir.exists() || !dir.isDirectory) return

        val mask = FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MODIFY or
            FileObserver.MOVED_FROM or
            FileObserver.MOVED_TO or
            FileObserver.CLOSE_WRITE or
            FileObserver.ATTRIB

        fileObserver = object : FileObserver(dir, mask) {
            override fun onEvent(event: Int, eventPath: String?) {
                if (isObserverRefresh) return
                val name = eventPath ?: return
                if (name == "fold.log") return
                val eName = event and 0xFFF
                FoldLogger.d(TAG, "FileObserver: event=$eName path=$name")
                observerEventCount++
                mainHandler.removeCallbacks(refreshRunnable)
                mainHandler.postDelayed(refreshRunnable, 200)
            }
        }
        try {
            fileObserver?.startWatching()
            FoldLogger.i(TAG, "FileObserver: started for $path")
        } catch (e: Exception) {
            FoldLogger.e(TAG, "FileObserver: startWatching failed", e)
            fileObserver = null
        }
    }

    private fun stopObserver() {
        mainHandler.removeCallbacks(refreshRunnable)
        try {
            fileObserver?.stopWatching()
        } catch (_: Exception) {}
        fileObserver = null
    }

    fun onResume() {
        val path = _state.value.currentPath
        if (path.isNotEmpty()) {
            localFileProvider.invalidateCache(path)
            navigateTo(path)
        }
    }

    fun navigateTo(path: String) {
        FoldLogger.d(TAG, "navigateTo: path=$path")
        viewModelScope.launch {
            // 同一目录刷新时保留现有列表，不显示 loading，避免整页闪烁
            val isRefresh = path == _state.value.currentPath && _files.value.isNotEmpty()
            if (!isRefresh) {
                _state.update { it.copy(isLoading = true, error = null) }
            } else {
                _state.update { it.copy(error = null) }
            }
            try {
                val t0 = System.nanoTime()
                val isRestricted = ShizukuHelper.needsShizuku(path)
                val t1 = System.nanoTime()
                val rawFiles = if (isRestricted && ShizukuHelper.granted.value) {
                    var result = ShizukuHelper.listRestrictedDir(path)
                    if (result.isFailure) {
                        kotlinx.coroutines.delay(1000)
                        result = ShizukuHelper.listRestrictedDir(path)
                    }
                    result.getOrElse { throw it }
                        .map { info ->
                            FileItem(
                                name = info.name,
                                path = info.path,
                                isDirectory = info.isDirectory,
                                size = info.size,
                                lastModifiedTimestamp = info.lastModified,
                                extension = info.extension
                            )
                        }
                } else if (isRestricted && !ShizukuHelper.granted.value && _state.value.rootAvailable) {
                    var result = RootHelper.listDir(path)
                    if (result.isFailure) {
                        kotlinx.coroutines.delay(500)
                        result = RootHelper.listDir(path)
                    }
                    result.getOrElse { throw it }
                } else if (isRestricted && !ShizukuHelper.granted.value) {
                    emptyList()
                } else {
                    localFileProvider.listFiles(path)
                }
                val t2 = System.nanoTime()
                FoldLogger.d(TAG, "navigateTo: listFiles=${(t2 - t1) / 1_000_000}ms, count=${rawFiles.size}, isRestricted=$isRestricted")

                // 过滤 + 排序移到 IO 线程，避免大目录卡主线程
                val folderMode = folderSortOverrides[path]
                val sorted = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val hideHidden = _state.value.showHiddenFiles
                    val filtered = if (!hideHidden) rawFiles else rawFiles.filter { !it.name.startsWith(".") }
                    val t3 = System.nanoTime()
                    val effectiveMode = folderMode ?: _state.value.sortMode
                    val result = sortFiles(filtered, effectiveMode)
                    val t4 = System.nanoTime()
                    FoldLogger.d(TAG, "navigateTo: filter=${(t3 - t2) / 1_000_000}ms, sort=${(t4 - t3) / 1_000_000}ms")
                    result
                }
                val t5 = System.nanoTime()
                _files.value = sorted
                _state.update {
                    val newHistory = (it.pathHistory + path).takeLast(100)
                    it.copy(
                        currentPath = path, files = sorted, isLoading = false,
                        pathHistory = newHistory, sortFolderOnly = folderMode != null,
                        isRestrictedPath = isRestricted,
                        shizukuAvailable = ShizukuHelper.available.value,
                        shizukuGranted = ShizukuHelper.granted.value
                    )
                }
                val t6 = System.nanoTime()
                FoldLogger.d(TAG, "navigateTo: success, path=$path, files=${sorted.size}, stateUpdate=${(t6 - t5) / 1_000_000}ms, total=${(t6 - t0) / 1_000_000}ms")
                if (isObserverRefresh) {
                    isObserverRefresh = false
                } else {
                    startObserver(path)
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "navigateTo: failed, path=$path", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun getRootPath(): String = localFileProvider.getRootPath()

    fun navigateUp() {
        val current = _state.value.currentPath
        val root = localFileProvider.getRootPath()
        FoldLogger.d(TAG, "navigateUp: current=$current")
        if (current == root) return
        val parent = current.substringBeforeLast('/').ifEmpty { "/" }
        // 从外部存储（OTG/SD卡）返回时，直接回到内部存储根目录
        if (!current.startsWith(root) && (parent == "/storage" || parent == "/")) {
            navigateTo(root)
            return
        }
        navigateTo(parent)
    }

    fun onFileClick(file: FileItem) {
        FoldLogger.d(TAG, "onFileClick: name=${file.name}, isDir=${file.isDirectory}, path=${file.path}")
        if (file.isDirectory) navigateTo(file.path)
    }

    fun deleteFile(file: FileItem) {
        FoldLogger.i(TAG, "deleteFile: name=${file.name}, path=${file.path}")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = File(file.path)
                val canWrite = f.canWrite() || f.parentFile?.canWrite() == true
                FoldLogger.i(TAG, "deleteFile: canWrite=$canWrite, exists=${f.exists()}, path=${file.path}")
                // 检查是否可写（OTG/SD卡可能被挂载为只读）
                if (!canWrite) {
                    FoldLogger.w(TAG, "deleteFile: path not writable")
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(error = app.getString(R.string.storage_readonly)) }
                    }
                    return@launch
                }
                val success = if (ShizukuHelper.needsShizuku(file.path) && ShizukuHelper.granted.value) {
                    ShizukuHelper.deleteRestricted(file.path).isSuccess
                } else {
                    if (f.exists()) {
                        val deleted = if (_trashEnabled.value) {
                            moveToTrash(f)
                            true
                        } else {
                            f.deleteRecursively()
                        }
                        FoldLogger.i(TAG, "deleteFile: deleteRecursively result=$deleted, name=${file.name}")
                        deleted
                    } else {
                        false
                    }
                }
                if (success) {
                    FoldLogger.i(TAG, "deleteFile: success, name=${file.name}")
                    localFileProvider.invalidateCache(java.io.File(file.path).parent ?: "")
                    val updated = _files.value.filter { it.path != file.path }
                    _files.value = updated
                    _state.update { s -> s.copy(files = updated) }
                } else {
                    FoldLogger.w(TAG, "deleteFile: failed, name=${file.name}, exists=${File(file.path).exists()}, canWrite=${File(file.path).canWrite()}")
                    _state.update { it.copy(error = app.getString(R.string.delete_failed, file.name)) }
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "deleteFile: exception", e)
                _state.update { it.copy(error = app.getString(R.string.delete_failed, file.name)) }
            }
        }
    }

    fun renameFile(file: FileItem) {
        FoldLogger.d(TAG, "renameFile: name=${file.name}, path=${file.path}")
        _state.update { it.copy(renamingFile = file) }
    }

    fun confirmRename(newName: String) {
        val file = _state.value.renamingFile ?: return
        FoldLogger.i(TAG, "confirmRename: oldName=${file.name}, newName=$newName, path=${file.path}")
        viewModelScope.launch {
            try {
                if (ShizukuHelper.needsShizuku(file.path) && ShizukuHelper.granted.value) {
                    ShizukuHelper.renameRestricted(file.path, newName).getOrThrow()
                } else {
                    localFileProvider.rename(file.path, newName)
                }
                val parent = file.path.substringBeforeLast('/')
                localFileProvider.invalidateCache(parent)
                val newPath = "$parent/$newName"
                val newExt = newName.substringAfterLast('.', "").lowercase()
                val updated = _files.value.map {
                    if (it.path == file.path) it.copy(name = newName, path = newPath, extension = newExt) else it
                }.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
                _files.value = updated
                _state.update { s ->
                    s.copy(renamingFile = null, files = updated)
                }
                FoldLogger.i(TAG, "confirmRename: success, newName=$newName")
            } catch (e: Exception) {
                FoldLogger.e(TAG, "confirmRename: failed, newName=$newName", e)
                _state.update { it.copy(error = e.message) }
            }
        }
    }

    fun cancelRename() {
        FoldLogger.d(TAG, "cancelRename")
        _state.update { it.copy(renamingFile = null) }
    }

    fun copyPath(file: FileItem) {
        FoldLogger.d(TAG, "copyPath: path=${file.path}")
        _state.update { it.copy(copiedPath = file.path) }
    }

    fun clearCopiedPath() {
        FoldLogger.d(TAG, "clearCopiedPath")
        _state.update { it.copy(copiedPath = null) }
    }

    fun shareFile(file: FileItem) {
        FoldLogger.d(TAG, "shareFile: name=${file.name}, path=${file.path}")
        _state.update { it.copy(sharingFile = file) }
    }

    fun clearSharingFile() {
        FoldLogger.d(TAG, "clearSharingFile")
        _state.update { it.copy(sharingFile = null) }
    }

    fun batchShare() {
        val selected = _state.value.selectedFiles
        if (selected.isEmpty()) return
        val files = _files.value.filter { it.path in selected && !it.isDirectory }
        if (files.isEmpty()) return
        FoldLogger.d(TAG, "batchShare: ${files.size} files")
        _state.update { it.copy(sharingFiles = files, selectionMode = false, selectedFiles = emptySet()) }
    }

    fun clearSharingFiles() {
        FoldLogger.d(TAG, "clearSharingFiles")
        _state.update { it.copy(sharingFiles = emptyList()) }
    }

    fun showProperties(file: FileItem) {
        FoldLogger.d(TAG, "showProperties: name=${file.name}, isDir=${file.isDirectory}, size=${file.size}")
        _state.update { it.copy(propertiesFile = file) }
    }

    fun hideProperties() {
        FoldLogger.d(TAG, "hideProperties")
        _state.update { it.copy(propertiesFile = null) }
    }

    fun extractArchive(file: FileItem) {
        FoldLogger.i(TAG, "extractArchive: ${file.name}")
        viewModelScope.launch {
            val destDir = File(file.path).parentFile?.resolve(file.nameWithoutExtension)
                ?.absolutePath ?: return@launch
            val result = com.example.fold.data.archive.ArchiveHelper.extract(file.path, destDir)
            if (result.isSuccess) {
                FoldLogger.i(TAG, "extractArchive: success -> $destDir")
                refresh()
            } else {
                FoldLogger.e(TAG, "extractArchive: failed", result.exceptionOrNull())
                _state.update { it.copy(error = app.getString(R.string.result_extract_failed, result.exceptionOrNull()?.message ?: "")) }
            }
        }
    }

    fun copyFile(file: FileItem) {
        FoldLogger.d(TAG, "copyFile: ${file.name}")
        _state.update { it.copy(clipboardFiles = listOf(file), clipboardMove = false) }
    }

    fun moveFile(file: FileItem) {
        FoldLogger.d(TAG, "moveFile: ${file.name}")
        _state.update { it.copy(clipboardFiles = listOf(file), clipboardMove = true) }
    }

    fun pasteFile() {
        val clips = _state.value.clipboardFiles
        if (clips.isEmpty()) return
        val isMove = _state.value.clipboardMove
        val destDir = File(_state.value.currentPath)
        FoldLogger.i(TAG, "pasteFile: ${clips.size} files → ${destDir.absolutePath}, move=$isMove")
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (clip in clips) {
                try {
                    val src = File(clip.path)
                    val dest = File(destDir, clip.name)
                    if (src.absolutePath == dest.absolutePath) continue
                    if (isMove) {
                        src.renameTo(dest)
                    } else {
                        src.copyTo(dest, overwrite = false)
                    }
                    successCount++
                } catch (e: Exception) {
                    FoldLogger.e(TAG, "pasteFile: failed for ${clip.name}", e)
                }
            }
            FoldLogger.i(TAG, "pasteFile: done $successCount/${clips.size}")
            withContext(Dispatchers.Main) { refresh() }
            _state.update { it.copy(clipboardFiles = emptyList(), clipboardMove = false) }
            if (successCount < clips.size) {
                _state.update { it.copy(error = app.getString(R.string.result_copy_done, successCount, clips.size)) }
            }
        }
    }

    fun clearClipboard() {
        _state.update { it.copy(clipboardFiles = emptyList(), clipboardMove = false) }
    }

    private var compressJob: kotlinx.coroutines.Job? = null
    private var compressOutputPath: String? = null
    @Volatile private var compressCancelled = false

    fun compressArchive(file: FileItem, format: String) {
        FoldLogger.i(TAG, "compressArchive: ${file.name}, format=$format")
        val ext = when (format) {
            "targz" -> "tar.gz"
            "7z" -> "7z"
            else -> "zip"
        }
        val outputPath = file.path + ".$ext"
        compressOutputPath = outputPath
        compressCancelled = false
        compressJob = viewModelScope.launch {
            FoldLogger.i(TAG, "compressArchive: setting progress=0")
            _state.update { it.copy(compressProgress = 0f, compressFileName = file.name) }
            val onProgress: (String, Int, Int) -> Unit = { name, current, total ->
                if (!compressCancelled) {
                    val progress = if (total > 0) current.toFloat() / total else 0f
                    FoldLogger.i(TAG, "compressArchive: onProgress $current/$total $name progress=$progress")
                    _state.update { it.copy(compressProgress = progress, compressFileName = name) }
                }
            }
            try {
                FoldLogger.i(TAG, "compressArchive: starting compress, format=$format")
                val result = when (format) {
                    "targz" -> com.example.fold.data.archive.ArchiveHelper.compressTarGz(file.path, outputPath, onProgress)
                    "7z" -> com.example.fold.data.archive.ArchiveHelper.compress7z(file.path, outputPath, onProgress)
                    else -> com.example.fold.data.archive.ArchiveHelper.compressZip(file.path, outputPath, onProgress)
                }
                FoldLogger.i(TAG, "compressArchive: compress done, result=${result.isSuccess}")
                if (result.isSuccess) {
                    FoldLogger.i(TAG, "compressArchive: success -> $outputPath")
                    refresh()
                } else if (result.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
                    FoldLogger.e(TAG, "compressArchive: failed", result.exceptionOrNull())
                    _state.update { it.copy(error = app.getString(R.string.result_compress_failed, result.exceptionOrNull()?.message ?: "")) }
                }
            } finally {
                compressJob = null
                compressOutputPath = null
                _state.update { it.copy(compressProgress = -1f, compressFileName = "") }
            }
        }
    }

    fun cancelCompress() {
        FoldLogger.i(TAG, "cancelCompress")
        compressCancelled = true
        compressJob?.cancel()
        compressJob = null
        compressOutputPath?.let { java.io.File(it).delete() }
        compressOutputPath = null
        _state.update { it.copy(compressProgress = -1f, compressFileName = "") }
    }

    fun refresh() {
        val path = _state.value.currentPath
        FoldLogger.d(TAG, "refresh: path=$path")
        localFileProvider.invalidateCache(path)
        navigateTo(path)
    }

    fun showNewFolderDialog() {
        _state.update { it.copy(showNewFolderDialog = true) }
    }

    fun hideNewFolderDialog() {
        _state.update { it.copy(showNewFolderDialog = false) }
    }

    fun createFolder(name: String) {
        FoldLogger.i(TAG, "createFolder: name=$name")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dir = File(_state.value.currentPath, name)
                val created = if (ShizukuHelper.needsShizuku(dir.parent) && ShizukuHelper.granted.value) {
                    ShizukuHelper.exec("mkdir -p '${dir.absolutePath.replace("'", "'\\''")}'").isSuccess
                } else {
                    dir.mkdirs()
                }
                if (created) {
                    localFileProvider.invalidateCache(_state.value.currentPath)
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(showNewFolderDialog = false) }
                        refresh()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(error = app.getString(R.string.result_create_failed)) }
                    }
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "createFolder failed", e)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = e.message) }
                }
            }
        }
    }

    fun showNewTxtDialog() {
        _state.update { it.copy(showNewTxtDialog = true) }
    }

    fun hideNewTxtDialog() {
        _state.update { it.copy(showNewTxtDialog = false) }
    }

    fun createNewTxt(name: String) {
        val fileName = if (name.endsWith(".txt")) name else "$name.txt"
        FoldLogger.i(TAG, "createNewTxt: $fileName")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(_state.value.currentPath, fileName)
                if (file.exists()) {
                    withContext(Dispatchers.Main) {
                        _state.update { it.copy(error = app.getString(R.string.result_file_exists)) }
                    }
                    return@launch
                }
                file.writeText("")
                localFileProvider.invalidateCache(_state.value.currentPath)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(showNewTxtDialog = false) }
                    refresh()
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "createNewTxt failed", e)
                withContext(Dispatchers.Main) {
                    _state.update { it.copy(error = e.message) }
                }
            }
        }
    }

    fun clearError() {
        FoldLogger.d(TAG, "clearError")
        _state.update { it.copy(error = null) }
    }

    fun saveScrollPosition(index: Int, offset: Int) {
        FoldLogger.v(TAG, "saveScrollPosition: path=${_state.value.currentPath}, index=$index, offset=$offset")
        scrollPositions[_state.value.currentPath] = Pair(index, offset)
    }

    fun getSavedScrollPosition(): Pair<Int, Int> {
        val pos = scrollPositions[_state.value.currentPath] ?: Pair(0, 0)
        FoldLogger.v(TAG, "getSavedScrollPosition: path=${_state.value.currentPath}, index=${pos.first}, offset=${pos.second}")
        return pos
    }

    fun toggleHiddenFiles() {
        val oldValue = _state.value.showHiddenFiles
        val newValue = !oldValue
        FoldLogger.i(TAG, "toggleHiddenFiles: $oldValue -> $newValue")
        prefs.edit().putBoolean("show_hidden", newValue).apply()
        _state.update { it.copy(showHiddenFiles = newValue) }
        refresh()
    }

    fun toggleViewMode() {
        val old = _state.value.viewMode
        val new = if (old == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST
        FoldLogger.i(TAG, "toggleViewMode: $old -> $new")
        prefs.edit().putString("view_mode", new.prefValue).apply()
        _state.update { it.copy(viewMode = new) }
    }

    fun showHttpDialog() {
        FoldLogger.d(TAG, "showHttpDialog")
        _state.update { it.copy(showHttpDialog = true) }
    }

    fun hideHttpDialog() {
        FoldLogger.d(TAG, "hideHttpDialog")
        _state.update { it.copy(showHttpDialog = false) }
    }

    fun refreshShizukuStatus() {
        FoldLogger.d(TAG, "refreshShizukuStatus: available=${ShizukuHelper.available.value}, granted=${ShizukuHelper.granted.value}")
        _state.update { it.copy(
            shizukuAvailable = ShizukuHelper.available.value,
            shizukuGranted = ShizukuHelper.granted.value
        ) }
        // 如果当前在受限目录，等服务绑定后刷新列表
        if (ShizukuHelper.needsShizuku(_state.value.currentPath)) {
            viewModelScope.launch {
                kotlinx.coroutines.delay(500) // 等 Shizuku 服务绑定
                refresh()
            }
        }
    }

    // ===== 排序 =====

    fun showSortDialog() {
        FoldLogger.d(TAG, "showSortDialog")
        _state.update { it.copy(showSortDialog = true) }
    }

    fun hideSortDialog() {
        FoldLogger.d(TAG, "hideSortDialog")
        _state.update { it.copy(showSortDialog = false) }
    }

    fun setSortMode(mode: SortMode, folderOnly: Boolean) {
        val path = _state.value.currentPath
        val oldMode = _state.value.sortMode
        FoldLogger.i(TAG, "setSortMode: oldMode=$oldMode, newMode=$mode, folderOnly=$folderOnly, path=$path")
        if (folderOnly) {
            folderSortOverrides[path] = mode
            // 持久化文件夹级排序
            prefs.edit().putString("folder_sort_$path", mode.prefValue).apply()
        } else {
            folderSortOverrides.remove(path)
            prefs.edit().remove("folder_sort_$path").putString("global_sort", mode.prefValue).apply()
        }
        _state.update { it.copy(sortMode = mode, sortFolderOnly = folderOnly, showSortDialog = false) }
        // 重新排序当前列表
        val sorted = sortFiles(_files.value, mode)
        _files.value = sorted
        _state.update { it.copy(files = sorted) }
    }

    fun setSortFolderOnly(only: Boolean) {
        FoldLogger.d(TAG, "setSortFolderOnly: only=$only")
        _state.update { it.copy(sortFolderOnly = only) }
    }

    private fun sortFiles(files: List<FileItem>, mode: SortMode): List<FileItem> {
        val foldersFirst = compareByDescending<FileItem> { it.isDirectory }
        return when (mode) {
            SortMode.NAME_ASC -> files.sortedWith(foldersFirst.thenBy { it.name.lowercase() })
            SortMode.NAME_DESC -> files.sortedWith(foldersFirst.thenByDescending { it.name.lowercase() })
            SortMode.DATE_NEWEST -> files.sortedWith(foldersFirst.thenByDescending { it.lastModifiedTimestamp })
            SortMode.DATE_OLDEST -> files.sortedWith(foldersFirst.thenBy { it.lastModifiedTimestamp })
            SortMode.SIZE_LARGEST -> files.sortedWith(foldersFirst.thenByDescending { it.size })
            SortMode.SIZE_SMALLEST -> files.sortedWith(foldersFirst.thenBy { it.size })
            SortMode.TYPE_GROUP -> files.sortedWith(
                compareByDescending<FileItem> { it.isDirectory }
                    .thenBy { if (it.isDirectory) "" else it.extension }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    fun toggleServer() {
        FoldLogger.d(TAG, "toggleServer: isRunning=${_state.value.isServerRunning}")
        if (_state.value.isServerRunning) stopServer() else startServer()
    }

    fun startServer() {
        FoldLogger.i(TAG, "startServer")
        val url = AppContainer.startHttpServer()
        if (url != null) {
            FoldLogger.i(TAG, "startServer: success, url=$url")
            _state.update { it.copy(isServerRunning = true, serverUrl = url, showHttpDialog = true) }
        } else {
            FoldLogger.e(TAG, "startServer: failed")
            _state.update { it.copy(error = app.getString(R.string.http_start_failed)) }
        }
    }

    fun stopServer() {
        FoldLogger.i(TAG, "stopServer")
        AppContainer.stopHttpServer()
        _state.update { it.copy(isServerRunning = false, serverUrl = "", showHttpDialog = false) }
    }

    fun copyServerUrl() {
        FoldLogger.d(TAG, "copyServerUrl: url=${_state.value.serverUrl}")
        _state.update { it.copy(copiedPath = _state.value.serverUrl) }
    }

    // ===== 搜索 =====

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
        if (query.isEmpty()) {
            _state.update { it.copy(isSearchActive = false, searchResults = emptyList()) }
            _files.value = _state.value.files
            return
        }
        _state.update { it.copy(isSearchActive = true) }
        performSearch(query)
    }

    fun clearSearch() {
        _state.update { it.copy(searchQuery = "", isSearchActive = false, searchResults = emptyList()) }
        _files.value = _state.value.files
    }

    fun toggleSearchScope() {
        val newValue = !_state.value.searchInCurrentFolder
        _state.update { it.copy(searchInCurrentFolder = newValue) }
        // 重新执行当前搜索
        val query = _state.value.searchQuery
        if (query.isNotEmpty()) {
            performSearch(query)
        }
    }

    private fun performSearch(query: String) {
        viewModelScope.launch(Dispatchers.IO) {
            // 如果索引为空，先构建（最多等 10 秒）
            var attempts = 0
            while (fileIndexer.count() == 0 && attempts < 20) {
                if (attempts == 0) {
                    FoldLogger.i(TAG, "Search: index empty, rebuilding...")
                    fileIndexer.rebuildIndex()
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }
            val searchResults = if (_state.value.searchInCurrentFolder) {
                fileIndexer.searchInPath(query, _state.value.currentPath)
            } else {
                fileIndexer.search(query)
            }
            val results = searchResults.map { entity ->
                FileItem(
                    name = entity.name,
                    path = entity.path,
                    isDirectory = entity.isDirectory,
                    size = entity.size,
                    lastModifiedTimestamp = entity.lastModified,
                    extension = entity.extension
                )
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(searchResults = results) }
                _files.value = results
            }
        }
    }

    fun rebuildIndex() {
        viewModelScope.launch(Dispatchers.IO) {
            fileIndexer.rebuildIndex()
        }
    }

    // ===== 多选 =====

    fun toggleSelectionMode() {
        val newMode = !_state.value.selectionMode
        _state.update { it.copy(selectionMode = newMode, selectedFiles = emptySet()) }
    }

    fun toggleFileSelection(file: FileItem) {
        val current = _state.value.selectedFiles.toMutableSet()
        if (current.contains(file.path)) {
            current.remove(file.path)
        } else {
            current.add(file.path)
        }
        _state.update { it.copy(selectedFiles = current) }
        if (current.isEmpty() && _state.value.selectionMode) {
            _state.update { it.copy(selectionMode = false) }
        }
    }

    fun selectAll() {
        val allPaths = _files.value.map { it.path }.toSet()
        _state.update { it.copy(selectedFiles = allPaths) }
    }

    fun batchDelete() {
        val selected = _state.value.selectedFiles
        if (selected.isEmpty()) return
        FoldLogger.i(TAG, "batchDelete: ${selected.size} files")
        viewModelScope.launch(Dispatchers.IO) {
            var successCount = 0
            for (path in selected) {
                try {
                    moveToTrash(File(path))
                    successCount++
                } catch (e: Exception) {
                    FoldLogger.e(TAG, "batchDelete: failed for $path", e)
                }
            }
            withContext(Dispatchers.Main) {
                _state.update { it.copy(selectionMode = false, selectedFiles = emptySet()) }
                refresh()
                if (successCount < selected.size) {
                    _state.update { it.copy(error = app.getString(R.string.result_delete_done, successCount, selected.size)) }
                }
            }
        }
    }

    fun batchMove() {
        val selected = _state.value.selectedFiles
        if (selected.isEmpty()) return
        val files = _files.value.filter { it.path in selected }
        if (files.isNotEmpty()) {
            _state.update { it.copy(clipboardFiles = files, clipboardMove = true, selectionMode = false, selectedFiles = emptySet()) }
        }
    }

    fun batchCopy() {
        val selected = _state.value.selectedFiles
        if (selected.isEmpty()) return
        val files = _files.value.filter { it.path in selected }
        if (files.isNotEmpty()) {
            _state.update { it.copy(clipboardFiles = files, clipboardMove = false, selectionMode = false, selectedFiles = emptySet()) }
        }
    }

    private var batchCompressTargetDir: File? = null
    private var batchCompressFiles: List<File> = emptyList()

    fun prepareBatchCompress() {
        val selected = _state.value.selectedFiles
        if (selected.isEmpty()) return
        val files = _files.value.filter { it.path in selected }
        if (files.isEmpty()) return
        batchCompressFiles = files.map { File(it.path) }
        batchCompressTargetDir = File(_state.value.currentPath)
        _state.update { it.copy(showBatchCompressDialog = true, selectionMode = false, selectedFiles = emptySet()) }
    }

    fun executeBatchCompress(format: String) {
        val files = batchCompressFiles
        val baseDir = batchCompressTargetDir ?: return
        if (files.isEmpty()) return
        _state.update { it.copy(showBatchCompressDialog = false) }
        val ext = when (format) {
            "targz" -> "tar.gz"
            "7z" -> "7z"
            else -> "zip"
        }
        val dirName = baseDir.name.ifEmpty { "archive" }
        val outputPath = File(baseDir, "$dirName.$ext").absolutePath
        compressCancelled = false
        compressOutputPath = outputPath
        compressJob = viewModelScope.launch {
            FoldLogger.i(TAG, "batchCompress: ${files.size} files, format=$format")
            _state.update { it.copy(compressProgress = 0f, compressFileName = app.getString(R.string.result_file_count, files.size)) }
            val onProgress: (String, Int, Int) -> Unit = { name, current, total ->
                if (!compressCancelled) {
                    val progress = if (total > 0) current.toFloat() / total else 0f
                    _state.update { it.copy(compressProgress = progress, compressFileName = name) }
                }
            }
            try {
                val result = com.example.fold.data.archive.ArchiveHelper.compressFiles(files, baseDir, outputPath, format, onProgress)
                if (result.isSuccess) {
                    FoldLogger.i(TAG, "batchCompress: success -> $outputPath")
                    refresh()
                } else if (result.exceptionOrNull() !is kotlinx.coroutines.CancellationException) {
                    FoldLogger.e(TAG, "batchCompress: failed", result.exceptionOrNull())
                    _state.update { it.copy(error = app.getString(R.string.result_compress_failed, result.exceptionOrNull()?.message ?: "")) }
                }
            } finally {
                compressJob = null
                compressOutputPath = null
                _state.update { it.copy(compressProgress = -1f, compressFileName = "") }
            }
        }
        batchCompressFiles = emptyList()
        batchCompressTargetDir = null
    }

    fun dismissBatchCompressDialog() {
        _state.update { it.copy(showBatchCompressDialog = false) }
        batchCompressFiles = emptyList()
        batchCompressTargetDir = null
    }

    // ===== 回收站 =====

    fun getTrashDir(): File {
        val dir = File(app.filesDir, "trash")
        dir.mkdirs()
        return dir
    }

    fun getTrashDirPath(): String = getTrashDir().absolutePath

    fun getTrashOriginalPath(trashPath: String): String? {
        val meta = loadTrashMeta()
        return meta[trashPath]
    }

    fun toggleTrashEnabled() {
        val newValue = !_trashEnabled.value
        _trashEnabled.value = newValue
        prefs.edit().putBoolean("trash_enabled", newValue).apply()
    }

    private fun getTrashMetaFile(): File = File(getTrashDir(), "metadata.json")

    private fun loadTrashMeta(): MutableMap<String, String> {
        try {
            val metaFile = getTrashMetaFile()
            if (metaFile.exists()) {
                val json = metaFile.readText()
                val map = mutableMapOf<String, String>()
                json.lines().filter { line -> line.contains("=") }.forEach { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) map[parts[0]] = parts[1]
                }
                return map
            }
        } catch (_: Exception) {}
        return mutableMapOf()
    }

    private fun saveTrashMeta(meta: Map<String, String>) {
        val metaFile = getTrashMetaFile()
        metaFile.writeText(meta.entries.joinToString("\n") { "${it.key}=${it.value}" })
    }

    private fun moveToTrash(file: File) {
        if (!file.exists()) return
        val trashDir = getTrashDir()
        val trashFile = File(trashDir, "${System.currentTimeMillis()}_${file.name}")
        file.renameTo(trashFile)
        val meta = loadTrashMeta()
        meta[trashFile.absolutePath] = file.absolutePath
        saveTrashMeta(meta)
    }

    fun restoreFromTrash(trashedName: String, originalPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trashFile = File(getTrashDir(), trashedName)
                val destFile = File(originalPath)
                destFile.parentFile?.mkdirs()
                trashFile.renameTo(destFile)
                val meta = loadTrashMeta()
                meta.remove(trashFile.absolutePath)
                saveTrashMeta(meta)
                withContext(Dispatchers.Main) {
                    refresh()
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "restoreFromTrash: failed", e)
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            getTrashDir().deleteRecursively()
            getTrashDir().mkdirs()
            saveTrashMeta(emptyMap())
        }
    }

    fun cleanExpiredTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            val now = System.currentTimeMillis()
            val meta = loadTrashMeta()
            val toRemove = mutableListOf<String>()
            meta.forEach { (trashPath, _) ->
                val file = File(trashPath)
                if (!file.exists() || (now - file.lastModified() > thirtyDaysMs)) {
                    file.delete()
                    toRemove.add(trashPath)
                }
            }
            toRemove.forEach { meta.remove(it) }
            saveTrashMeta(meta)
        }
    }

    override fun onCleared() {
        FoldLogger.i(TAG, "onCleared")
        stopObserver()
        super.onCleared()
        ShizukuHelper.onServiceReady = null
        AppContainer.stopHttpServer()
    }
}
