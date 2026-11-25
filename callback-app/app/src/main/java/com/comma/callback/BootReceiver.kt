package com.comma.callback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 폰 재부팅 직후에는 진행 중인 통화가 없으므로 상태를 초기화
        RuntimeState(context).lastState = "IDLE"
        MonitorService.start(context)
    }
}
