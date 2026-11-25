package com.comma.callback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 상단에 알림을 띄워 두는 상주 서비스.
 * 삼성 절전 기능이 앱을 잠재우지 않도록 앱을 '사용 중' 상태로 유지한다.
 */
class MonitorService : Service() {

    companion object {
        private const val CHANNEL = "monitor"
        private const val NOTI_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, MonitorService::class.java))
            } catch (_: Exception) {
                // 앱이 뒤에 있을 때는 시작이 막힐 수 있다 → 앱을 한 번 열면 다시 시작됨
            }
        }

        fun refresh(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.activeNotifications.any { it.id == NOTI_ID }) {
                try {
                    nm.notify(NOTI_ID, build(context))
                } catch (_: SecurityException) {
                }
            }
        }

        private fun build(context: Context): Notification {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "콜백문자 대기", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "앱이 꺼지지 않도록 상단에 표시되는 알림"
                        setShowBadge(false)
                    }
                )
            }
            val s = Settings(context)
            val last = RuntimeState(context).lastCallEndAt
            val lastText = if (last == 0L) "아직 감지된 통화 없음"
            else "마지막 통화 감지 " + SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(last))
            val title = when {
                !s.enabled -> "자동 발송 꺼짐"
                s.testMode -> "시험 모드로 대기 중"
                else -> "콜백문자 대기 중"
            }
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(lastText)
                .setOngoing(true)
                .setContentIntent(open)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            ServiceCompat.startForeground(this, NOTI_ID, build(this), type)
        } catch (_: Exception) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
