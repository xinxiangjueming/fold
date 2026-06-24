package com.example.fold.ui.common

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 避开摄像头（刘海/挖孔）和系统栏的安全区域 Modifier
 */
@Composable
fun Modifier.safeDrawingPadding(): Modifier =
    this.padding(WindowInsets.safeDrawing.asPaddingValues())
