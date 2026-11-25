package com.comma.callback

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as SysSettings
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/** 처음 설치 후 해야 할 설정을 단계별로 보여주는 화면. */
class SetupActivity : AppCompatActivity() {

    companion object {
        val BASIC_PERMS: Array<String>
            get() {
                val list = mutableListOf(
                    Manifest.permission.READ_PHONE_STATE,
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_CONTACTS,
                    Manifest.permission.SEND_SMS,
                )
                if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
                return list.toTypedArray()
            }

        fun granted(c: Context, p: String) =
            ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

        fun batteryOk(c: Context): Boolean =
            c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)

        fun missingCount(c: Context): Int {
            var n = 0
            val basic = BASIC_PERMS.filter { it != Manifest.permission.SEND_SMS && it != Manifest.permission.READ_CALL_LOG }
            if (!basic.all { granted(c, it) }) n++
            if (!granted(c, Manifest.permission.SEND_SMS) || !granted(c, Manifest.permission.READ_CALL_LOG)) n++
            if (!batteryOk(c)) n++
            return n
        }
    }

    private lateinit var steps: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        steps = findViewById(R.id.steps)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun render() {
        steps.removeAllViews()
        val basic = BASIC_PERMS.filter { it != Manifest.permission.SEND_SMS && it != Manifest.permission.READ_CALL_LOG }
        val basicOk = basic.all { granted(this, it) }
        val smsOk = granted(this, Manifest.permission.SEND_SMS) && granted(this, Manifest.permission.READ_CALL_LOG)

        addStep(
            "1. 기본 권한 허용", basicOk,
            "전화 상태, 연락처(지인 제외용), 알림 권한입니다. 버튼을 누르고 뜨는 창마다 '허용'을 누르세요. " +
                "문자·통화기록 권한 창이 '제한된 설정'이라며 막혀도 정상입니다. 2번에서 풉니다.",
            "권한 요청하기"
        ) {
            ActivityCompat.requestPermissions(this, BASIC_PERMS, 1)
        }

        addStep(
            "2. 문자·통화기록 권한 (제한된 설정 허용)", smsOk,
            "플레이스토어 밖에서 설치한 앱은 문자·통화기록 권한이 잠겨 있습니다.\n" +
                "① 1번 버튼을 먼저 한 번 누른 뒤\n" +
                "② 아래 버튼 → 앱 정보 화면 오른쪽 위 ⋮ → '제한된 설정 허용' → 지문/비밀번호\n" +
                "③ 같은 화면에서 권한 → 문자 → 허용, 통화기록 → 허용\n" +
                "④ 이 화면으로 돌아오기",
            "앱 정보 열기"
        ) { openAppDetails() }

        addStep(
            "3. 배터리 '제한 없음'", batteryOk(this),
            "삼성 절전 기능이 앱을 재우면 문자가 안 나갑니다. 버튼을 누르고 '허용'을 누르세요.\n" +
                "그래도 안 되면: 앱 정보 → 배터리 → '제한 없음' 선택.",
            "배터리 예외 요청"
        ) { requestBatteryExemption() }

        addStep(
            "4. 삼성 절전 예외 + 최근 앱 잠금 (직접 확인)", null,
            "• 설정 → 배터리 → 백그라운드 사용 제한 → '사용하지 않는 앱을 절전 상태로 전환' 끄기, " +
                "또는 '절전 예외 앱'에 이 앱 추가\n" +
                "• 최근 앱 화면에서 이 앱 아이콘을 눌러 '이 앱 잠금' 선택\n" +
                "• 원UI 큰 업데이트 후에는 1~4번을 다시 확인하세요.",
            "배터리 설정 열기"
        ) {
            tryStart(Intent(SysSettings.ACTION_BATTERY_SAVER_SETTINGS))
        }

        val s = Settings(this)
        addStep(
            "5. 시험용 번호 넣기", if (s.testMode) s.testNumbers.isNotEmpty() else true,
            "처음에는 '시험 모드'가 켜져 있어 시험용 번호(가족 폰 등)에서 온 전화에만 문자를 보냅니다. " +
                "기존 콜백앱과 겹치지 않게 하려는 것입니다. 시험이 끝나면 ②에서 시험 모드를 끄세요.",
            "시험용 번호 입력하러 가기"
        ) { startActivity(Intent(this, SettingsActivity::class.java)) }
    }

    private fun addStep(title: String, done: Boolean?, body: String, button: String, onClick: () -> Unit) {
        val dp = resources.displayMetrics.density
        val card = MaterialCardView(this).apply {
            radius = 14 * dp
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (12 * dp).toInt() }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val mark = when (done) { true -> "✅ "; false -> "⬜ "; null -> "👉 " }
        inner.addView(TextView(this).apply {
            text = mark + title
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        inner.addView(TextView(this).apply {
            text = body
            textSize = 14f
            setPadding(0, (6 * dp).toInt(), 0, (6 * dp).toInt())
        })
        if (done != true) {
            inner.addView(MaterialButton(this).apply {
                text = button
                setOnClickListener { onClick() }
            })
        }
        card.addView(inner)
        steps.addView(card)
    }

    private fun openAppDetails() {
        tryStart(Intent(SysSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val ok = tryStart(
            Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
        if (!ok) openAppDetails()
    }

    private fun tryStart(i: Intent): Boolean = try {
        startActivity(i); true
    } catch (_: Exception) {
        false
    }
}
