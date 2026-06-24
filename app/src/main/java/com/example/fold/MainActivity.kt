package com.example.fold

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.navigation.compose.rememberNavController
import com.example.fold.ui.common.PermissionHandler
import com.example.fold.ui.navigation.AppNavGraph
import com.example.fold.ui.theme.FoldTheme
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper

private const val TAG = "FoldMain"

class MainActivity : AppCompatActivity() {

    private fun isNightMode(): Boolean {
        return (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate(savedInstanceState)
        val t1 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: savedInstanceState=${savedInstanceState != null}, nightMode=${isNightMode()}, super=${t1-t0}ms")

        // 深浅底色，避免开屏白闪
        val forcedDark = com.example.fold.ui.theme.getDarkModePref(this)
        val isDark = when (forcedDark) { 1 -> true; 2 -> false; else -> isNightMode() }
        window.setBackgroundDrawable(ColorDrawable(if (isDark) Color.parseColor("#1A1A1A") else Color.parseColor("#F5F5F5")))
        val t2 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: theme setup=${t2-t1}ms")

        // 沉浸式系统栏全部在 FoldTheme 的 SideEffect 中处理
        setContent {
            FoldTheme {
                PermissionHandler {
                    val navController = rememberNavController()
                    AppNavGraph(navController = navController)
                }
            }
        }
        val t3 = SystemClock.elapsedRealtime()
        FoldLogger.i(TAG, "onCreate: setContent=${t3-t2}ms, total=${t3-t0}ms")
    }

    override fun onResume() {
        super.onResume()
        FoldLogger.d(TAG, "onResume: rechecking Shizuku")
        // 从 Shizuku 切回来时重新检查状态
        ShizukuHelper.recheck()
    }
}
