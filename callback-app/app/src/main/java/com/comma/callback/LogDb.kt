package com.comma.callback

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class LogEntry(
    val id: Long,
    val time: Long,
    val number: String,
    val callType: String,
    val duration: Long,
    val status: String,
    val kind: String,
    val reason: String,
    val message: String,
)

/**
 * 발송 이력 저장소.
 * status: PENDING(보내는 중) / SENT(성공) / FAILED(실패) / SKIPPED(조건에 안 맞아 안 보냄)
 * kind: SMS(단문) / LMS(장문) / SPLIT(나눠 보낸 단문) / -(보내지 않음)
 */
class LogDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "callback.db", null, 1) {

    companion object {
        const val PENDING = "PENDING"
        const val SENT = "SENT"
        const val FAILED = "FAILED"
        const val SKIPPED = "SKIPPED"

        @Volatile private var instance: LogDb? = null
        fun get(context: Context): LogDb =
            instance ?: synchronized(this) { instance ?: LogDb(context).also { instance = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                time INTEGER NOT NULL,
                number TEXT NOT NULL,
                call_type TEXT NOT NULL,
                duration INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                kind TEXT NOT NULL DEFAULT '-',
                reason TEXT NOT NULL DEFAULT '',
                message TEXT NOT NULL DEFAULT ''
            )"""
        )
        db.execSQL("CREATE INDEX idx_log_number ON log(number, time)")
        db.execSQL("CREATE INDEX idx_log_time ON log(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(
        number: String, callType: String, duration: Long, status: String,
        kind: String, reason: String, message: String,
    ): Long {
        val v = ContentValues().apply {
            put("time", System.currentTimeMillis())
            put("number", number)
            put("call_type", callType)
            put("duration", duration)
            put("status", status)
            put("kind", kind)
            put("reason", reason)
            put("message", message)
        }
        return writableDatabase.insert("log", null, v)
    }

    fun update(id: Long, status: String, reason: String? = null, kind: String? = null) {
        val v = ContentValues().apply {
            put("status", status)
            if (reason != null) put("reason", reason)
            if (kind != null) put("kind", kind)
        }
        writableDatabase.update("log", v, "id=?", arrayOf(id.toString()))
    }

    fun find(id: Long): LogEntry? =
        query("SELECT * FROM log WHERE id=?", arrayOf(id.toString())).firstOrNull()

    fun recent(limit: Int = 300): List<LogEntry> =
        query("SELECT * FROM log ORDER BY time DESC LIMIT $limit", null)

    /** 오늘 0시 이후 실제로 보낸(또는 보내는 중인) 건수 */
    fun sentToday(): Int {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM log WHERE time>=? AND status IN ('$PENDING','$SENT') AND call_type!='테스트'",
            arrayOf(c.timeInMillis.toString())
        ).use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** 이 번호로 since 이후에 보낸 적이 있나 */
    fun sentToSince(number: String, since: Long): Boolean {
        readableDatabase.rawQuery(
            "SELECT 1 FROM log WHERE number=? AND time>=? AND status IN ('$PENDING','$SENT') AND call_type!='테스트' LIMIT 1",
            arrayOf(number, since.toString())
        ).use { return it.moveToFirst() }
    }

    private fun query(sql: String, args: Array<String>?): List<LogEntry> {
        val out = ArrayList<LogEntry>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out += LogEntry(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    time = c.getLong(c.getColumnIndexOrThrow("time")),
                    number = c.getString(c.getColumnIndexOrThrow("number")),
                    callType = c.getString(c.getColumnIndexOrThrow("call_type")),
                    duration = c.getLong(c.getColumnIndexOrThrow("duration")),
                    status = c.getString(c.getColumnIndexOrThrow("status")),
                    kind = c.getString(c.getColumnIndexOrThrow("kind")),
                    reason = c.getString(c.getColumnIndexOrThrow("reason")),
                    message = c.getString(c.getColumnIndexOrThrow("message")),
                )
            }
        }
        return out
    }
}
