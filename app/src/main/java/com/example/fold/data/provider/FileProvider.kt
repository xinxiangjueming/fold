package com.example.fold.data.provider

import com.example.fold.data.model.FileItem
import java.io.InputStream
import java.io.OutputStream

interface FileProvider {
    suspend fun listFiles(path: String): List<FileItem>
    suspend fun openFile(path: String): InputStream
    suspend fun getFileSize(path: String): Long
    suspend fun downloadFile(remotePath: String, outputStream: OutputStream, onProgress: ((Long, Long) -> Unit)? = null)
    suspend fun uploadFile(inputStream: InputStream, remotePath: String, onProgress: ((Long, Long) -> Unit)? = null)
    suspend fun deleteFile(path: String): Boolean
    suspend fun rename(oldPath: String, newName: String): Boolean
    suspend fun createDirectory(path: String): Boolean
    fun getRootPath(): String
}
