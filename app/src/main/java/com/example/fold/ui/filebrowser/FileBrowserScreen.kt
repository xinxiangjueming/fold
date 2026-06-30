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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import androidx.core.view.drawToBitmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Slider as MiuixSlider

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
    onNavigateToTrash: () -> Unit = {},
    viewModel: FileBrowserViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val files by viewModel.files.collectAsStateWithLifecycle()
    val calculatorMode by viewModel.calculatorMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current

    DisposableEffect(viewModel) {
        viewModel.onCalculatorModeChanged = { isCalc ->
            val activity = context as? com.example.fold.MainActivity
            if (activity != null) com.example.fold.MainActivity.updateActivityIntent(activity, isCalc)
        }
        onDispose { viewModel.onCalculatorModeChanged = null }
    }

    LaunchedEffect(Unit) {
        FoldLogger.i(TAG, "FileBrowserScreen: composed, path=${state.currentPath}, files=${files.size}")
        com.example.fold.util.ShizukuHelper.recheck()
    }

    LaunchedEffect(files.isNotEmpty()) {
        if (files.isNotEmpty()) {
            FoldLogger.i(TAG, "FileBrowserScreen: first files loaded, count=${files.size}, path=${state.currentPath}")
        }
    }

    var menuTargetFile by remember { mutableStateOf<FileItem?>(null) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var compressTargetFile by remember { mutableStateOf<FileItem?>(null) }

    var backPressedTime by remember { mutableStateOf(0L) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var isBackGestureActive by remember { mutableStateOf(false) }
    var backScreenshot by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var searchActive by remember { mutableStateOf(false) }
    var searchText by remember { mutableStateOf("") }

    androidx.activity.compose.PredictiveBackHandler(enabled = state.currentPath != getDisplayRoot(state) || searchActive || state.selectionMode) { backEvent ->
        try {
            backScreenshot = try { view.drawToBitmap() } catch (e: Exception) { null }
            backEvent.collect { event ->
                backProgress = event.progress
                isBackGestureActive = true
            }
            isBackGestureActive = false
            backProgress = 0f
            backScreenshot = null
            if (state.selectionMode) {
                viewModel.toggleSelectionMode()
            } else if (searchActive) {
                searchActive = false
                searchText = ""
                viewModel.clearSearch()
            } else {
                viewModel.navigateUp()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            isBackGestureActive = false
            backProgress = 0f
            backScreenshot = null
        }
    }

    val backScale = 1f - (backProgress * 0.08f)
    val backTranslationX = backProgress * 100f
    val backCornerRadius = backProgress * 24f

    Box(Modifier.fillMaxSize()) {
        backScreenshot?.let { bmp ->
            androidx.compose.foundation.Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .graphicsLayer {
                    scaleX = backScale
                    scaleY = backScale
                    translationX = backTranslationX
                    shadowElevation = backProgress * 20f
                }
                .clip(RoundedCornerShape(backCornerRadius.dp))
        ) {
            Column(Modifier.fillMaxSize()) {
                // TopAppBar
                TopAppBar(
                    title = {
                        if (searchActive) {
                            OutlinedTextField(
                                value = searchText,
                                onValueChange = {
                                    searchText = it
                                    viewModel.onSearchQueryChange(it)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.search_files)) },
                                trailingIcon = {
                                    Row {
                                        IconButton(onClick = { viewModel.toggleSearchScope() }) {
                                            Icon(
                                                if (state.searchInCurrentFolder) Icons.Filled.Folder else Icons.Filled.Public,
                                                contentDescription = null,
                                                tint = if (state.searchInCurrentFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (searchText.isNotEmpty()) {
                                            IconButton(onClick = {
                                                searchText = ""
                                                viewModel.clearSearch()
                                            }) {
                                                Icon(Icons.Filled.Close, contentDescription = null)
                                            }
                                        }
                                    }
                                },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    unfocusedBorderColor = Color.Transparent,
                                    focusedBorderColor = Color.Transparent
                                )
                            )
                        } else if (state.selectionMode) {
                            Text(
                                text = stringResource(R.string.selected_count, state.selectedFiles.size),
                                style = MaterialTheme.typography.titleMedium
                            )
                        } else {
                            Text(
                                text = state.currentPath.ifEmpty { stringResource(R.string.app_name) },
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    navigationIcon = {
                        if (searchActive) {
                            IconButton(onClick = {
                                searchActive = false
                                searchText = ""
                                viewModel.clearSearch()
                            }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                            }
                        } else if (state.selectionMode) {
                            IconButton(onClick = { viewModel.toggleSelectionMode() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel))
                            }
                        } else {
                            if (state.currentPath != getDisplayRoot(state)) {
                                IconButton(onClick = { viewModel.navigateUp() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                                }
                            }
                        }
                    },
                    actions = {
                        if (state.selectionMode) {
                            IconButton(onClick = { viewModel.selectAll() }) {
                                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.select_all))
                            }
                            IconButton(onClick = { viewModel.batchDelete() }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { viewModel.batchCopy() }) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                            }
                            IconButton(onClick = { viewModel.batchMove() }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = stringResource(R.string.action_move))
                            }
                            IconButton(onClick = { viewModel.prepareBatchCompress() }) {
                                Icon(Icons.Filled.Archive, contentDescription = stringResource(R.string.action_compress))
                            }
                        } else {
                            if (!searchActive) {
                                IconButton(onClick = { searchActive = true }) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_files))
                                }
                            }
                            if (state.clipboardFiles.isNotEmpty()) {
                                val label = if (state.clipboardMove)
                                    stringResource(R.string.action_move_here)
                                else
                                    stringResource(R.string.action_paste_here)
                                TextButton(onClick = { viewModel.pasteFile() }) {
                                    Icon(Icons.Filled.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("$label (${state.clipboardFiles.size})", style = MaterialTheme.typography.labelMedium)
                                }
                                IconButton(onClick = { viewModel.clearClipboard() }) {
                                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
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
                                        onClick = { showMoreMenu = false; viewModel.toggleHiddenFiles() },
                                        leadingIcon = { Icon(if (state.showHiddenFiles) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (calculatorMode) R.string.calculator_mode_off else R.string.calculator_mode_on)) },
                                        onClick = { showMoreMenu = false; viewModel.toggleCalculatorMode() },
                                        leadingIcon = { Icon(Icons.Filled.Calculate, contentDescription = null) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.hidden_apps)) },
                                        onClick = { showMoreMenu = false; onNavigateToHiddenApps() },
                                        leadingIcon = { Icon(Icons.Filled.HideSource, contentDescription = null) }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.http_server)) },
                                        onClick = {
                                            showMoreMenu = false
                                            if (!state.isServerRunning) {
                                                viewModel.startServer()
                                            } else {
                                                viewModel.showHttpDialog()
                                            }
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Filled.Cloud,
                                                contentDescription = null,
                                                tint = if (state.isServerRunning) MiuixSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.trash_title)) },
                                        onClick = { showMoreMenu = false; onNavigateToTrash() },
                                        leadingIcon = { Icon(Icons.Filled.DeleteSweep, contentDescription = null) }
                                    )
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )

                // Shizuku 权限提示
                if (state.isRestrictedPath && !state.shizukuGranted && !state.rootAvailable) {
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
                    val isDark = when (darkModeState.intValue) {
                        1 -> true
                        2 -> false
                        else -> androidx.compose.foundation.isSystemInDarkTheme()
                    }
                    val adapter = remember {
                        FileListAdapter(
                            onClick = { file ->
                                when {
                                    file.isDirectory -> {
                                        com.example.fold.ui.common.PredictiveBackManager.captureCurrentScreen(view)
                                        viewModel.onFileClick(file)
                                    }
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
                                            Toast.makeText(context, "无法安装: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    else -> {
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
                                menuTargetFile = file
                            },
                            isDark = isDark,
                            selectionMode = state.selectionMode,
                            selectedFiles = state.selectedFiles,
                            onToggleSelection = { file -> viewModel.toggleFileSelection(file) }
                        )
                    }
                    LaunchedEffect(files) {
                        adapter.submitList(files)
                    }
                    LaunchedEffect(isDark) {
                        adapter.updateTheme(isDark)
                    }
                    LaunchedEffect(state.selectionMode, state.selectedFiles) {
                        adapter.selectionMode = state.selectionMode
                        adapter.selectedFiles = state.selectedFiles
                        adapter.notifyDataSetChanged()
                    }
                    // 路径变化时恢复滚动位置
                    var lastPath by remember { mutableStateOf(state.currentPath) }
                    LaunchedEffect(state.currentPath) {
                        if (state.currentPath != lastPath) {
                            lastPath = state.currentPath
                            kotlinx.coroutines.delay(100)
                            val (index, offset) = viewModel.getSavedScrollPosition()
                            if (index > 0 || offset > 0) {
                                // 通过 adapter 的 recyclerView 恢复
                            }
                        }
                    }
                    AndroidView(
                        factory = { ctx ->
                            androidx.recyclerview.widget.RecyclerView(ctx).apply {
                                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                                this.adapter = adapter
                                clipToPadding = false
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                setPadding(0, 0, 0, ctx.resources.displayMetrics.heightPixels.let {
                                    val navBarId = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                                    if (navBarId > 0) ctx.resources.getDimensionPixelSize(navBarId) else 0
                                })
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
                            // 每次列表更新后恢复滚动位置
                            rv.post {
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
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_zip)) }, onClick = { compressTargetFile = null; viewModel.compressArchive(file, "zip") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_targz)) }, onClick = { compressTargetFile = null; viewModel.compressArchive(file, "targz") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_7z)) }, onClick = { compressTargetFile = null; viewModel.compressArchive(file, "7z") })
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

            // 批量压缩格式选择弹窗
            if (state.showBatchCompressDialog) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissBatchCompressDialog() },
                    title = { Text(stringResource(R.string.compress_format_title), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                    text = {
                        Column {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_zip)) }, onClick = { viewModel.executeBatchCompress("zip") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_targz)) }, onClick = { viewModel.executeBatchCompress("targz") })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_compress_7z)) }, onClick = { viewModel.executeBatchCompress("7z") })
                        }
                    },
                    confirmButton = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { viewModel.dismissBatchCompressDialog() }) { Text(stringResource(R.string.action_cancel)) }
                        }
                    },
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // 压缩进度弹窗
            if (state.compressProgress >= 0f) {
                AlertDialog(
                    onDismissRequest = { viewModel.cancelCompress() },
                    title = {
                        Text(
                            stringResource(R.string.action_compress),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                state.compressFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                            MiuixSlider(
                                value = state.compressProgress,
                                onValueChange = {},
                                valueRange = 0f..1f,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "${(state.compressProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    confirmButton = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { viewModel.cancelCompress() }) {
                                Text(stringResource(R.string.action_cancel))
                            }
                        }
                    },
                    dismissButton = {},
                    shape = RoundedCornerShape(16.dp)
                )
            }

            // 长按操作菜单
            if (menuTargetFile != null) {
                val file = menuTargetFile!!
                androidx.compose.ui.window.Popup(
                    alignment = Alignment.Center,
                    onDismissRequest = { menuTargetFile = null }
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.85f),
                        shape = RoundedCornerShape(20.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = file.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(bottom = 12.dp)
                            )

                            data class MenuItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val onClick: () -> Unit)
                            val items = mutableListOf<MenuItem>()
                            items.add(MenuItem(stringResource(R.string.action_select), Icons.Filled.CheckCircle) {
                                menuTargetFile = null
                                viewModel.toggleSelectionMode()
                                viewModel.toggleFileSelection(file)
                            })
                            items.add(MenuItem(stringResource(R.string.action_rename), Icons.Filled.Edit) { menuTargetFile = null; viewModel.renameFile(file) })
                            items.add(MenuItem(stringResource(R.string.action_copy), Icons.Filled.ContentCopy) { menuTargetFile = null; viewModel.copyFile(file) })
                            items.add(MenuItem(stringResource(R.string.action_move), Icons.AutoMirrored.Filled.DriveFileMove) { menuTargetFile = null; viewModel.moveFile(file) })
                            items.add(MenuItem(stringResource(R.string.action_copy_path), Icons.Filled.ContentPaste) { menuTargetFile = null; viewModel.copyPath(file) })
                            if (!file.isDirectory) {
                                items.add(MenuItem(stringResource(R.string.action_share), Icons.Filled.Share) { menuTargetFile = null; viewModel.shareFile(file) })
                            }
                            if (com.example.fold.data.archive.ArchiveHelper.isArchive(file.name)) {
                                items.add(MenuItem(stringResource(R.string.action_extract), Icons.Filled.FolderZip) { menuTargetFile = null; viewModel.extractArchive(file) })
                            }
                            items.add(MenuItem(stringResource(R.string.action_compress), Icons.Filled.Archive) { menuTargetFile = null; compressTargetFile = file })
                            items.add(MenuItem(stringResource(R.string.action_properties), Icons.Filled.Info) { menuTargetFile = null; viewModel.showProperties(file) })
                            items.add(MenuItem(stringResource(R.string.action_delete), Icons.Filled.Delete) { menuTargetFile = null; viewModel.deleteFile(file) })

                            val rows = items.chunked(2)
                            rows.forEachIndexed { _, rowItems ->
                                Row(modifier = Modifier.fillMaxWidth()) {
                                    rowItems.forEach { item ->
                                        val isDelete = item.label == stringResource(R.string.action_delete)
                                        Surface(
                                            onClick = item.onClick,
                                            shape = RoundedCornerShape(12.dp),
                                            color = Color.Transparent,
                                            modifier = Modifier.weight(1f).padding(3.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(imageVector = item.icon, contentDescription = null, modifier = Modifier.size(20.dp),
                                                    tint = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                                                Spacer(Modifier.width(8.dp))
                                                Text(text = item.label, style = MaterialTheme.typography.bodyMedium,
                                                    color = if (isDelete) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                    }
                                    if (rowItems.size < 2) Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 对话框 ====================

@Composable
private fun RenameDialog(currentName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_rename), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { if (newName.isNotBlank()) onConfirm(newName) }) { Text(stringResource(R.string.action_save)) }
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
    val calculating = stringResource(R.string.prop_calculating)
    var folderSize by remember { mutableStateOf(if (file.isDirectory) calculating else null) }
    LaunchedEffect(file.path, file.isDirectory) {
        if (file.isDirectory) {
            folderSize = withContext(Dispatchers.IO) { formatFileSize(calcFolderSize(java.io.File(file.path))) }
        }
    }
    var fileMd5 by remember { mutableStateOf(if (!file.isDirectory) calculating else null) }
    LaunchedEffect(file.path, file.isDirectory) {
        if (!file.isDirectory) {
            fileMd5 = withContext(Dispatchers.IO) { calcMd5(java.io.File(file.path)) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_properties), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PropertyRow(stringResource(R.string.prop_name), file.name)
                PropertyRow(stringResource(R.string.prop_path), file.path)
                if (file.isDirectory) { folderSize?.let { PropertyRow(stringResource(R.string.prop_size), it) } }
                else { PropertyRow(stringResource(R.string.prop_size), formatFileSize(file.size)) }
                PropertyRow(stringResource(R.string.prop_type), if (file.isDirectory) stringResource(R.string.file_type_folder) else file.extension.uppercase())
                PropertyRow(stringResource(R.string.prop_modified), modifiedText)
                fileMd5?.let { PropertyRow(stringResource(R.string.prop_md5), it) }
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

private fun calcFolderSize(dir: java.io.File): Long {
    if (!dir.exists() || !dir.isDirectory) return 0L
    var total = 0L
    val stack = ArrayDeque<java.io.File>()
    stack.addLast(dir)
    while (stack.isNotEmpty()) {
        val f = stack.removeLast()
        val children = f.listFiles() ?: continue
        for (c in children) {
            if (c.isDirectory) stack.addLast(c) else total += c.length()
        }
    }
    return total
}

private fun calcMd5(file: java.io.File): String {
    if (!file.exists() || !file.isFile) return ""
    return try {
        val digest = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().buffered(8192).use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "" }
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
                    Row(modifier = Modifier.fillMaxWidth().clickable { selectedMode = mode }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selectedMode == mode, onClick = { selectedMode = mode })
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { localFolderOnly = !localFolderOnly }, verticalAlignment = Alignment.CenterVertically) {
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                TextButton(onClick = { onFolderOnlyChange(localFolderOnly); onConfirm(selectedMode, localFolderOnly) }) { Text(stringResource(R.string.action_save)) }
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(12.dp)) {
                    Text(text = serverUrl, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(16.dp))
                }
                Text(text = stringResource(R.string.http_server_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(serverUrl)) }) { Text(stringResource(R.string.action_copy_path)) }
                TextButton(onClick = { onStop() }) { Text(stringResource(R.string.http_server_stop), color = MaterialTheme.colorScheme.error) }
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
