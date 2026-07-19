package com.example.fold.ui.common

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException

/**
 * 预测性返回容器：拦截系统返回手势，触发返回导航
 */
@Composable
fun PredictiveBackScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    PredictiveBackHandler(enabled = enabled) {
        try {
            it.collect {}
            onBack()
        } catch (_: CancellationException) {
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        content()
    }
}
