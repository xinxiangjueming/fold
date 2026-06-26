package com.example.fold.ui.hidden

import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.fold.R
import com.example.fold.data.manager.AppHider
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.launch

private const val TAG = "HiddenApps"

/**
 * 隐藏应用管理界面
 *
 * 中间网格显示已隐藏的应用，点击启动，长按弹出"恢复显示"选项。
 * 右下角 FAB 点击弹出应用选择器，选择后隐藏。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HiddenAppsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hiddenApps by remember { mutableStateOf<List<AppHider.AppEntry>>(emptyList()) }
    var allApps by remember { mutableStateOf<List<AppHider.AppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showPicker by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<AppHider.AppEntry?>(null) }
    var launchFailed by remember { mutableStateOf<String?>(null) }
    var method by remember { mutableStateOf<AppHider.HideMethod?>(null) }

    // 加载数据
    fun reload() {
        scope.launch {
            isLoading = true
            method = AppHider.detectMethod()
            allApps = AppHider.listApps(context)
            hiddenApps = allApps.filter { it.isHidden }
            isLoading = false
            FoldLogger.d(TAG, "reload: method=$method, hidden=${hiddenApps.size}, total=${allApps.size}")
        }
    }

    LaunchedEffect(Unit) { reload() }

    // Shizuku 授权后自动刷新（无论之前是否已有结果）
    val shizukuGranted by com.example.fold.util.ShizukuHelper.granted.collectAsStateWithLifecycle()
    val shizukuBound by com.example.fold.util.ShizukuHelper.serviceBound.collectAsStateWithLifecycle()
    LaunchedEffect(shizukuGranted, shizukuBound) {
        if (shizukuGranted && shizukuBound && method == null) {
            FoldLogger.d(TAG, "shizuku ready (granted=$shizukuGranted, bound=$shizukuBound), auto-reloading")
            AppHider.invalidateCache()
            reload()
        }
    }

    BackHandler { onBack() }

    // 应用操作弹窗
    menuTarget?.let { app ->
        AlertDialog(
            onDismissRequest = { menuTarget = null },
            title = {
                Text(
                    app.name,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Text(
                    stringResource(R.string.hidden_app_action_hint),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = {
                        scope.launch {
                            AppHider.launchApp(context, app.packageName)
                            menuTarget = null
                        }
                    }) { Text(stringResource(R.string.hidden_app_launch)) }
                    TextButton(onClick = {
                        scope.launch {
                            AppHider.unhideApp(context, app.packageName)
                            menuTarget = null
                            reload()
                        }
                    }) { Text(stringResource(R.string.hidden_app_unhide)) }
                }
            }
        )
    }

    // 启动失败提示
    launchFailed?.let { name ->
        AlertDialog(
            onDismissRequest = { launchFailed = null },
            title = { Text(stringResource(R.string.hidden_app_launch_failed)) },
            text = { Text(stringResource(R.string.hidden_app_launch_failed_hint, name)) },
            confirmButton = {
                TextButton(onClick = { launchFailed = null }) { Text("OK") }
            }
        )
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            // TopBar
            TopAppBar(
                title = { Text(stringResource(R.string.hidden_apps_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (method != null) {
                        Text(
                            text = stringResource(
                                when (method) {
                                    AppHider.HideMethod.ROOT -> R.string.hidden_method_root
                                    AppHider.HideMethod.SHIZUKU -> R.string.hidden_method_shizuku
                                    else -> R.string.hidden_method_shizuku
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceContainerHigh,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (method == null) {
                // 无权限提示
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.hidden_apps_no_method),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.hidden_apps_no_method_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            com.example.fold.util.ShizukuHelper.requestPermission()
                            scope.launch {
                                kotlinx.coroutines.delay(500)
                                method = AppHider.detectMethod()
                            }
                        }) {
                            Text(stringResource(R.string.shizuku_request))
                        }
                    }
                }
            } else if (hiddenApps.isEmpty()) {
                // 空状态
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.hidden_apps_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.hidden_apps_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 32.dp)
                        )
                    }
                }
            } else {
                // 应用网格
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(hiddenApps, key = { it.packageName }) { app ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        scope.launch {
                                            if (!AppHider.launchApp(context, app.packageName)) {
                                                launchFailed = app.name
                                            }
                                        }
                                    },
                                    onLongClick = { menuTarget = app }
                                )
                                .padding(8.dp)
                        ) {
                            AppIcon(app.icon, Modifier.size(48.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(
                                app.name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // FAB：添加隐藏应用（仅在有权限时显示）
        if (!isLoading && method != null) {
            FloatingActionButton(
                onClick = { showPicker = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.hidden_apps_add))
            }
        }
    }

    // 应用选择弹窗
    if (showPicker) {
        AppPickerDialog(
            allApps = allApps,
            onDismiss = { showPicker = false },
            onConfirm = { selected ->
                showPicker = false
                scope.launch {
                    var count = 0
                    for (pkg in selected) {
                        if (AppHider.hideApp(context, pkg)) count++
                    }
                    FoldLogger.i(TAG, "hideApps: requested=${selected.size}, success=$count")
                    reload()
                }
            }
        )
    }
}

/**
 * 应用选择弹窗 — 显示所有第三方应用，多选后确定隐藏
 */
@Composable
private fun AppPickerDialog(
    allApps: List<AppHider.AppEntry>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    // 只显示未隐藏的、非保护的应用
    val available = remember(allApps) {
        allApps.filter { !it.isHidden && !it.isProtected }
    }
    val selected = remember { mutableStateOf(setOf<String>()) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(available, searchQuery) {
        if (searchQuery.isBlank()) available
        else available.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    stringResource(R.string.hidden_picker_title),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text(stringResource(R.string.hidden_picker_search), fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        text = {
            if (available.isEmpty()) {
                Text(
                    stringResource(R.string.hidden_picker_empty),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    filtered.forEach { app ->
                        val isChecked = selected.value.contains(app.packageName)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isChecked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                    else Color.Transparent
                                )
                                .combinedClickable(
                                    onClick = {
                                        selected.value = if (isChecked) {
                                            selected.value - app.packageName
                                        } else {
                                            selected.value + app.packageName
                                        }
                                    }
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            AppIcon(app.icon, Modifier.size(36.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(app.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (isChecked) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
                TextButton(
                    onClick = { onConfirm(selected.value) },
                    enabled = selected.value.isNotEmpty()
                ) {
                    Text(stringResource(R.string.hidden_picker_confirm, selected.value.size))
                }
            }
        }
    )
}

@Composable
private fun AppIcon(drawable: Drawable, modifier: Modifier = Modifier) {
    val bitmap = remember(drawable) {
        drawable.toBitmap(96, 96).asImageBitmap()
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        contentScale = ContentScale.Fit
    )
}
