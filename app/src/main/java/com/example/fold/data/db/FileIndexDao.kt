package com.example.fold.data.db

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

class FileIndexDao(private val db: SQLiteDatabase) {

    fun insertAll(files: List<FileEntity>) {
        db.beginTransaction()
        try {
            val stmt = db.compileStatement(
                "INSERT OR REPLACE INTO ${FileIndexDatabase.TABLE_NAME} " +
                "(${FileIndexDatabase.COL_PATH},${FileIndexDatabase.COL_NAME},${FileIndexDatabase.COL_NAME_LOWER}," +
                "${FileIndexDatabase.COL_IS_DIR},${FileIndexDatabase.COL_SIZE},${FileIndexDatabase.COL_LAST_MODIFIED}," +
                "${FileIndexDatabase.COL_EXTENSION},${FileIndexDatabase.COL_PARENT_PATH}) " +
                "VALUES (?,?,?,?,?,?,?,?)"
            )
            for (file in files) {
                stmt.bindString(1, file.path)
                stmt.bindString(2, file.name)
                stmt.bindString(3, file.nameLower)
                stmt.bindLong(4, if (file.isDirectory) 1 else 0)
                stmt.bindLong(5, file.size)
                stmt.bindLong(6, file.lastModified)
                stmt.bindString(7, file.extension)
                stmt.bindString(8, file.parentPath)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteAll() {
        db.delete(FileIndexDatabase.TABLE_NAME, null, null)
    }

    fun search(query: String, limit: Int = 200): List<FileEntity> {
        val results = mutableListOf<FileEntity>()
        val cursor = db.query(
            FileIndexDatabase.TABLE_NAME,
            null,
            "${FileIndexDatabase.COL_NAME_LOWER} LIKE ?",
            arrayOf("%$query%"),
            null, null, null,
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(FileEntity(
                    path = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_PATH)),
                    name = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_NAME)),
                    nameLower = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_NAME_LOWER)),
                    isDirectory = it.getInt(it.getColumnIndexOrThrow(FileIndexDatabase.COL_IS_DIR)) == 1,
                    size = it.getLong(it.getColumnIndexOrThrow(FileIndexDatabase.COL_SIZE)),
                    lastModified = it.getLong(it.getColumnIndexOrThrow(FileIndexDatabase.COL_LAST_MODIFIED)),
                    extension = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_EXTENSION)),
                    parentPath = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_PARENT_PATH))
                ))
            }
        }
        return results
    }

    fun searchInPath(query: String, dirPath: String, limit: Int = 200): List<FileEntity> {
        val results = mutableListOf<FileEntity>()
        val cursor = db.query(
            FileIndexDatabase.TABLE_NAME,
            null,
            "${FileIndexDatabase.COL_NAME_LOWER} LIKE ? AND ${FileIndexDatabase.COL_PATH} LIKE ?",
            arrayOf("%$query%", "$dirPath/%"),
            null, null, null,
            limit.toString()
        )
        cursor.use {
            while (it.moveToNext()) {
                results.add(FileEntity(
                    path = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_PATH)),
                    name = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_NAME)),
                    nameLower = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_NAME_LOWER)),
                    isDirectory = it.getInt(it.getColumnIndexOrThrow(FileIndexDatabase.COL_IS_DIR)) == 1,
                    size = it.getLong(it.getColumnIndexOrThrow(FileIndexDatabase.COL_SIZE)),
                    lastModified = it.getLong(it.getColumnIndexOrThrow(FileIndexDatabase.COL_LAST_MODIFIED)),
                    extension = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_EXTENSION)),
                    parentPath = it.getString(it.getColumnIndexOrThrow(FileIndexDatabase.COL_PARENT_PATH))
                ))
            }
        }
        return results
    }

    fun count(): Int {
        val cursor = db.rawQuery("SELECT COUNT(*) FROM ${FileIndexDatabase.TABLE_NAME}", null)
        cursor.use {
            return if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun deleteByPath(dirPath: String) {
        db.delete(FileIndexDatabase.TABLE_NAME, "${FileIndexDatabase.COL_PATH} LIKE ?", arrayOf("$dirPath%"))
    }
}
