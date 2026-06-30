package com.example.fold.ui.trash

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fold.R
import com.example.fold.ui.filebrowser.formatFileSize
import com.example.fold.ui.filebrowser.formatTimestamp
import com.example.fold.ui.theme.MiuixSuccess
import java.io.File

data class TrashItem(
    val file: File,
    val originalPath: String?,
    val displayName: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    trashEnabled: Boolean,
    onToggleTrash: () -> Unit,
    getTrashDir: () -> File,
    getOriginalPath: (String) -> String?,
    onRestore: (File, String?) -> Unit,
    onDeletePermanent: (File) -> Unit,
    onEmptyTrash: () -> Unit
) {
    val context = LocalContext.current
    var trashFiles by remember { mutableStateOf(listOf<TrashItem>()) }

    fun refreshList() {
        val dir = getTrashDir()
        val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
        trashFiles = files.map { f ->
            val originalPath = getOriginalPath(f.absolutePath)
            val displayName = f.nameWithoutExtension.substringAfterLast('_').ifEmpty { f.name }
            TrashItem(f, originalPath, displayName)
        }
    }

    LaunchedEffect(Unit) { refreshList() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(stringResource(R.string.trash_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                IconButton(onClick = onToggleTrash) {
                    Icon(
                        Icons.Filled.DeleteSweep,
                        contentDescription = stringResource(R.string.trash_title),
                        tint = if (trashEnabled) MiuixSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (trashFiles.isNotEmpty()) {
                    IconButton(onClick = {
                        onEmptyTrash()
                        refreshList()
                    }) {
                        Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.trash_empty_all), tint = MaterialTheme.colorScheme.error)
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        )

        if (trashFiles.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = null, modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.trash_empty), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            Text(
                text = stringResource(R.string.trash_count, trashFiles.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(trashFiles, key = { it.file.absolutePath }) { item ->
                    ListItem(
                        headlineContent = {
                            Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Column {
                                if (item.originalPath != null) {
                                    Text(item.originalPath, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(
                                    "${formatFileSize(item.file.length())}  ·  ${formatTimestamp(item.file.lastModified())}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        leadingContent = {
                            Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = {
                                    onRestore(item.file, item.originalPath)
                                    refreshList()
                                }) {
                                    Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.trash_restore), tint = MiuixSuccess)
                                }
                                IconButton(onClick = {
                                    onDeletePermanent(item.file)
                                    refreshList()
                                }) {
                                    Icon(Icons.Filled.DeleteForever, contentDescription = stringResource(R.string.trash_delete_permanently), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        },
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }
}
