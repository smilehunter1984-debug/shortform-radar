package com.comma.callback

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : AppCompatActivity() {

    private val fmt = SimpleDateFormat("M/d HH:mm:ss", Locale.KOREA)
    private var items: List<LogEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        findViewById<ListView>(R.id.list).adapter = adapter
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() {
        super.onResume()
        Thread {
            val db = LogDb.get(this)
            val list = db.recent()
            val today = db.sentToday()
            runOnUiThread {
                items = list
                adapter.notifyDataSetChanged()
                val sent = list.count { it.status == LogDb.SENT }
                val failed = list.count { it.status == LogDb.FAILED }
                val skipped = list.count { it.status == LogDb.SKIPPED }
                findViewById<TextView>(R.id.tvSummary).text =
                    "오늘 발송 ${today}건 · 최근 ${list.size}건 중 성공 $sent / 실패 $failed / 안 보냄 $skipped"
            }
        }.start()
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.item_log, parent, false)
            val e = items[position]
            val (label, color) = when (e.status) {
                LogDb.SENT -> "✅ 성공" to R.color.ok
                LogDb.FAILED -> "❌ 실패" to R.color.warn
                LogDb.PENDING -> "⏳ 보내는 중" to R.color.muted
                else -> "➖ 안 보냄" to R.color.muted
            }
            val kind = when (e.kind) {
                "SMS" -> " · 단문"
                "LMS" -> " · 장문"
                "MMS" -> " · 사진"
                "SPLIT" -> " · 나눠 보냄"
                else -> ""
            }
            val dur = if (e.duration > 0) " · ${e.duration}초" else ""
            v.findViewById<TextView>(R.id.tvTop).apply {
                text = "$label  ${Phone.pretty(e.number)}"
                setTextColor(ContextCompat.getColor(this@LogActivity, color))
            }
            v.findViewById<TextView>(R.id.tvBottom).text =
                "${fmt.format(Date(e.time))} · ${e.callType}$dur$kind" +
                    if (e.reason.isNotEmpty()) "\n${e.reason}" else ""
            return v
        }
    }
}
