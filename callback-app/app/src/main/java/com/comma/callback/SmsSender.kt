package com.comma.callback

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.core.content.FileProvider
import java.io.File

/** 실제로 문자를 보내는 곳. 결과는 SentReceiver 가 받아서 이력에 기록한다. */
object SmsSender {

    const val ACTION_SENT = "com.comma.callback.SENT"
    const val EXTRA_LOG_ID = "log_id"
    const val EXTRA_KIND = "kind"
    const val EXTRA_PART = "part"
    const val EXTRA_PARTS = "parts"

    fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

    /** 길이에 따라 단문 또는 장문으로 보낸다. 반환값: 사용한 방식(SMS/LMS) */
    fun send(context: Context, logId: Long, number: String, text: String, subject: String? = null): String {
        return if (Phone.smsBytes(text) <= Phone.SMS_MAX_BYTES) {
            sendSms(context, logId, number, text)
            "SMS"
        } else {
            sendLms(context, logId, number, text, subject)
            "LMS"
        }
    }

    fun sendSms(context: Context, logId: Long, number: String, text: String) {
        val sm = smsManager(context)
        val parts = sm.divideMessage(text)
        if (parts.size <= 1) {
            sm.sendTextMessage(number, null, text, sentIntent(context, logId, "SMS", 0, 1), null)
        } else {
            val intents = ArrayList<PendingIntent>()
            for (i in parts.indices) intents += sentIntent(context, logId, "SPLIT", i, parts.size)
            sm.sendMultipartTextMessage(number, null, parts, intents, null)
        }
    }

    fun sendLms(context: Context, logId: Long, number: String, text: String, subject: String? = null) {
        val dir = File(context.cacheDir, "mms").apply { mkdirs() }
        val file = File(dir, "send_$logId.pdu")
        file.writeBytes(MmsPdu.buildSendReq(number, text, subject))
        val uri = FileProvider.getUriForFile(context, context.packageName + ".mmsfiles", file)
        smsManager(context).sendMultimediaMessage(
            context, uri, null, null, sentIntent(context, logId, "LMS", 0, 1)
        )
    }

    private fun sentIntent(context: Context, logId: Long, kind: String, part: Int, parts: Int): PendingIntent {
        val i = Intent(ACTION_SENT).setClass(context, SentReceiver::class.java)
            .putExtra(EXTRA_LOG_ID, logId)
            .putExtra(EXTRA_KIND, kind)
            .putExtra(EXTRA_PART, part)
            .putExtra(EXTRA_PARTS, parts)
        val req = ((logId * 16 + part) % Int.MAX_VALUE).toInt()
        // 장문은 시스템이 결과(HTTP 상태 등)를 덧붙여 주므로 수정 가능(MUTABLE)으로 만든다.
        val mutability =
            if (kind == "LMS" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_IMMUTABLE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutability
        return PendingIntent.getBroadcast(context, req, i, flags)
    }

    fun describeResult(kind: String, code: Int): String {
        if (code == Activity.RESULT_OK) return "성공"
        if (kind == "LMS") {
            return when (code) {
                SmsManager.MMS_ERROR_UNSPECIFIED -> "장문 실패: 알 수 없는 오류(1)"
                SmsManager.MMS_ERROR_INVALID_APN -> "장문 실패: 통신사 MMS 설정(APN) 문제(2)"
                SmsManager.MMS_ERROR_UNABLE_CONNECT_MMS -> "장문 실패: MMS 서버 연결 불가(3)"
                SmsManager.MMS_ERROR_HTTP_FAILURE -> "장문 실패: 통신사 서버 거부(4)"
                SmsManager.MMS_ERROR_IO_ERROR -> "장문 실패: 데이터 읽기/쓰기 오류(5)"
                SmsManager.MMS_ERROR_RETRY -> "장문 실패: 잠시 후 재시도 필요(6)"
                SmsManager.MMS_ERROR_CONFIGURATION_ERROR -> "장문 실패: 폰 MMS 설정 오류(7)"
                SmsManager.MMS_ERROR_NO_DATA_NETWORK -> "장문 실패: 모바일 데이터 꺼짐(8)"
                else -> "장문 실패: 코드 $code"
            }
        }
        return when (code) {
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "실패: 일반 오류(1) – 권한·통신사 차단·잔여건수 확인"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "실패: 비행기 모드/전파 꺼짐(2)"
            SmsManager.RESULT_ERROR_NULL_PDU -> "실패: 메시지 생성 오류(3)"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "실패: 서비스 지역 아님(4)"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "실패: 발송 한도 초과(5)"
            else -> "실패: 코드 $code"
        }
    }
}
