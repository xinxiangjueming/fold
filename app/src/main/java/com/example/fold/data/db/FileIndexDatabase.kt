package com.example.fold.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class FileIndexDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COL_PATH TEXT PRIMARY KEY,
                $COL_NAME TEXT NOT NULL,
                $COL_NAME_LOWER TEXT NOT NULL,
                $COL_IS_DIR INTEGER NOT NULL DEFAULT 0,
                $COL_SIZE INTEGER NOT NULL DEFAULT 0,
                $COL_LAST_MODIFIED INTEGER NOT NULL DEFAULT 0,
                $COL_EXTENSION TEXT NOT NULL DEFAULT '',
                $COL_PARENT_PATH TEXT NOT NULL DEFAULT ''
            )
        """)
        db.execSQL("CREATE INDEX idx_name_lower ON $TABLE_NAME($COL_NAME_LOWER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    companion object {
        const val DB_NAME = "file_index.db"
        const val DB_VERSION = 1
        const val TABLE_NAME = "file_index"
        const val COL_PATH = "path"
        const val COL_NAME = "name"
        const val COL_NAME_LOWER = "name_lower"
        const val COL_IS_DIR = "is_dir"
        const val COL_SIZE = "size"
        const val COL_LAST_MODIFIED = "last_modified"
        const val COL_EXTENSION = "extension"
        const val COL_PARENT_PATH = "parent_path"
    }
}
