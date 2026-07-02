package com.example.fold.data.model

import com.example.fold.R

/**
 * 阅读主题预设
 */
enum class ReadingTheme(
    val labelResId: Int,        // 字符串资源 ID
    val bgColor: Long,           // 背景色
    val textColor: Long,         // 文字色
    val isLight: Boolean         // 是否浅色模式（影响状态栏图标）
) {
    LIGHT(R.string.theme_light, 0xFFF5F5F5, 0xFF1F1F1F, true),
    DARK(R.string.theme_dark, 0xFF1A1A1A, 0xFFE0E0E0, false),
    SEPIA(R.string.theme_sepia, 0xFFF5E6C8, 0xFF3E2723, true),
    GREEN(R.string.theme_green, 0xFFCCE8CF, 0xFF1B3A1B, true),
    GRAY(R.string.theme_gray, 0xFFB0B0B0, 0xFF1A1A1A, true),
    INK(R.string.theme_ink, 0xFF2C2C2C, 0xFFCCCCCC, false);

    companion object {
        fun fromName(name: String?) = entries.find { 
            it.name == name 
        } ?: LIGHT
    }
}

/**
 * 阅读器状态
 */
data class ReaderState(
    val filePath: String = "",
    val fileName: String = "",
    val fileType: String = "",       // txt, epub, pdf
    val isLoading: Boolean = true,
    val error: String? = null,
    // TXT 相关
    val chapters: List<Chapter> = emptyList(),
    val currentChapterIndex: Int = 0,
    val currentScrollOffset: Int = 0,
    // PDF 相关
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    // 阅读设置
    val fontSize: Float = 16f,
    val lineSpacing: Float = 1.5f,
    val isDarkMode: Boolean = false,
    val encoding: String = "UTF-8",
    val fontPath: String = "",           // 自定义字体文件路径
    // 阅读主题
    val readingTheme: ReadingTheme = ReadingTheme.LIGHT,
    // 边距
    val marginLeft: Float = 20f,         // 左边距 dp
    val marginRight: Float = 20f,        // 右边距 dp
    // 替换词 Map<原文, 替换词>
    val wordReplacements: Map<String, String> = emptyMap(),
    // 重新分段
    val reSegment: Boolean = false,
    // 繁简转换：0=关闭, 1=转繁体, 2=转简体
    val chineseConvert: Int = 0,
    // 音量键翻页
    val volumePageTurn: Boolean = false,
    // 息屏归隐：息屏时自动保存进度并退回计算器
    val stealthMode: Boolean = false
)

data class Chapter(
    val title: String,
    val startIndex: Int,
    val endIndex: Int
)

data class BookmarkEntry(
    val chapterIndex: Int,
    val chapterTitle: String,
    val scrollOffset: Int,
    val timestamp: Long
)
