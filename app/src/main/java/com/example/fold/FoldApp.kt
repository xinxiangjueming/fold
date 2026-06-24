package com.example.fold

import android.app.Application
import android.os.SystemClock
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
        FoldLogger.i("Fold_Startup", "Application.onCreate: super=${t1-t0}ms, AppContainer=${t2-t1}ms, Shizuku=${t3-t2}ms, total=${t3-t0}ms")
    }
}
