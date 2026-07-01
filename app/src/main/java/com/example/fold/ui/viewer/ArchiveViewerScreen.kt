package com.example.fold.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fold.R
import com.example.fold.data.archive.ArchiveEntry
import com.example.fold.data.archive.ArchiveHelper
import com.example.fold.ui.filebrowser.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveViewerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }
    var entries by remember { mutableStateOf<List<ArchiveEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var extracting by remember { mutableStateOf(false) }
    var extractProgress by remember { mutableFloatStateOf(0f) }
    var extractMessage by remember { mutableStateOf("") }

    LaunchedEffect(filePath) {
        isLoading = true
        error = null
        val result = withContext(Dispatchers.IO) {
            try {
                ArchiveHelper.listEntries(filePath)
            } catch (e: Exception) {
                null
            }
        }
        if (result != null) {
            entries = result
        } else {
            error = context.getString(R.string.archive_read_failed)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (entries.isNotEmpty()) {
                            Text(
                                text = "${stringResource(R.string.archive_entry_count, entries.size)} · ${formatFileSize(file.length())}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (entries.isNotEmpty() && !extracting) {
                        TextButton(onClick = {
                            extracting = true
                            extractProgress = 0f
                            scope.launch {
                                val destDir = file.parentFile?.resolve(file.nameWithoutExtension)?.absolutePath
                                    ?: return@launch
                                val result = ArchiveHelper.extract(filePath, destDir) { name, current, total ->
                                    extractMessage = name
                                    if (total > 0) extractProgress = current.toFloat() / total
                                }
                                extracting = false
                                if (result.isSuccess) {
                                    onBack()
                                }
                            }
                        }) {
                            Text(stringResource(R.string.action_extract_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                error != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) }
                    }
                }
                extracting -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text(extractMessage, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { extractProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("${(extractProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                    }
                }
                else -> {
                    val adapter = remember { ArchiveListAdapter() }
                    LaunchedEffect(entries) {
                        adapter.submitList(entries)
                    }
                    AndroidView(
                        factory = { ctx ->
                            androidx.recyclerview.widget.RecyclerView(ctx).apply {
                                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
                                this.adapter = adapter
                                clipToPadding = false
                                importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                                // 底部 padding 避开导航栏
                                setPadding(0, 0, 0, ctx.resources.displayMetrics.heightPixels.let {
                                    val navBarId = ctx.resources.getIdentifier("navigation_bar_height", "dimen", "android")
                                    if (navBarId > 0) ctx.resources.getDimensionPixelSize(navBarId) else 0
                                })
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
