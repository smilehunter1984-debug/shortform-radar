package com.comma.callback

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/** 설정 화면에서 저장한 값을 읽고 쓰는 곳. */
class Settings(context: Context) {
    private val p: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    init {
        PreferenceManager.setDefaultValues(context, R.xml.preferences, false)
    }

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    val message: String get() = p.getString("message", "")!!.trim()
    val lmsFallback: Boolean get() = p.getBoolean("lms_fallback", true)

    val sendAnswered: Boolean get() = p.getBoolean("send_answered", true)
    val sendMissed: Boolean get() = p.getBoolean("send_missed", true)
    val sendOutgoing: Boolean get() = p.getBoolean("send_outgoing", false)

    val excludeContacts: Boolean get() = p.getBoolean("exclude_contacts", true)
    val mobileOnly: Boolean get() = p.getBoolean("mobile_only", true)
    val blockedNumbers: Set<String> get() = Phone.parseList(p.getString("blocked_numbers", ""))

    val intervalDays: Int get() = intOf("interval_days", 30, 0, 3650)
    val dailyLimit: Int get() = intOf("daily_limit", 100, 1, 500)

    /** java.util.Calendar 요일 번호(일=1 … 토=7) */
    val sendDays: Set<Int>
        get() = p.getStringSet("send_days", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
            ?: (1..7).toSet()
    val startHour: Int get() = intOf("start_hour", 9, 8, 21)
    val endHour: Int get() = intOf("end_hour", 20, 8, 21)

    val testMode: Boolean get() = p.getBoolean("test_mode", true)
    val testNumbers: Set<String> get() = Phone.parseList(p.getString("test_numbers", ""))

    private fun intOf(key: String, def: Int, min: Int, max: Int): Int =
        (p.getString(key, null)?.trim()?.toIntOrNull() ?: def).coerceIn(min, max)
}

/** 통화 감지 중 임시로 기억해 두는 값(설정 아님). */
class RuntimeState(context: Context) {
    private val p = context.getSharedPreferences("runtime", Context.MODE_PRIVATE)

    var lastState: String
        get() = p.getString("last_state", "IDLE")!!
        set(v) = p.edit().putString("last_state", v).apply()
    var rang: Boolean
        get() = p.getBoolean("rang", false)
        set(v) = p.edit().putBoolean("rang", v).apply()
    var offhook: Boolean
        get() = p.getBoolean("offhook", false)
        set(v) = p.edit().putBoolean("offhook", v).apply()
    var lastDetectedAt: Long
        get() = p.getLong("last_detected", 0L)
        set(v) = p.edit().putLong("last_detected", v).apply()
    var lastCallEndAt: Long
        get() = p.getLong("last_call_end", 0L)
        set(v) = p.edit().putLong("last_call_end", v).apply()
    var lastProcessedCallDate: Long
        get() = p.getLong("last_processed_call", 0L)
        set(v) = p.edit().putLong("last_processed_call", v).apply()
}
