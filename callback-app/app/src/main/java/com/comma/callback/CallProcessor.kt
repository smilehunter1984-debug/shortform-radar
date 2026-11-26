package com.comma.callback

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * 통화가 끝났을 때 실행된다.
 * 1) 통화기록에서 방금 통화의 번호·종류·통화시간을 확정
 * 2) 발송 규칙 검사
 * 3) 보내거나, 안 보낸 이유를 이력에 기록
 */
object CallProcessor {

    const val TYPE_ANSWERED = "받은 전화"
    const val TYPE_MISSED = "부재중"
    const val TYPE_OUTGOING = "건 전화"
    const val TYPE_TEST = "테스트"

    private data class CallInfo(val number: String, val type: String, val duration: Long, val date: Long)

    private val LOCK = Any()

    /** guessType: 통화 상태 변화로 짐작한 종류(통화기록을 못 읽을 때만 사용) */
    fun onCallEnded(context: Context, guessType: String) = synchronized(LOCK) {
        handleCallEnded(context, guessType)
    }

    private fun handleCallEnded(context: Context, guessType: String) {
        val state = RuntimeState(context)
        val db = LogDb.get(context)

        // 통화기록이 저장될 때까지 잠깐 기다렸다가 읽는다(최대 3번 시도)
        var call: CallInfo? = null
        for (attempt in 0 until 3) {
            Thread.sleep(if (attempt == 0) 2000L else 1500L)
            call = latestCall(context)
            if (call != null && call.date > state.lastProcessedCallDate &&
                System.currentTimeMillis() - (call.date + call.duration * 1000) < 10 * 60 * 1000L
            ) break
            call = null
        }

        if (call == null) {
            // 같은 통화에 대한 중복 처리(이미 처리한 통화가 가장 최근 통화)면 조용히 끝낸다
            val latest = latestCall(context)
            if (latest != null && latest.date == state.lastProcessedCallDate) return
            db.insert("", guessType, 0, LogDb.SKIPPED, "-", "번호 확인 실패(통화기록 권한 확인)", "")
            return
        }
        if (call.type == "차단") {
            state.lastProcessedCallDate = call.date
            return
        }
        state.lastProcessedCallDate = call.date

        val settings = Settings(context)
        val skip = checkRules(context, settings, db, call)
        if (skip != null) {
            db.insert(call.number, call.type, call.duration, LogDb.SKIPPED, "-", skip, "")
            return
        }

        val text = settings.message
        val id = db.insert(call.number, call.type, call.duration, LogDb.PENDING, "-", "", text)
        try {
            val kind = SmsSender.send(context, id, call.number, text, settings.lmsSubject)
            db.update(id, LogDb.PENDING, kind = kind)
        } catch (e: Exception) {
            db.update(id, LogDb.FAILED, reason = "발송 오류: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
        }
    }

    /** 보내면 안 되는 이유를 돌려준다. null 이면 보내도 됨. */
    private fun checkRules(context: Context, s: Settings, db: LogDb, call: CallInfo): String? {
        val n = call.number
        if (!s.enabled) return "자동 발송 꺼짐"
        if (n.isEmpty()) return "발신번호 표시 제한"
        if (!hasPerm(context, Manifest.permission.SEND_SMS)) return "문자 권한 없음(제한된 설정 허용 필요)"

        when (call.type) {
            TYPE_ANSWERED -> if (!s.sendAnswered) return "받은 전화는 보내지 않음(설정)"
            TYPE_MISSED -> if (!s.sendMissed) return "부재중은 보내지 않음(설정)"
            TYPE_OUTGOING -> if (!s.sendOutgoing) return "건 전화는 보내지 않음(설정)"
        }

        val isTestNumber = n in s.testNumbers
        if (s.testMode && !isTestNumber) return "시험 모드: 시험용 번호가 아님"

        if (n in s.blockedNumbers) return "수신거부 번호"
        if (s.mobileOnly && !Phone.isMobile(n)) return "휴대폰 번호 아님"

        val reason = timeBlocked(s)
        if (reason != null) return reason

        // 시험 모드의 시험용 번호는 연락처·간격 검사를 건너뛴다(가족 번호로 여러 번 시험할 수 있게)
        if (!(s.testMode && isTestNumber)) {
            if (s.excludeContacts && inContacts(context, n)) return "연락처에 저장된 번호"
            if (s.intervalDays > 0) {
                val since = System.currentTimeMillis() - s.intervalDays * 24L * 60 * 60 * 1000
                if (db.sentToSince(n, since)) return "${s.intervalDays}일 안에 이미 보냄"
            }
        }

        if (db.sentToday() >= s.dailyLimit) return "오늘 발송 한도(${s.dailyLimit}건) 도달"
        if (s.message.isBlank()) return "문자 내용이 비어 있음"
        return null
    }

    private fun timeBlocked(s: Settings): String? {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        if (hour >= 21 || hour < 8) return "야간(21시~08시) 발송 금지"
        if (now.get(Calendar.DAY_OF_WEEK) !in s.sendDays) return "보내지 않는 요일"
        val start = s.startHour
        val end = s.endHour
        if (start < end && (hour < start || hour >= end)) return "발송 시간(${start}~${end}시) 아님"
        return null
    }

    private fun latestCall(context: Context): CallInfo? {
        if (!hasPerm(context, Manifest.permission.READ_CALL_LOG)) return null
        val projection = arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION, CallLog.Calls.DATE)
        val uri = CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter("limit", "1").build()
        return try {
            context.contentResolver.query(uri, projection, null, null, CallLog.Calls.DATE + " DESC")?.use { c ->
                if (!c.moveToFirst()) return null
                val type = when (c.getInt(1)) {
                    CallLog.Calls.INCOMING_TYPE -> TYPE_ANSWERED
                    CallLog.Calls.OUTGOING_TYPE -> TYPE_OUTGOING
                    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE, CallLog.Calls.VOICEMAIL_TYPE -> TYPE_MISSED
                    CallLog.Calls.BLOCKED_TYPE -> "차단"
                    else -> TYPE_MISSED
                }
                CallInfo(Phone.normalize(c.getString(0)), type, c.getLong(2), c.getLong(3))
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun inContacts(context: Context, number: String): Boolean {
        if (!hasPerm(context, Manifest.permission.READ_CONTACTS)) return false
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun hasPerm(context: Context, p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
}
