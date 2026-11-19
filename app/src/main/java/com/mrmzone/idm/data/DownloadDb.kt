package com.mrmzone.idm.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DownloadDb(ctx: Context) : SQLiteOpenHelper(ctx, "downloads.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE downloads(
            id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, file_name TEXT NOT NULL, category TEXT,
            total_size INTEGER, downloaded INTEGER, status TEXT, connections INTEGER, resumable INTEGER,
            created_at INTEGER, finished_at INTEGER, last_try INTEGER, error TEXT, segments TEXT,
            temp_path TEXT, final_path TEXT, referer TEXT, user_agent TEXT, cookies TEXT,
            in_queue INTEGER, description TEXT, speed_limit INTEGER,
            kind TEXT, format_spec TEXT, extra TEXT, save_dir TEXT)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
        if (o < 2) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN kind TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN format_spec TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN extra TEXT")
        }
        if (o < 3) db.execSQL("ALTER TABLE downloads ADD COLUMN save_dir TEXT")
    }

    private fun values(d: DownloadItem) = ContentValues().apply {
        put("url", d.url); put("file_name", d.fileName); put("category", d.category.name)
        put("total_size", d.totalSize); put("downloaded", d.downloaded); put("status", d.status.name)
        put("connections", d.connections); put("resumable", if (d.resumable) 1 else 0)
        put("created_at", d.createdAt); put("finished_at", d.finishedAt); put("last_try", d.lastTry)
        put("error", d.error); put("segments", d.segments); put("temp_path", d.tempPath)
        put("final_path", d.finalPath); put("referer", d.referer); put("user_agent", d.userAgent)
        put("cookies", d.cookies); put("in_queue", if (d.inQueue) 1 else 0)
        put("description", d.description); put("speed_limit", d.speedLimitKb)
        put("kind", d.kind); put("format_spec", d.formatSpec); put("extra", d.extra); put("save_dir", d.saveDir)
    }

    @Synchronized fun insert(d: DownloadItem): Long {
        d.id = writableDatabase.insert("downloads", null, values(d)); return d.id
    }
    @Synchronized fun update(d: DownloadItem) {
        writableDatabase.update("downloads", values(d), "id=?", arrayOf(d.id.toString()))
    }
    @Synchronized fun delete(id: Long) { writableDatabase.delete("downloads", "id=?", arrayOf(id.toString())) }

    @Synchronized fun all(): MutableList<DownloadItem> {
        val list = mutableListOf<DownloadItem>()
        readableDatabase.rawQuery("SELECT * FROM downloads ORDER BY id DESC", null).use { c ->
            while (c.moveToNext()) list += read(c)
        }
        return list
    }

    private fun read(c: Cursor): DownloadItem {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        return DownloadItem(
            id = l("id"), url = s("url"), fileName = s("file_name"),
            category = runCatching { Category.valueOf(s("category")) }.getOrDefault(Category.OTHER),
            totalSize = l("total_size"), downloaded = l("downloaded"),
            status = runCatching { Status.valueOf(s("status")) }.getOrDefault(Status.PAUSED),
            connections = l("connections").toInt(), resumable = l("resumable") == 1L,
            createdAt = l("created_at"), finishedAt = l("finished_at"), lastTry = l("last_try"),
            error = s("error"), segments = s("segments"), tempPath = s("temp_path"), finalPath = s("final_path"),
            referer = s("referer"), userAgent = s("user_agent"), cookies = s("cookies"),
            inQueue = l("in_queue") == 1L, description = s("description"), speedLimitKb = l("speed_limit").toInt(),
            kind = s("kind"), formatSpec = s("format_spec"), extra = s("extra"), saveDir = s("save_dir"),
        )
    }
}
