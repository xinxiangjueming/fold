package com.example.fold.ui.reader

import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.fold.R
import com.example.fold.util.CharsetDetector
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember(filePath) { File(filePath) }
    var isModified by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val keyboardController = LocalSoftwareKeyboardController.current
    var originalText by remember { mutableStateOf("") }
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    val prefs = remember { context.getSharedPreferences("text_editor", android.content.Context.MODE_PRIVATE) }
    var fontSize by remember { mutableIntStateOf(prefs.getInt("font_size", 14)) }
    var showFontSizeDialog by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        isLoading = true
        loadError = null
        try {
            val content = withContext(Dispatchers.IO) {
                CharsetDetector.readTextFile(file)
            }
            originalText = content
            editTextRef.value?.setText(content)
            editTextRef.value?.setSelection(0, 0)
        } catch (e: Exception) {
            FoldLogger.e("TextEditor", "load failed: ${file.name}", e)
            loadError = e.message
        }
        isLoading = false
    }

    fun save() {
        keyboardController?.hide()
        val et = editTextRef.value ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    file.writeText(et.text.toString())
                }
                originalText = et.text.toString()
                isModified = false
                snackbarHostState.showSnackbar(context.getString(R.string.action_save) + " ✓")
            } catch (e: Exception) {
                FoldLogger.e("TextEditor", "save failed: ${file.name}", e)
                snackbarHostState.showSnackbar(context.getString(R.string.editor_save_failed, e.message ?: ""))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = file.name + if (isModified) " *" else "",
                        maxLines = 1,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isModified) {
                            scope.launch {
                                val result = snackbarHostState.showSnackbar(
                                    context.getString(R.string.editor_unsaved_confirm),
                                    actionLabel = context.getString(R.string.editor_save),
                                    withDismissAction = true
                                )
                                when (result) {
                                    SnackbarResult.ActionPerformed -> { save(); onBack() }
                                    SnackbarResult.Dismissed -> onBack()
                                }
                            }
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showFontSizeDialog = true }) {
                        Text("${fontSize}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (isModified) {
                        IconButton(onClick = { save() }) {
                            Icon(Icons.Filled.Save, contentDescription = stringResource(R.string.action_save))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { _ ->
        val isDark = androidx.compose.foundation.isSystemInDarkTheme()
        val bgColor = MaterialTheme.colorScheme.background

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(androidx.compose.ui.Alignment.Center))
                }
                loadError != null -> {
                    Column(
                        modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                    ) {
                        Text(loadError!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = { onBack() }) { Text(stringResource(R.string.action_back)) }
                    }
                }
                else -> {
                    AndroidView(
                        factory = { ctx ->
                            EditText(ctx).apply {
                                typeface = Typeface.MONOSPACE
                                textSize = fontSize.toFloat()
                                setPadding(32, 16, 32, 16)
                                setLineSpacing(0f, 1.2f)
                                setText(originalText)
                                setBackgroundColor(Color.TRANSPARENT)
                                setTextColor(if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#1F1F1F"))
                                setHintTextColor(if (isDark) Color.parseColor("#666666") else Color.parseColor("#999999"))
                                addTextChangedListener(object : TextWatcher {
                                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                                    override fun afterTextChanged(s: Editable?) {
                                        isModified = s?.toString() != originalText
                                    }
                                })
                                editTextRef.value = this
                            }
                        },
                        update = { et ->
                            et.setBackgroundColor(Color.TRANSPARENT)
                            et.setTextColor(if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#1F1F1F"))
                            et.textSize = fontSize.toFloat()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (showFontSizeDialog) {
            var sliderValue by remember { mutableFloatStateOf(fontSize.toFloat()) }
            AlertDialog(
                onDismissRequest = { showFontSizeDialog = false },
                title = { Text(stringResource(R.string.editor_font_size), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${sliderValue.toInt()}", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                fontSize = sliderValue.toInt()
                                prefs.edit().putInt("font_size", fontSize).apply()
                            },
                            valueRange = 8f..32f,
                            steps = 23,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("8", style = MaterialTheme.typography.bodySmall)
                            Text("32", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFontSizeDialog = false }) { Text(stringResource(R.string.editor_confirm)) }
                },
                dismissButton = {},
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}
