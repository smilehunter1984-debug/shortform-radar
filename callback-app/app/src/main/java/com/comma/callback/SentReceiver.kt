package com.comma.callback

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/** 문자 발송 결과를 받아 이력에 성공/실패와 실패 이유를 기록한다. */
class SentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = resultCode
        val logId = intent.getLongExtra(SmsSender.EXTRA_LOG_ID, -1)
        val kind = intent.getStringExtra(SmsSender.EXTRA_KIND) ?: "SMS"
        val part = intent.getIntExtra(SmsSender.EXTRA_PART, 0)
        val parts = intent.getIntExtra(SmsSender.EXTRA_PARTS, 1)
        if (logId < 0) return

        val pending = goAsync()
        Thread {
            try {
                handle(context.applicationContext, logId, kind, part, parts, code)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(ctx: Context, logId: Long, kind: String, part: Int, parts: Int, code: Int) {
        val db = LogDb.get(ctx)
        val entry = db.find(logId) ?: return

        if (kind == "LMS" || kind == "MMS") {
            File(File(ctx.cacheDir, "mms"), "send_$logId.pdu").delete()
        }

        if (code == Activity.RESULT_OK) {
            // 나눠 보낸 문자는 마지막 조각까지 성공해야 성공으로 기록
            if (part == parts - 1 && entry.status != LogDb.FAILED) {
                val note = when {
                    entry.reason.startsWith("장문 실패") -> entry.reason + " → 나눠 보내기 성공"
                    entry.reason.startsWith("사진 실패") -> entry.reason.replace("재발송", "재발송 성공")
                    else -> ""
                }
                db.update(logId, LogDb.SENT, reason = note)
            }
            return
        }

        val why = SmsSender.describeResult(kind, code)
        if (kind == "MMS") {
            // 사진문자 실패 → 사진 없이 글자만 다시 보내기
            val s = Settings(ctx)
            val note = "사진 " + why.removePrefix("장문 ")
            try {
                val k = SmsSender.send(ctx, logId, entry.number, entry.message, s.lmsSubject, withPhotos = false)
                db.update(logId, LogDb.PENDING, reason = "$note → 글자만 재발송", kind = k)
            } catch (e: Exception) {
                db.update(logId, LogDb.FAILED, reason = "$note / 재발송 오류: ${e.message}")
            }
            return
        }
        if (kind == "LMS" && Settings(ctx).lmsFallback) {
            // 장문 실패 → 짧은 문자 여러 개로 다시 보내기
            db.update(logId, LogDb.PENDING, reason = why, kind = "SPLIT")
            try {
                SmsSender.sendSms(ctx, logId, entry.number, entry.message)
            } catch (e: Exception) {
                db.update(logId, LogDb.FAILED, reason = "$why / 재발송 오류: ${e.message}")
            }
        } else {
            db.update(logId, LogDb.FAILED, reason = why)
        }
    }
}
