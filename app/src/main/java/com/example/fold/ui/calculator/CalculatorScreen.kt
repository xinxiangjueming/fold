package com.example.fold.ui.calculator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import kotlin.math.sqrt
import kotlin.math.PI
import kotlin.math.E
import kotlin.math.pow

/**
 * 计算器伪装界面 — 用于小爱老师等学习机
 *
 * 正常使用就是一个普通计算器（含科学计算）。
 * 输入特定密码序列后（如 7777=），触发 onUnlock 回调进入文件管理器。
 */
@Composable
fun CalculatorScreen(
    onUnlock: () -> Unit
) {
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val opBg = Color(0xFFFF9500)
    val opTextColor = Color.White
    val funcBg = MaterialTheme.colorScheme.surfaceContainerHigh
    val funcTextColor = onSurfaceColor
    val sciBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val sciTextColor = onSurfaceColor
    val buttonBg = MaterialTheme.colorScheme.surfaceContainer
    val buttonTextColor = onSurfaceColor

    var display by remember { mutableStateOf("0") }
    var prevValue by remember { mutableStateOf(0.0) }
    var currentOp by remember { mutableStateOf<String?>(null) }
    var newInput by remember { mutableStateOf(true) }
    // 表达式行（如 "5 +"）
    var expression by remember { mutableStateOf("") }
    // 密码缓冲区
    var secretBuffer by remember { mutableStateOf("") }

    // 解锁密码：连续输入 7777 然后按 =
    val unlockCode = "7777"

    fun formatNumber(v: Double): String {
        if (v.isNaN()) return "Error"
        if (v.isInfinite()) return "∞"
        if (v == v.toLong().toDouble()) return v.toLong().toString()
        val s = "%.10f".format(v)
        return s.trimEnd('0').trimEnd('.')
    }

    fun applyDisplay(v: Double) {
        display = formatNumber(v)
        newInput = true
    }

    fun currentValue(): Double = display.toDoubleOrNull() ?: 0.0

    fun onDigit(d: String) {
        secretBuffer += d
        if (secretBuffer.length > 20) secretBuffer = secretBuffer.takeLast(20)

        display = if (newInput) {
            newInput = false
            d
        } else {
            if (display == "0") d else display + d
        }
    }

    val opSymbol = mapOf("/" to "÷", "*" to "×", "-" to "-", "+" to "+", "pow" to "^")

    fun onOp(op: String) {
        secretBuffer = ""
        val current = currentValue()
        if (currentOp != null && !newInput) {
            prevValue = calc(prevValue, current, currentOp!!)
            display = formatNumber(prevValue)
        } else {
            prevValue = current
        }
        currentOp = op
        expression = "${formatNumber(prevValue)} ${opSymbol[op] ?: op}"
        newInput = true
    }

    fun onEquals() {
        if (secretBuffer.endsWith(unlockCode)) {
            secretBuffer = ""
            onUnlock()
            return
        }
        secretBuffer = ""

        val current = currentValue()
        if (currentOp != null) {
            expression = "${formatNumber(prevValue)} ${opSymbol[currentOp] ?: currentOp} ${formatNumber(current)} ="
            val result = calc(prevValue, current, currentOp!!)
            applyDisplay(result)
            currentOp = null
            prevValue = 0.0
        }
    }

    fun onClear() {
        secretBuffer = ""
        display = "0"
        prevValue = 0.0
        currentOp = null
        newInput = true
        expression = ""
    }

    // 科学计算
    fun onSqrt() {
        val v = currentValue()
        expression = "√(${formatNumber(v)})"
        applyDisplay(sqrt(v))
    }

    fun onSquare() {
        val v = currentValue()
        expression = "${formatNumber(v)}²"
        applyDisplay(v * v)
    }

    fun onReciprocal() {
        val v = currentValue()
        expression = "1/(${formatNumber(v)})"
        applyDisplay(if (v != 0.0) 1.0 / v else Double.NaN)
    }

    fun onNegate() {
        val v = currentValue()
        applyDisplay(-v)
    }

    fun onPercent() {
        val v = currentValue()
        expression = "${formatNumber(v)}%"
        applyDisplay(v / 100.0)
    }

    fun onPi() {
        expression = "π"
        applyDisplay(PI)
    }

    fun onE() {
        expression = "e"
        applyDisplay(E)
    }

    fun onSin() {
        val v = currentValue()
        expression = "sin(${formatNumber(v)})"
        applyDisplay(kotlin.math.sin(v))
    }

    fun onCos() {
        val v = currentValue()
        expression = "cos(${formatNumber(v)})"
        applyDisplay(kotlin.math.cos(v))
    }

    fun onTan() {
        val v = currentValue()
        expression = "tan(${formatNumber(v)})"
        applyDisplay(kotlin.math.tan(v))
    }

    fun onLn() {
        val v = currentValue()
        expression = "ln(${formatNumber(v)})"
        applyDisplay(if (v > 0) kotlin.math.ln(v) else Double.NaN)
    }

    fun onLog() {
        val v = currentValue()
        expression = "log(${formatNumber(v)})"
        applyDisplay(if (v > 0) kotlin.math.log10(v) else Double.NaN)
    }

    // 高级计算开关（持久化）
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("file_sort", android.content.Context.MODE_PRIVATE) }
    var showAdvanced by remember { mutableStateOf(prefs.getBoolean("calc_advanced", false)) }

    // 低 dpi 设备 MIUI 自动处理导航栏 insets，不需要手动 padding
    val density = LocalDensity.current
    val dpi = remember { context.resources.displayMetrics.densityDpi }
    val bottomPad = remember(dpi) {
        if (dpi <= 320) 0.dp else {
            val id = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            val px = if (id > 0) context.resources.getDimensionPixelSize(id) else 0
            with(density) { px.toDp() }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.Bottom
    ) {

        // 表达式行
        if (expression.isNotEmpty()) {
            Text(
                text = expression,
                color = onSurfaceVariant,
                fontSize = 22.sp,
                fontWeight = FontWeight.Light,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )
        }
        // 显示屏
        Text(
            text = display,
            color = onSurfaceColor,
            fontSize = 48.sp,
            fontWeight = FontWeight.Light,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        )

        // 按钮区域
        val buttonSpacing = if (showAdvanced) 6.dp else 8.dp
        val buttonHeight = if (showAdvanced) 48.dp else 64.dp
        val btnFontSize = if (showAdvanced) 17.sp else 22.sp
        val haptic = LocalHapticFeedback.current

        @Composable
        fun CalcButton(
            text: String,
            bgColor: Color = buttonBg,
            textColor: Color = buttonTextColor,
            weight: Float = 1f,
            onClick: () -> Unit
        ) {
            Box(
                modifier = Modifier
                    .weight(weight)
                    .height(buttonHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        onClick()
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    color = textColor,
                    fontSize = btnFontSize,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 科学计算 — 点击 f(x) 展开
        if (showAdvanced) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
                    CalcButton("sin", bgColor = sciBg, textColor = sciTextColor) { onSin() }
                    CalcButton("cos", bgColor = sciBg, textColor = sciTextColor) { onCos() }
                    CalcButton("tan", bgColor = sciBg, textColor = sciTextColor) { onTan() }
                    CalcButton("ln", bgColor = sciBg, textColor = sciTextColor) { onLn() }
                    CalcButton("log", bgColor = sciBg, textColor = sciTextColor) { onLog() }
                }
                Spacer(Modifier.height(buttonSpacing))
                Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
                    CalcButton("√", bgColor = sciBg, textColor = sciTextColor) { onSqrt() }
                    CalcButton("x²", bgColor = sciBg, textColor = sciTextColor) { onSquare() }
                    CalcButton("x^y", bgColor = sciBg, textColor = sciTextColor) { onOp("pow") }
                    CalcButton("π", bgColor = sciBg, textColor = sciTextColor) { onPi() }
                    CalcButton("e", bgColor = sciBg, textColor = sciTextColor) { onE() }
                }
                Spacer(Modifier.height(buttonSpacing))
            }
        }

        // Row 1: AC  ⌫  ±  %  ÷（左四等宽，÷ 与下方橙色等宽）
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
            CalcButton("AC", weight = 0.75f, bgColor = funcBg, textColor = funcTextColor) { onClear() }
            CalcButton("⌫", weight = 0.75f, bgColor = funcBg, textColor = funcTextColor) {
                if (display.length > 1) display = display.dropLast(1) else display = "0"
            }
            CalcButton("±", weight = 0.75f, bgColor = funcBg, textColor = funcTextColor) { onNegate() }
            CalcButton("%", weight = 0.75f, bgColor = funcBg, textColor = funcTextColor) { onPercent() }
            CalcButton("÷", bgColor = opBg, textColor = opTextColor) { onOp("/") }
        }
        Spacer(Modifier.height(buttonSpacing))

        // Row 2: 7 8 9 ×
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
            CalcButton("7") { onDigit("7") }
            CalcButton("8") { onDigit("8") }
            CalcButton("9") { onDigit("9") }
            CalcButton("×", bgColor = opBg, textColor = opTextColor) { onOp("*") }
        }
        Spacer(Modifier.height(buttonSpacing))

        // Row 3: 4 5 6 -
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
            CalcButton("4") { onDigit("4") }
            CalcButton("5") { onDigit("5") }
            CalcButton("6") { onDigit("6") }
            CalcButton("-", bgColor = opBg, textColor = opTextColor) { onOp("-") }
        }
        Spacer(Modifier.height(buttonSpacing))

        // Row 4: 1 2 3 +
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
            CalcButton("1") { onDigit("1") }
            CalcButton("2") { onDigit("2") }
            CalcButton("3") { onDigit("3") }
            CalcButton("+", bgColor = opBg, textColor = opTextColor) { onOp("+") }
        }
        Spacer(Modifier.height(buttonSpacing))

        // Row 5: f(x) 0 . =
        Row(horizontalArrangement = Arrangement.spacedBy(buttonSpacing), modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (showAdvanced) opBg.copy(alpha = 0.15f) else funcBg)
                    .clickable {
                        haptic.performHapticFeedback(HapticFeedbackType.VirtualKey)
                        showAdvanced = !showAdvanced
                        prefs.edit().putBoolean("calc_advanced", showAdvanced).apply()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Functions,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (showAdvanced) opBg else funcTextColor
                )
            }
            CalcButton("0") { onDigit("0") }
            CalcButton(".") { onDigit(".") }
            CalcButton("=", bgColor = opBg, textColor = opTextColor) { onEquals() }
        }
    }
}

private fun calc(a: Double, b: Double, op: String): Double {
    return when (op) {
        "+" -> a + b
        "-" -> a - b
        "*" -> a * b
        "/" -> if (b != 0.0) a / b else Double.NaN
        "pow" -> a.pow(b)
        else -> b
    }
}
