package com.example.fold.data.reader

import com.example.fold.data.model.Chapter
import com.example.fold.data.model.ReaderState
import kotlinx.coroutines.flow.StateFlow

/**
 * 统一内容阅读器接口
 */
interface ContentReader {
    val state: StateFlow<ReaderState>

    /** 打开文件 */
    suspend fun open(filePath: String, encoding: String? = null)

    /** 跳转到指定位置 */
    suspend fun seekTo(position: Long)

    /** 获取章节列表 */
    fun getChapters(): List<Chapter>

    /** 关闭释放资源 */
    fun close()
}
