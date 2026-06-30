package com.example.fold.data.db

data class FileEntity(
    val path: String,
    val name: String,
    val nameLower: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val extension: String,
    val parentPath: String
)
