package com.comma.callback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * 전화 상태 방송을 듣는다.
 *  벨 → 끊김           = 부재중
 *  벨 → 통화중 → 끊김  = 받은 전화
 *  통화중 → 끊김       = 건 전화
 * 같은 신호가 두 번 오는 경우가 있어(번호 없는 것 + 있는 것) 상태가 바뀔 때만 처리한다.
 */
class CallStateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val newState = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val ctx = context.applicationContext
        val st = RuntimeState(ctx)
        st.lastDetectedAt = System.currentTimeMillis()

        val prev = st.lastState
        if (newState == prev) return
        st.lastState = newState

        when (newState) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                st.rang = true; st.offhook = false
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                st.offhook = true
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val guess = when {
                    st.rang && !st.offhook -> CallProcessor.TYPE_MISSED
                    st.rang && st.offhook -> CallProcessor.TYPE_ANSWERED
                    else -> CallProcessor.TYPE_OUTGOING
                }
                st.rang = false; st.offhook = false
                val now = System.currentTimeMillis()
                if (now - st.lastCallEndAt < 5000) return
                st.lastCallEndAt = now

                val pending = goAsync()
                Thread {
                    try {
                        CallProcessor.onCallEnded(ctx, guess)
                    } catch (_: Exception) {
                    } finally {
                        pending.finish()
                    }
                }.start()
                MonitorService.refresh(ctx)
            }
        }
    }
}
