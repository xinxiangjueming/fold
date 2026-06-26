package com.example.fold.ui.filebrowser

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnNextLayout
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fold.R
import com.example.fold.data.model.FileItem
import com.example.fold.ui.theme.*
import com.example.fold.ui.theme.LocalDarkMode
import com.example.fold.ui.theme.toggleDarkMode
import com.example.fold.util.FoldLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// 共享的日期格式器，ThreadLocal 保证线程安全
private const val TAG = "FoldUI"

private val dateFormatCache = object : ThreadLocal<SimpleDateFormat>() {
    override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
}
internal fun formatTimestamp(ts: Long): String = dateFormatCache.get()!!.format(Date(ts))

// ==================== 文件浏览器主界面 ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    onFileClick: (String) -> Unit,
    onImageClick: (String) -> Unit,
    onArchiveClick: (String) -> Unit,
    onVideoClick: (String) -> Unit = {},
    onAudioClick: (String) -> Unit = {},
    onNavigateToHiddenApps: () -> Unit = {},
    viewModel: FileBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val calculatorMode by viewModel.calculatorMode.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // 每次进入页面时重新检查 Shizuku 状态
    LaunchedEffect(Unit) {
        FoldLogger.i(TAG, "FileBrowserScreen: composed, path=${state.currentPath}, files=${files.size}")
        com.example.fold.util.ShizukuHelper.recheck()
    }

    // 首次文件列表加载完成
    LaunchedEffect(files.isNotEmpty()) {
        if (files.isNotEmpty()) {
            FoldLogger.i(TAG, "FileBrowserScreen: first files loaded, count=${files.size}, path=${state.currentPath}")
        }
    }

    // 长按菜单状态（屏幕级，不在每个 item 里）
    var menuTargetFile by remember { mutableStateOf<FileItem?>(null) }
    // 更多选项菜单
    var showMoreMenu by remember { mutableStateOf(false) }
    // 压缩格式选择
    var compressTargetFile by remember { mutableStateOf<FileItem?>(null) }

    // 返回手势处理：非根目录→返回上级，根目录→双击退出
    var backPressedTime by remember { mutableStateOf(0L) }
    BackHandler {
        val isRoot = state.currentPath == getDisplayRoot(state)
        FoldLogger.d(TAG, "BackHandler: isRoot=$isRoot, path=${state.currentPath}")
        if (!isRoot) {
            FoldLogger.d(TAG, "BackHandler: navigating up")
            viewModel.navigateUp()
        } else {
            val now = System.currentTimeMillis()
            if (now - backPressedTime < 2000) {
                FoldLogger.i(TAG, "BackHandler: double-tap exit")
                (context as? android.app.Activity)?.finish()
            } else {
                backPressedTime = now
                Toast.makeText(context, context.getString(R.string.press_again_to_exit), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 不用 Scaffold，避免 contentWindowInsets 每帧重算布局
    FoldLogger.d(TAG, "FileBrowserScreen recompose: darkModeState=${com.example.fold.ui.theme.darkModeState.intValue}, background=${MaterialTheme.colorScheme.background}, onSurface=${MaterialTheme.colorScheme.onSurface}, surface=${MaterialTheme.colorScheme.surface}")
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
        ) {
            // TopAppBar 自己处理状态栏 insets
            TopAppBar(
                title = {
                    Text(
                        text = state.currentPath.ifEmpty { stringResource(R.string.app_name) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    val canNavigateUp = state.currentPath != getDisplayRoot(state)
                    if (canNavigateUp) {
                        IconButton(onClick = { viewModel.navigateUp() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    // 粘贴按钮（剪贴板有内容时显示）
                    state.clipboardFile?.let { clip ->
                        val label = if (state.clipboardMove)
                            stringResource(R.string.action_move_here)
                        else
                            stringResource(R.string.action_paste_here)
                        TextButton(onClick = { viewModel.pasteFile() }) {
                            Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(label, style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { viewModel.clearClipboard() }) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    }
                    IconButton(onClick = { viewModel.toggleServer() }) {
                        Icon(
                            Icons.Filled.Cloud,
                            contentDescription = stringResource(R.string.http_server),
                            tint = if (state.isServerRunning) MiuixSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    Box {
                        IconButton(onClick = { showMoreMenu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sort_title)) },
                                onClick = { showMoreMenu = false; viewModel.showSortDialog() },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) }
                            )
                            val darkMode = LocalDarkMode.current
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(
                                        when (darkMode) {
                                            1 -> R.string.dark_mode_on
                                            2 -> R.string.dark_mode_off
                                            else -> R.string.dark_mode_auto
                                        }
                                    ))
                                },
                                onClick = { toggleDarkMode(context) },
                                leadingIcon = {
                                    Icon(
                                        when (darkMode) {
                                            1 -> Icons.Filled.DarkMode
                                            2 -> Icons.Filled.LightMode
                                            else -> Icons.Filled.SettingsBrightness
                                        },
                                        contentDescription = null
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (state.showHiddenFiles) R.string.hide_hidden_files else R.string.show_hidden_files)) },
                                onClick = {
                                    FoldLogger.d(TAG, "menu: toggleHiddenFiles clicked, current=${state.showHiddenFiles}")
                                    showMoreMenu = false
                                    viewModel.toggleHiddenFiles()
                                },
                                leadingIcon = { Icon(if (state.showHiddenFiles) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (calculatorMode) R.string.calculator_mode_off else R.string.calculator_mode_on)) },
                                onClick = {
                                    FoldLogger.d(TAG, "menu: toggleCalculatorMode clicked, current=$calculatorMode")
                                    showMoreMenu = false
                                    viewModel.toggleCalculatorMode()
                                },
                                leadingIcon = { Icon(Icons.Filled.Calculate, contentDescription = null) }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hidden_apps)) },
                                onClick = {
                                    showMoreMenu = false
                                    onNavigateToHiddenApps()
                                },
                                leadingIcon = { Icon(Icons.Filled.HideSource, contentDescription = null) }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // Shizuku 权限提示（受限目录且未授权）
            if (state.isRestrictedPath && !state.shizukuGranted) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp),
                    onClick = {
                        com.example.fold.util.ShizukuHelper.recheck()
                        if (com.example.fold.util.ShizukuHelper.available.value) {
                            com.example.fold.util.ShizukuHelper.requestPermission()
                        } else {
                            Toast.makeText(context, "请先启动 Shizuku，然后切回此应用", Toast.LENGTH_LONG).show()
                        }
                    }
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.shizuku_required),
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            AnimatedVisibility(visible = state.error != null, enter = fadeIn(), exit = fadeOut()) {
                state.error?.let { error ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(8.dp))
                            Text(error, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                            IconButton(onClick = { viewModel.clearError() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                            }
                        }
                    }
                }
            }

            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (files.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.empty_folder), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                // RecyclerView: View 复用，零 item 组合开销
                val isDark = when (com.example.fold.ui.theme.darkModeState.intValue) {
                    1 -> true
                    2 -> false
                    else -> androidx.compose.foundation.isSystemInDarkTheme()
                }
                FoldLogger.d(TAG, "RecyclerView isDark=$isDark, darkModeState=${com.example.fold.ui.theme.darkModeState.intValue}")
                val adapter = remember {
                    FileListAdapter(
                        onClick = { file ->
                            FoldLogger.d(TAG, "onFileClick: name=${file.name}, isDir=${file.isDirectory}")
                            when {
                                file.isDirectory -> viewModel.onFileClick(file)
                                file.extension.lowercase() in setOf("jpg","jpeg","png","gif","webp","bmp","svg") -> onImageClick(file.path)
                                file.extension.lowercase() in setOf("mp4","mkv","avi","mov","flv","wmv","webm","3gp","ts") -> onVideoClick(file.path)
                                file.extension.lowercase() in setOf("mp3","wav","flac","aac","ogg","wma","m4a","opus","ape") -> onAudioClick(file.path)
                                com.example.fold.data.archive.ArchiveHelper.isArchive(file.name) -> onArchiveClick(file.path)
                                file.isReadableFile -> onFileClick(file.path)
                                file.extension.lowercase() == "apk" -> {
                                    try {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.provider", java.io.File(file.path)
                                        )
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "application/vnd.android.package-archive")
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        FoldLogger.e(TAG, "Failed to open APK: ${e.message}")
                                        android.widget.Toast.makeText(context, "无法安装: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                                else -> {
                                    // 尝试用系统默认程序打开
                                    try {
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.provider", java.io.File(file.path)
                                        )
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, "*/*")
                                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(intent)
                                    } catch (_: Exception) {}
                                }
                            }
                        },
                        onLongPress = { file ->
                            FoldLogger.d(TAG, "onLongPress: name=${file.name}")
                            menuTargetFile = file
                        },
                        isDark = isDark
                    )
                }
                // files 变化时提交新列表
                LaunchedEffect(files) {
                    FoldLogger.d(TAG, "submitList: ${files.size} items")
                    adapter.submitList(files)
                }
                // 深浅色变化时更新 adapter 颜色并刷新所有可见 item
                LaunchedEffect(isDark) {
                    FoldLogger.d(TAG, "isDark changed: $isDark, updating adapter colors")
                    adapter.isDark = isDark
                    adapter.notifyDataSetChanged()
                }
                AndroidView(
                    factory = { ctx ->
                        androidx.recyclerview.widget.RecyclerView(ctx).apply {
                            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                            this.adapter = adapter
                            clipToPadding = false
                            // 关闭无障碍，避免每帧发送 28KB 大事务
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            // 底部 padding 避开导航栏
                            setPadding(0, 0, 0, ctx.resources.displayMetrics.heightPixels.let {
                                val navBarId = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                                if (navBarId > 0) ctx.resources.getDimensionPixelSize(navBarId) else 0
                            })
                            // 监听滚动，保存位置
                            addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
                                override fun onScrollStateChanged(rv: androidx.recyclerview.widget.RecyclerView, newState: Int) {
                                    if (newState == androidx.recyclerview.widget.RecyclerView.SCROLL_STATE_IDLE) {
                                        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
                                        lm?.let {
                                            val pos = it.findFirstVisibleItemPosition()
                                            val offset = it.findViewByPosition(pos)?.top ?: 0
                                            viewModel.saveScrollPosition(pos, offset)
                                        }
                                    }
                                }
                            })
                        }
                    },
                    update = { rv ->
                        // 检测 RecyclerView 是否被不必要地 recompose
                        FoldLogger.d(TAG, "AndroidView update: files=${files.size}, isDark=$isDark")
                        // 文件列表更新后恢复滚动位置
                        rv.doOnNextLayout {
                            val (index, offset) = viewModel.getSavedScrollPosition()
                            if (index > 0 || offset > 0) {
                                (rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                                    ?.scrollToPositionWithOffset(index, offset)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 重命名对话框
        state.renamingFile?.let { file ->
            RenameDialog(currentName = file.name, onConfirm = { viewModel.confirmRename(it) }, onDismiss = { viewModel.cancelRename() })
        }

        // 排序对话框
        if (state.showSortDialog) {
            SortDialog(
                currentMode = state.sortMode,
                folderOnly = state.sortFolderOnly,
                onConfirm = { mode, folderOnly -> viewModel.setSortMode(mode, folderOnly) },
                onFolderOnlyChange = { viewModel.setSortFolderOnly(it) },
                onDismiss = { viewModel.hideSortDialog() }
            )
        }

        // 复制路径
        state.copiedPath?.let { path ->
            @Suppress("DEPRECATION")
            val clipboardManager = LocalClipboardManager.current
            LaunchedEffect(path) {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(path))
                viewModel.clearCopiedPath()
            }
        }

        // 分享
        state.sharingFile?.let { file ->
            LaunchedEffect(file) {
                try {
                    val fileObj = java.io.File(file.path)
                    val uri = androidx.core.content.FileProvider.getUriForFile(
                        context, "${context.packageName}.provider", fileObj
                    )
                    val mimeType = android.webkit.MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = mimeType
                        putExtra(android.content.Intent.EXTRA_STREAM, uri)
                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(android.content.Intent.createChooser(intent, file.name))
                } catch (e: Exception) {
                    FoldLogger.e(TAG, "share failed: ${e.message}")
                }
                viewModel.clearSharingFile()
            }
        }

        // 属性对话框
        state.propertiesFile?.let { file ->
            PropertiesDialog(file = file, onDismiss = { viewModel.hideProperties() })
        }

        // HTTP 服务器弹窗
        if (state.showHttpDialog) {
            HttpServerDialog(
                serverUrl = state.serverUrl,
                onStop = { viewModel.stopServer() },
                onDismiss = { viewModel.hideHttpDialog() }
            )
        }

        // 压缩格式选择弹窗
        compressTargetFile?.let { file ->
            AlertDialog(
                onDismissRequest = { compressTargetFile = null },
                title = { Text(stringResource(R.string.compress_format_title), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                text = {
                    Column {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_compress_zip)) },
                            onClick = {
                                compressTargetFile = null
                                viewModel.compressArchive(file, "zip")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_compress_targz)) },
                            onClick = {
                                compressTargetFile = null
                                viewModel.compressArchive(file, "targz")
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_compress_7z)) },
                            onClick = {
                                compressTargetFile = null
                                viewModel.compressArchive(file, "7z")
                            }
                        )
                    }
                },
                confirmButton = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TextButton(onClick = { compressTargetFile = null }) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
                shape = RoundedCornerShape(16.dp)
            )
        }

        // 长按操作菜单（屏幕级，只创建一个实例）
        if (menuTargetFile != null) {
            val file = menuTargetFile!!
            androidx.compose.ui.window.Popup(
                alignment = androidx.compose.ui.Alignment.Center,
                onDismissRequest = { menuTargetFile = null }
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.6f),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_rename)) },
                            onClick = { menuTargetFile = null; viewModel.renameFile(file) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy)) },
                            onClick = { menuTargetFile = null; viewModel.copyFile(file) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_move)) },
                            onClick = { menuTargetFile = null; viewModel.moveFile(file) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_copy_path)) },
                            onClick = { menuTargetFile = null; viewModel.copyPath(file) },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) }
                        )
                        if (!file.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_share)) },
                                onClick = { menuTargetFile = null; viewModel.shareFile(file) },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) }
                            )
                        }
                        if (com.example.fold.data.archive.ArchiveHelper.isArchive(file.name)) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_extract)) },
                                onClick = { menuTargetFile = null; viewModel.extractArchive(file) },
                                leadingIcon = { Icon(Icons.Filled.FolderZip, contentDescription = null) }
                            )
                        }
                        if (file.isDirectory) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_compress)) },
                                onClick = { menuTargetFile = null; compressTargetFile = file },
                                leadingIcon = { Icon(Icons.Filled.Archive, contentDescription = null) }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_properties)) },
                            onClick = { menuTargetFile = null; viewModel.showProperties(file) },
                            leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_delete)) },
                            onClick = { menuTargetFile = null; viewModel.deleteFile(file) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                        )
                    }
                }
            }
        }
    }
}

// ==================== 路径栏 ====================

@Composable
private fun PathBar(currentPath: String, onNavigateUp: () -> Unit, canNavigateUp: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp))
                    .then(if (canNavigateUp) Modifier.clickable(onClick = onNavigateUp) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.ArrowUpward, contentDescription = null, modifier = Modifier.size(20.dp),
                    tint = if (canNavigateUp) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f))
            }
            Spacer(Modifier.width(8.dp))
            Text(text = currentPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
}

// ==================== 对话框 ====================

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf(currentName) }
    FoldLogger.d(TAG, "RenameDialog: currentName=$currentName")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { if (newName.isNotBlank()) onConfirm(newName) }) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PropertiesDialog(file: FileItem, onDismiss: () -> Unit) {
    val modifiedText = remember(file.lastModifiedTimestamp) {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(file.lastModifiedTimestamp)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_properties), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow(stringResource(R.string.prop_name), file.name)
                PropertyRow(stringResource(R.string.prop_path), file.path)
                if (!file.isDirectory) PropertyRow(stringResource(R.string.prop_size), formatFileSize(file.size))
                PropertyRow(stringResource(R.string.prop_type), if (file.isDirectory) stringResource(R.string.file_type_folder) else file.extension.uppercase())
                PropertyRow(stringResource(R.string.prop_modified), modifiedText)
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SortDialog(
    currentMode: SortMode,
    folderOnly: Boolean,
    onConfirm: (SortMode, Boolean) -> Unit,
    onFolderOnlyChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentMode) }
    var localFolderOnly by remember(folderOnly) { mutableStateOf(folderOnly) }

    val options = listOf(
        SortMode.NAME_ASC to stringResource(R.string.sort_name_asc),
        SortMode.NAME_DESC to stringResource(R.string.sort_name_desc),
        SortMode.DATE_NEWEST to stringResource(R.string.sort_date_newest),
        SortMode.DATE_OLDEST to stringResource(R.string.sort_date_oldest),
        SortMode.SIZE_LARGEST to stringResource(R.string.sort_size_largest),
        SortMode.SIZE_SMALLEST to stringResource(R.string.sort_size_smallest),
        SortMode.TYPE_GROUP to stringResource(R.string.sort_type_group),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sort_title), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (mode, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedMode = mode }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = selectedMode == mode, onClick = { selectedMode = mode })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { localFolderOnly = !localFolderOnly },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = localFolderOnly, onCheckedChange = { localFolderOnly = it })
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.sort_folder_only), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.sort_folder_only_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    FoldLogger.d(TAG, "SortDialog: confirmed, selectedMode=$selectedMode, folderOnly=$localFolderOnly")
                    onFolderOnlyChange(localFolderOnly); onConfirm(selectedMode, localFolderOnly)
                }) { Text(stringResource(R.string.action_save)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
private fun HttpServerDialog(serverUrl: String, onStop: () -> Unit, onDismiss: () -> Unit) {
    @Suppress("DEPRECATION")
    val clipboardManager = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.http_server), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.http_server_running), style = MaterialTheme.typography.bodyMedium)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = serverUrl,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.http_server_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(serverUrl))
                }) { Text(stringResource(R.string.action_copy_path)) }
                TextButton(onClick = {
                    FoldLogger.i(TAG, "HttpServerDialog: stop server")
                    onStop()
                }) {
                    Text(stringResource(R.string.http_server_stop), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

// ==================== 工具函数 ====================

internal fun getFileIcon(file: FileItem) = when {
    file.isDirectory -> Icons.Filled.Folder
    file.extension == "txt" -> Icons.Filled.Description
    file.extension == "epub" -> Icons.AutoMirrored.Filled.MenuBook
    file.extension == "pdf" -> Icons.Filled.PictureAsPdf
    file.extension.lowercase() in setOf("mp4","mkv","avi","mov","flv","wmv","webm","3gp","ts") -> Icons.Filled.PlayCircle
    file.extension.lowercase() in setOf("mp3","wav","flac","aac","ogg","wma","m4a","opus","ape") -> Icons.Filled.MusicNote
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

internal fun getFileIconTint(file: FileItem) = when {
    file.isDirectory -> FileTypeFolder
    file.extension == "txt" -> FileTypeTxt
    file.extension == "epub" -> FileTypeEpub
    file.extension == "pdf" -> FileTypePdf
    file.extension.lowercase() in setOf("mp4","mkv","avi","mov","flv","wmv","webm","3gp","ts") -> FileTypeVideo
    file.extension.lowercase() in setOf("mp3","wav","flac","aac","ogg","wma","m4a","opus","ape") -> FileTypeAudio
    else -> MiuixTextSecondary
}

private fun getFileIconBackground(file: FileItem) = when {
    file.isDirectory -> FileTypeFolder.copy(alpha = 0.12f)
    file.extension == "txt" -> FileTypeTxt.copy(alpha = 0.12f)
    file.extension == "epub" -> FileTypeEpub.copy(alpha = 0.12f)
    file.extension == "pdf" -> FileTypePdf.copy(alpha = 0.12f)
    file.extension.lowercase() in setOf("mp4","mkv","avi","mov","flv","wmv","webm","3gp","ts") -> FileTypeVideo.copy(alpha = 0.12f)
    file.extension.lowercase() in setOf("mp3","wav","flac","aac","ogg","wma","m4a","opus","ape") -> FileTypeAudio.copy(alpha = 0.12f)
    else -> MiuixSurfaceVariant
}

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun getDisplayRoot(state: FileBrowserState): String = "/storage/emulated/0"
