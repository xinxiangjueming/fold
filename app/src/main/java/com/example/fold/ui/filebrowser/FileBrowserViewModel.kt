package com.example.fold.ui.filebrowser

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fold.AppContainer
import com.example.fold.R
import com.example.fold.data.model.FileItem
import com.example.fold.data.provider.LocalFileProvider
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper
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

data class FileBrowserState(
    val currentPath: String = "",
    val files: List<FileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pathHistory: List<String> = emptyList(),
    val renamingFile: FileItem? = null,
    val copiedPath: String? = null,
    val sharingFile: FileItem? = null,
    val propertiesFile: FileItem? = null,
    val isServerRunning: Boolean = false,
    val serverUrl: String = "",
    val showHttpDialog: Boolean = false,
    val showSortDialog: Boolean = false,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val sortFolderOnly: Boolean = false,
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val isRestrictedPath: Boolean = false,
    val showHiddenFiles: Boolean = false
)

private const val TAG = "FoldVM"

class FileBrowserViewModel : ViewModel() {

    private val app: Application = AppContainer.appContext as Application
    private val localFileProvider: LocalFileProvider = AppContainer.localFileProvider
    private val prefs = app.getSharedPreferences("file_sort", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(FileBrowserState())
    val state: StateFlow<FileBrowserState> = _state.asStateFlow()

    // 文件列表独立 StateFlow，避免 UI 状态变化触发 LazyColumn 重组
    private val _files = MutableStateFlow<List<FileItem>>(emptyList())
    val files: StateFlow<List<FileItem>> = _files.asStateFlow()

    // 文件夹级排序覆盖: path -> SortMode
    private val folderSortOverrides = mutableMapOf<String, SortMode>()

    // 滚动位置记忆：path -> Pair(index, offset)
    private val scrollPositions = mutableMapOf<String, Pair<Int, Int>>()

    init {
        FoldLogger.i(TAG, "init: starting FileBrowserViewModel")
        val globalMode = SortMode.fromPref(prefs.getString("global_sort", null))
        val showHidden = prefs.getBoolean("show_hidden", false)
        _state.update { it.copy(
            isServerRunning = AppContainer.isServerRunning,
            sortMode = globalMode,
            showHiddenFiles = showHidden,
            shizukuAvailable = ShizukuHelper.available.value,
            shizukuGranted = ShizukuHelper.granted.value
        ) }
        navigateTo(localFileProvider.getRootPath())

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

    fun navigateTo(path: String) {
        FoldLogger.d(TAG, "navigateTo: path=$path")
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
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
                } else if (isRestricted && !ShizukuHelper.granted.value) {
                    emptyList()
                } else {
                    localFileProvider.listFilesFast(path)
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

                // 后台加载文件大小和修改时间（低速设备异步填充）
                if (!isRestricted && sorted.isNotEmpty() && sorted.any { it.size == 0L && !it.isDirectory }) {
                    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        val full = localFileProvider.listFiles(path)
                        if (_state.value.currentPath == path) {
                            _files.value = full
                            FoldLogger.d(TAG, "navigateTo: metadata loaded, ${full.size} files")
                        }
                    }
                }
            } catch (e: Exception) {
                FoldLogger.e(TAG, "navigateTo: failed, path=$path", e)
                _state.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        FoldLogger.d(TAG, "navigateUp: current=$current")
        if (current == localFileProvider.getRootPath()) return
        navigateTo(current.substringBeforeLast('/').ifEmpty { "/" })
    }

    fun onFileClick(file: FileItem) {
        FoldLogger.d(TAG, "onFileClick: name=${file.name}, isDir=${file.isDirectory}, path=${file.path}")
        if (file.isDirectory) navigateTo(file.path)
    }

    fun deleteFile(file: FileItem) {
        FoldLogger.i(TAG, "deleteFile: name=${file.name}, path=${file.path}")
        viewModelScope.launch {
            val success = if (ShizukuHelper.needsShizuku(file.path) && ShizukuHelper.granted.value) {
                ShizukuHelper.deleteRestricted(file.path).isSuccess
            } else {
                localFileProvider.deleteFile(file.path)
            }
            if (success) {
                FoldLogger.i(TAG, "deleteFile: success, name=${file.name}")
                localFileProvider.invalidateCache(java.io.File(file.path).parent ?: "")
                val updated = _files.value.filter { it.path != file.path }
                _files.value = updated
                _state.update { s -> s.copy(files = updated) }
            } else {
                FoldLogger.w(TAG, "deleteFile: failed, name=${file.name}")
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
                _state.update { it.copy(error = "解压失败: ${result.exceptionOrNull()?.message}") }
            }
        }
    }

    fun compressZip(file: FileItem) {
        FoldLogger.i(TAG, "compressZip: ${file.name}")
        viewModelScope.launch {
            val outputPath = file.path + ".zip"
            val result = com.example.fold.data.archive.ArchiveHelper.compressZip(file.path, outputPath)
            if (result.isSuccess) {
                FoldLogger.i(TAG, "compressZip: success -> $outputPath")
                refresh()
            } else {
                FoldLogger.e(TAG, "compressZip: failed", result.exceptionOrNull())
                _state.update { it.copy(error = "压缩失败: ${result.exceptionOrNull()?.message}") }
            }
        }
    }

    fun refresh() {
        val path = _state.value.currentPath
        FoldLogger.d(TAG, "refresh: path=$path")
        localFileProvider.invalidateCache(path)
        navigateTo(path)
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

    override fun onCleared() {
        FoldLogger.i(TAG, "onCleared")
        super.onCleared()
        ShizukuHelper.onServiceReady = null
        AppContainer.stopHttpServer()
    }
}
