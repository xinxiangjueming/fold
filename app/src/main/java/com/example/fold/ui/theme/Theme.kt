package com.example.fold.ui.theme

import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 全局深色模式状态（单例）：0 = 跟随系统, 1 = 强制深色, 2 = 强制浅色
 * FoldTheme 读取它决定配色方案，toggleDarkMode() 写入它触发重组。
 */
val darkModeState = mutableIntStateOf(0)

/** 子组件可通过 CompositionLocal 读取当前模式 */
val LocalDarkMode = compositionLocalOf { 0 }

private val LightColors = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF0072FF),
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E3FF),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFF001B3E),
    secondary = androidx.compose.ui.graphics.Color(0xFF545F70),
    onSecondary = androidx.compose.ui.graphics.Color.White,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E3F8),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFF111C2B),
    tertiary = androidx.compose.ui.graphics.Color(0xFF6E5676),
    onTertiary = androidx.compose.ui.graphics.Color.White,
    background = androidx.compose.ui.graphics.Color(0xFFF5F5F5),
    onBackground = androidx.compose.ui.graphics.Color(0xFF1F1F1F),
    surface = androidx.compose.ui.graphics.Color.White,
    onSurface = androidx.compose.ui.graphics.Color(0xFF1F1F1F),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFFE8E8E8),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF8A8A8A),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color.White,
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFFF8F8F8),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFFF2F2F2),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFFECECEC),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFFE6E6E6),
    outline = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFFD0D0D0),
    error = androidx.compose.ui.graphics.Color(0xFFFF3B30),
)

private val DarkColors = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF4DA3FF),
    onPrimary = androidx.compose.ui.graphics.Color.Black,
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF0D47A1),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E3FF),
    secondary = androidx.compose.ui.graphics.Color(0xFFBCC7DB),
    onSecondary = androidx.compose.ui.graphics.Color.Black,
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF3C4758),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD8E3F8),
    tertiary = androidx.compose.ui.graphics.Color(0xFFDBBCE2),
    onTertiary = androidx.compose.ui.graphics.Color.Black,
    background = androidx.compose.ui.graphics.Color(0xFF1A1A1A),
    onBackground = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
    surface = androidx.compose.ui.graphics.Color(0xFF222222),
    onSurface = androidx.compose.ui.graphics.Color(0xFFE0E0E0),
    surfaceVariant = androidx.compose.ui.graphics.Color(0xFF303030),
    onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF9E9E9E),
    surfaceContainerLowest = androidx.compose.ui.graphics.Color(0xFF181818),
    surfaceContainerLow = androidx.compose.ui.graphics.Color(0xFF1E1E1E),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF252525),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2F2F2F),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF3A3A3A),
    outline = androidx.compose.ui.graphics.Color(0xFF404040),
    outlineVariant = androidx.compose.ui.graphics.Color(0xFF353535),
    error = androidx.compose.ui.graphics.Color(0xFFFF3B30),
)

private const val PREF_NAME = "fold_prefs"
private const val KEY_DARK_MODE = "dark_mode"  // 0=系统, 1=强制深色, 2=强制浅色

/** 读取持久化的深色模式偏好 (可在非 Compose 上下文调用) */
fun getDarkModePref(context: Context): Int {
    return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).getInt(KEY_DARK_MODE, 0)
}

@Composable
fun FoldTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 首次组合时从 SharedPreferences 同步到全局状态
    LaunchedEffect(Unit) {
        darkModeState.intValue = getDarkModePref(context)
    }
    val darkMode = darkModeState.intValue  // 0=系统, 1=深色, 2=浅色

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (darkMode) {
        1 -> true
        2 -> false
        else -> systemDark
    }
    android.util.Log.d("Theme", "FoldTheme recompose: darkMode=$darkMode, systemDark=$systemDark, darkTheme=$darkTheme")

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as ComponentActivity
        val window = activity.window

        // enableEdgeToEdge 只执行一次
        LaunchedEffect(Unit) {
            activity.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                @Suppress("DEPRECATION")
                window.isStatusBarContrastEnforced = false
            }
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }

        // 状态栏颜色跟随主题变化（轻量级，可以放 SideEffect）
        SideEffect {
            @Suppress("DEPRECATION")
            window.statusBarColor = (if (darkTheme) DarkColors.background else LightColors.background).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalDarkMode provides darkMode) {
        val animSpec = tween<androidx.compose.ui.graphics.Color>(300)
        val targetColors = if (darkTheme) DarkColors else LightColors
        val animatedColors = targetColors.copy(
            primary = animateColorAsState(targetColors.primary, animSpec, "primary").value,
            onPrimary = animateColorAsState(targetColors.onPrimary, animSpec, "onPrimary").value,
            primaryContainer = animateColorAsState(targetColors.primaryContainer, animSpec, "primaryContainer").value,
            onPrimaryContainer = animateColorAsState(targetColors.onPrimaryContainer, animSpec, "onPrimaryContainer").value,
            background = animateColorAsState(targetColors.background, animSpec, "background").value,
            onBackground = animateColorAsState(targetColors.onBackground, animSpec, "onBackground").value,
            surface = animateColorAsState(targetColors.surface, animSpec, "surface").value,
            onSurface = animateColorAsState(targetColors.onSurface, animSpec, "onSurface").value,
            surfaceVariant = animateColorAsState(targetColors.surfaceVariant, animSpec, "surfaceVariant").value,
            onSurfaceVariant = animateColorAsState(targetColors.onSurfaceVariant, animSpec, "onSurfaceVariant").value,
            surfaceContainerLowest = animateColorAsState(targetColors.surfaceContainerLowest, animSpec, "sCLowest").value,
            surfaceContainerLow = animateColorAsState(targetColors.surfaceContainerLow, animSpec, "sCLow").value,
            surfaceContainer = animateColorAsState(targetColors.surfaceContainer, animSpec, "sC").value,
            surfaceContainerHigh = animateColorAsState(targetColors.surfaceContainerHigh, animSpec, "sCHigh").value,
            surfaceContainerHighest = animateColorAsState(targetColors.surfaceContainerHighest, animSpec, "sCHighest").value,
            outline = animateColorAsState(targetColors.outline, animSpec, "outline").value,
            error = animateColorAsState(targetColors.error, animSpec, "error").value,
            onError = animateColorAsState(targetColors.onError, animSpec, "onError").value,
        )
        MaterialTheme(colorScheme = animatedColors, content = content)
    }
}

/**
 * 切换深色模式：跟随系统 → 强制深色 → 强制浅色 → 跟随系统
 * 同时更新全局状态（触发重组）和 SharedPreferences（持久化）
 */
fun toggleDarkMode(context: Context) {
    val old = darkModeState.intValue
    val next = (old + 1) % 3
    darkModeState.intValue = next
    context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        .edit().putInt(KEY_DARK_MODE, next).apply()
    android.util.Log.i("Theme", "toggleDarkMode: $old -> $next (0=系统,1=深色,2=浅色)")
}
