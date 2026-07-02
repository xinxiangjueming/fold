package com.example.fold.ui.reader

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.fold.R
import com.example.fold.util.CharsetDetector
import com.example.fold.util.FoldLogger
import kotlinx.coroutines.*
import java.io.File

class TextEditorActivity : AppCompatActivity() {

    private var editText: EditText? = null
    private var toolbar: Toolbar? = null
    private var originalText = ""
    private var isModified = false
    private var filePath = ""
    private var fontSize = 14
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        filePath = intent.getStringExtra("filePath") ?: ""
        if (filePath.isEmpty()) {
            Toast.makeText(this, "No file path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("text_editor", MODE_PRIVATE)
        fontSize = prefs.getInt("font_size", 14)

        val toolbar = Toolbar(this).apply {
            setBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#F5F5F5"))
            setTitleTextColor(if (isDark) Color.WHITE else Color.BLACK)
            title = File(filePath).name
            setSupportActionBar(this)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            setNavigationOnClickListener { onBackPressedCompat() }
        }
        this.toolbar = toolbar

        val container = FrameLayout(this).apply {
            setBackgroundColor(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#FAFAFA"))
        }

        editText = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = fontSize.toFloat()
            setPadding(32, 16, 32, 16)
            setLineSpacing(0f, 1.2f)
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(if (isDark) Color.parseColor("#E0E0E0") else Color.parseColor("#1F1F1F"))
            setHintTextColor(if (isDark) Color.parseColor("#666666") else Color.parseColor("#999999"))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isScrollContainer = true
            isSingleLine = false
            overScrollMode = View.OVER_SCROLL_ALWAYS
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.TOP or Gravity.START
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!isModified) {
                        isModified = true
                        this@TextEditorActivity.toolbar?.title = File(filePath).name + " *"
                    }
                }
            })
        }

        val scrollContainer = android.widget.ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.TRANSPARENT)
            addView(editText, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        container.addView(scrollContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(toolbar, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(container, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(root)

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        loadFile()
    }

    private fun loadFile() {
        scope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    CharsetDetector.readTextFile(File(filePath))
                }
                originalText = content
                editText?.setText(content)
                editText?.setSelection(0, 0)
                isModified = false
            } catch (e: Exception) {
                FoldLogger.e("TextEditor", "load failed: ${File(filePath).name}", e)
                Toast.makeText(this@TextEditorActivity, "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveFile() {
        val et = editText ?: return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    File(filePath).writeText(et.text.toString())
                }
                originalText = et.text.toString()
                isModified = false
                this@TextEditorActivity.toolbar?.title = File(filePath).name
                Toast.makeText(this@TextEditorActivity, getString(R.string.action_save) + " ✓", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                FoldLogger.e("TextEditor", "save failed: ${File(filePath).name}", e)
                Toast.makeText(this@TextEditorActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, 1, 0, getString(R.string.editor_font_size)).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, 2, 0, getString(R.string.action_save)).setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            1 -> { showFontSizeDialog(); true }
            2 -> { saveFile(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFontSizeDialog() {
        val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        val textColor = if (isDark) 0xFFE0E0E0.toInt() else 0xFF1F1F1F.toInt()
        val accentColor = 0xFF2196F3.toInt()

        val label = android.widget.TextView(this).apply {
            text = "${fontSize}sp"
            textSize = fontSize.toFloat()
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 0)
            setTextColor(textColor)
        }
        val seekBar = android.widget.SeekBar(this).apply {
            min = 8
            max = 32
            progress = fontSize
            setPadding(48, 16, 48, 0)
            progressTintList = android.content.res.ColorStateList.valueOf(accentColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(accentColor)
        }
        seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                label.text = "${progress}sp"
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })

        val titleView = android.widget.TextView(this).apply {
            text = getString(R.string.editor_font_size)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 8)
            setTextColor(textColor)
        }

        val cancelBtn = android.widget.TextView(this).apply {
            text = getString(R.string.action_cancel)
            setTextColor(accentColor)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }
        val confirmBtn = android.widget.TextView(this).apply {
            text = getString(R.string.editor_confirm)
            setTextColor(accentColor)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(48, 32, 48, 32)
        }
        val buttonRow = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(cancelBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(confirmBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_dialog_light)
            setPadding(48, 0, 48, 8)
            addView(titleView)
            addView(label)
            addView(seekBar)
            addView(buttonRow)
        }

        val dialog = android.app.Dialog(this, R.style.RoundedDialog)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(R.drawable.bg_dialog_light)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.8).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        )
        dialog.show()

        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            fontSize = seekBar.progress
            editText?.textSize = fontSize.toFloat()
            getSharedPreferences("text_editor", MODE_PRIVATE)
                .edit().putInt("font_size", fontSize).apply()
            dialog.dismiss()
        }
    }

    override fun onBackPressed() {
        onBackPressedCompat()
    }

    private fun onBackPressedCompat() {
        if (isModified) {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.editor_unsaved_confirm)
                .setPositiveButton(R.string.editor_save) { _, _ ->
                    saveFile()
                    finish()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("Discard") { _, _ -> finish() }
                .show()
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        fun start(context: android.content.Context, filePath: String) {
            val intent = android.content.Intent(context, TextEditorActivity::class.java).apply {
                putExtra("filePath", filePath)
            }
            context.startActivity(intent)
        }
    }
}
