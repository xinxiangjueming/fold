package com.example.fold.util

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * 沉浸式系统栏（状态栏 + 小白条/导航栏）
 *
 * 使用方式：在 Activity.onCreate() 中调用 setupEdgeToEdge(activity)
 */
object NavigationBarHelper {

    /**
     * Activity.onCreate() 中调用
     * - 让内容延伸到系统栏下方
     * - 状态栏和导航栏全透明（小白条沉浸）
     * - 可选：适配状态栏深浅文字
     *
     * @param activity  目标 Activity（必须是 ComponentActivity 或其子类）
     * @param lightStatusBar true = 状态栏深色图标（浅色背景时用），null = 不设置
     */
    @Suppress("DEPRECATION")
    fun setupEdgeToEdge(activity: ComponentActivity, lightStatusBar: Boolean? = null) {
        val window = activity.window

        // 使用官方 enableEdgeToEdge()
        // 它会自动处理 edge-to-edge 模式、透明系统栏、cutout 等
        // ⚠️ 但它内部会把 isNavigationBarContrastEnforced 重新设为 true
        activity.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT)
        )

        // 必须在 enableEdgeToEdge() 之后关闭 contrast enforcement
        // 否则 Android 15 上导航栏会显示不透明白色遮罩（小白条不沉浸）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // 设置状态栏图标深浅
        if (lightStatusBar != null) {
            setStatusBarAppearance(activity, lightStatusBar)
        }
    }

    // ---------- internal ----------

    private fun setStatusBarAppearance(activity: Activity, light: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = activity.window.insetsController ?: return
            if (light) {
                controller.setSystemBarsAppearance(
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                controller.setSystemBarsAppearance(
                    0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = if (light) {
                @Suppress("DEPRECATION")
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                0
            }
        }
    }
}
