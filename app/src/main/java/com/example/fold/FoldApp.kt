package com.example.fold

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import com.example.fold.data.manager.AppHider
import com.example.fold.util.FoldLogger
import com.example.fold.util.ShizukuHelper

class FoldApp : Application() {
    override fun onCreate() {
        val t0 = SystemClock.elapsedRealtime()
        super.onCreate()
        val t1 = SystemClock.elapsedRealtime()
        AppContainer.init(this)
        val t2 = SystemClock.elapsedRealtime()
        ShizukuHelper.init()
        val t3 = SystemClock.elapsedRealtime()

        // 监听应用安装/卸载，自动刷新隐藏应用缓存
        registerReceiver(
            object : android.content.BroadcastReceiver() {
                override fun onReceive(context: android.content.Context?, intent: Intent?) {
                    AppHider.invalidateCache()
                }
            },
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
        )

        FoldLogger.i("Fold_Startup", "Application.onCreate: super=${t1-t0}ms, AppContainer=${t2-t1}ms, Shizuku=${t3-t2}ms, total=${t3-t0}ms")
    }
}
