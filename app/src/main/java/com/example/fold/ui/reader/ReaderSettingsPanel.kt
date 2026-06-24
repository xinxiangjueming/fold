package com.example.fold.ui.reader

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.fold.R
import com.example.fold.data.model.ReaderState
import com.example.fold.data.model.ReadingTheme
import java.io.File

@Composable
internal fun ReaderSettingsPanel(
    state: ReaderState,
    onFontSizeChange: (Float) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onFontImport: (Uri) -> Unit,
    onFontReset: () -> Unit,
    onThemeChange: (ReadingTheme) -> Unit,
    onMarginsChange: (Float, Float) -> Unit,
    onReSegmentToggle: () -> Unit,
    onChineseConvertCycle: () -> Unit,
    onAddReplacement: (String, String) -> Unit,
    onRemoveReplacement: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val fontPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { onFontImport(it) } }
    var showWordReplace by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 16.dp), color = MaterialTheme.colorScheme.onSurface)

            // 字号
            Text(stringResource(R.string.reader_font_size, state.fontSize.toInt()), color = MaterialTheme.colorScheme.onSurface)
            Slider(value = state.fontSize, onValueChange = onFontSizeChange, valueRange = 12f..28f, steps = 15)
            Spacer(Modifier.height(8.dp))

            // 行距
            Text(stringResource(R.string.reader_line_spacing, "%.1f".format(state.lineSpacing)), color = MaterialTheme.colorScheme.onSurface)
            Slider(value = state.lineSpacing, onValueChange = onLineSpacingChange, valueRange = 1.0f..3.0f, steps = 19)
            Spacer(Modifier.height(12.dp))

            // 阅读主题
            Text(stringResource(R.string.reader_theme), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurface)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReadingTheme.entries.forEach { theme ->
                    val isSelected = state.readingTheme == theme
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(androidx.compose.ui.graphics.Color(theme.bgColor))
                            .then(
                                if (isSelected) Modifier.border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(8.dp)
                                ) else Modifier
                            )
                            .clickable { onThemeChange(theme) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = theme.label.first().toString(),
                            color = androidx.compose.ui.graphics.Color(theme.textColor),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 边距
            Text(stringResource(R.string.reader_margin, state.marginLeft.toInt(), state.marginRight.toInt()), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.reader_margin_left), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = state.marginLeft, onValueChange = { onMarginsChange(it, state.marginRight) }, valueRange = 0f..60f, steps = 11)
            Text(stringResource(R.string.reader_margin_right), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(value = state.marginRight, onValueChange = { onMarginsChange(state.marginLeft, it) }, valueRange = 0f..60f, steps = 11)
            Spacer(Modifier.height(12.dp))

            // 重新分段
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.reader_re_segment), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.reader_re_segment_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.reSegment, onCheckedChange = { onReSegmentToggle() })
            }
            Spacer(Modifier.height(12.dp))

            // 繁简转换
            val chineseConvertLabel = when (state.chineseConvert) {
                1 -> stringResource(R.string.chinese_to_traditional)
                2 -> stringResource(R.string.chinese_to_simplified)
                else -> stringResource(R.string.chinese_convert_off)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.chinese_convert), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Text(chineseConvertLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onChineseConvertCycle) {
                    Text(when (state.chineseConvert) {
                        1 -> stringResource(R.string.chinese_to_traditional)
                        2 -> stringResource(R.string.chinese_to_simplified)
                        else -> stringResource(R.string.action_off)
                    })
                }
            }
            Spacer(Modifier.height(12.dp))

            // 字体
            Text(stringResource(R.string.reader_font), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val fontName = if (state.fontPath.isNotEmpty() && File(state.fontPath).exists()) {
                    File(state.fontPath).nameWithoutExtension
                } else stringResource(R.string.reader_font_default)
                Text(fontName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { fontPickerLauncher.launch("*/*") }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Filled.FontDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.reader_font_import))
                }
                if (state.fontPath.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = onFontReset, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Restore, contentDescription = stringResource(R.string.reader_font_reset), modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // 替换词
            OutlinedButton(
                onClick = { showWordReplace = !showWordReplace },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.FindReplace, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.reader_word_replace), color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.weight(1f))
                Text("${state.wordReplacements.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (showWordReplace) {
                Spacer(Modifier.height(8.dp))
                WordReplacementPanel(
                    replacements = state.wordReplacements,
                    onAdd = onAddReplacement,
                    onRemove = onRemoveReplacement
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.action_close))
            }
        }
    }
}

@Composable
private fun WordReplacementPanel(
    replacements: Map<String, String>,
    onAdd: (String, String) -> Unit,
    onRemove: (String) -> Unit
) {
    var original by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }

    Column {
        // 现有替换词列表
        replacements.forEach { (orig, repl) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(orig, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(repl, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 4.dp))
                IconButton(onClick = { onRemove(orig) }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // 添加新替换词
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = original,
                onValueChange = { original = it },
                label = { Text(stringResource(R.string.reader_replace_original)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = replacement,
                onValueChange = { replacement = it },
                label = { Text(stringResource(R.string.reader_replace_new)) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )
            IconButton(
                onClick = {
                    if (original.isNotBlank()) {
                        onAdd(original, replacement)
                        original = ""
                        replacement = ""
                    }
                },
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    }
}
