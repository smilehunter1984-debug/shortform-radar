package com.comma.callback

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var swEnabled: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        swEnabled = findViewById(R.id.swEnabled)
        swEnabled.setOnCheckedChangeListener { _, checked ->
            if (checked != settings.enabled) {
                settings.enabled = checked
                MonitorService.refresh(this)
                render()
            }
        }

        findViewById<MaterialButton>(R.id.btnSetup).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnLog).setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnTest).setOnClickListener { showTestDialog() }

        // 처음 실행이면 설정 안내부터
        if (SetupActivity.missingCount(this) > 0 && savedInstanceState == null &&
            !getSharedPreferences("runtime", MODE_PRIVATE).getBoolean("setup_shown", false)
        ) {
            getSharedPreferences("runtime", MODE_PRIVATE).edit().putBoolean("setup_shown", true).apply()
            startActivity(Intent(this, SetupActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        MonitorService.start(this)
        render()
    }

    private fun render() {
        val fmt = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
        val st = RuntimeState(this)
        val missing = SetupActivity.missingCount(this)

        val status = findViewById<TextView>(R.id.tvStatus)
        when {
            !settings.enabled -> {
                status.text = "⏸ 자동 발송 꺼짐"
                status.setTextColor(ContextCompat.getColor(this, R.color.muted))
            }
            missing > 0 -> {
                status.text = "⚠ 설정이 덜 됐어요"
                status.setTextColor(ContextCompat.getColor(this, R.color.warn))
            }
            else -> {
                status.text = if (settings.testMode) "✅ 작동 중 (시험 모드)" else "✅ 작동 중"
                status.setTextColor(ContextCompat.getColor(this, R.color.ok))
            }
        }

        val last = st.lastCallEndAt
        findViewById<TextView>(R.id.tvLast).text =
            "마지막 통화 감지: " + if (last == 0L) "아직 없음" else fmt.format(Date(last))
        Thread {
            val today = LogDb.get(this).sentToday()
            runOnUiThread {
                findViewById<TextView>(R.id.tvToday).text = "오늘 발송: ${today}건 / 최대 ${settings.dailyLimit}건"
            }
        }.start()

        val warn = findViewById<TextView>(R.id.tvWarn)
        if (missing > 0) {
            warn.text = "아직 안 된 설정이 ${missing}개 있습니다. '① 처음 설정 안내'를 눌러 주세요."
            warn.visibility = View.VISIBLE
        } else {
            warn.visibility = View.GONE
        }

        swEnabled.isChecked = settings.enabled
        findViewById<TextView>(R.id.tvMode).text = if (settings.testMode) {
            val n = settings.testNumbers.size
            "시험 모드: 시험용 번호 ${n}개에서 온 전화에만 보냅니다. 고객에게 보내려면 ②에서 시험 모드를 끄세요."
        } else {
            "실제 운영 중: 규칙에 맞는 모든 통화 후 문자를 보냅니다."
        }
    }

    private fun showTestDialog() {
        val input = EditText(this).apply {
            hint = "받을 번호 (예: 01012345678)"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(settings.testNumbers.firstOrNull() ?: "")
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val text = settings.message
        val bytes = Phone.smsBytes(text)
        val photos = Attachments.list(this).size
        val kind = if (photos > 0) "사진 ${photos}장 + 글자" else if (bytes <= Phone.SMS_MAX_BYTES) "단문" else "장문(LMS)"
        MaterialAlertDialogBuilder(this)
            .setTitle("테스트 발송")
            .setMessage("지금 설정된 문자($kind, ${bytes}바이트)를 이 번호로 바로 보냅니다. 발송 규칙은 검사하지 않습니다.")
            .setView(box)
            .setPositiveButton("보내기") { _, _ ->
                val n = Phone.normalize(input.text.toString())
                if (n.length < 9) {
                    Toast.makeText(this, "번호를 확인해 주세요", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                if (text.isBlank()) {
                    Toast.makeText(this, "②에서 문자 내용을 먼저 입력하세요", Toast.LENGTH_SHORT).show(); return@setPositiveButton
                }
                Thread {
                    val db = LogDb.get(this)
                    val id = db.insert(n, CallProcessor.TYPE_TEST, 0, LogDb.PENDING, "-", "", text)
                    val msg = try {
                        val k = SmsSender.send(this, id, n, text, settings.lmsSubject)
                        db.update(id, LogDb.PENDING, kind = k)
                        "보냈습니다. 결과는 '발송 이력'에서 확인하세요."
                    } catch (e: SecurityException) {
                        db.update(id, LogDb.FAILED, reason = "문자 권한 없음")
                        "문자 권한이 없습니다. ① 설정 안내를 확인하세요."
                    } catch (e: Exception) {
                        db.update(id, LogDb.FAILED, reason = "발송 오류: ${e.message}")
                        "발송 오류: ${e.message}"
                    }
                    runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
                }.start()
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
