package com.comma.callback

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, Fragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    override fun onPause() {
        super.onPause()
        MonitorService.refresh(this)
    }

    class Fragment : PreferenceFragmentCompat() {

        private val picker = registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(Attachments.MAX)
        ) { uris ->
            if (uris.isEmpty()) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            Toast.makeText(ctx, "사진을 문자용 크기로 줄이는 중…", Toast.LENGTH_SHORT).show()
            Thread {
                val n = Attachments.save(ctx, uris)
                activity?.runOnUiThread {
                    updatePhotoSummary()
                    Toast.makeText(ctx, "사진 ${n}장 저장됨", Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        private fun updatePhotoSummary() {
            val ctx = context ?: return
            val files = Attachments.list(ctx)
            findPreference<Preference>("pick_photos")?.summary =
                if (files.isEmpty()) "지금은 사진 없음 (글자만 보냄)"
                else "사진 ${files.size}장 첨부됨 · 총 ${Attachments.totalBytes(ctx) / 1024}KB\n" +
                    "사진문자는 모바일 데이터가 켜져 있어야 나갑니다. 실패하면 글자만 다시 보냅니다."
            findPreference<Preference>("clear_photos")?.isVisible = files.isNotEmpty()
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<Preference>("pick_photos")?.setOnPreferenceClickListener {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                true
            }
            findPreference<Preference>("clear_photos")?.setOnPreferenceClickListener {
                Attachments.clear(requireContext())
                updatePhotoSummary()
                Toast.makeText(requireContext(), "첨부 사진을 뺐습니다", Toast.LENGTH_SHORT).show()
                true
            }
            updatePhotoSummary()

            findPreference<EditTextPreference>("message")?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    it.minLines = 4
                    it.setSelection(it.text.length)
                }
                summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                    val t = p.text.orEmpty()
                    if (t.isBlank()) "(비어 있음 – 눌러서 입력)"
                    else {
                        val b = Phone.smsBytes(t)
                        val kind = if (b <= Phone.SMS_MAX_BYTES) "단문" else "장문(LMS) – 모바일 데이터 필요"
                        "$t\n\n${b}바이트 · $kind"
                    }
                }
            }

            for (key in listOf("interval_days", "daily_limit")) {
                findPreference<EditTextPreference>(key)?.apply {
                    setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                        when (key) {
                            "interval_days" -> {
                                val d = p.text?.toIntOrNull() ?: 30
                                if (d == 0) "제한 없음" else "같은 번호에는 ${d}일에 한 번만"
                            }
                            else -> "하루 ${p.text ?: "100"}건까지 (통신사 제한 때문에 100 이하 권장, 최대 500)"
                        }
                    }
                }
            }

            for (key in listOf("blocked_numbers", "test_numbers")) {
                findPreference<EditTextPreference>(key)?.apply {
                    val base = summary?.toString().orEmpty()
                    setOnBindEditTextListener {
                        it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                        it.minLines = 3
                    }
                    summaryProvider = Preference.SummaryProvider<EditTextPreference> { p ->
                        val list = Phone.parseList(p.text)
                        if (list.isEmpty()) "$base\n(없음)"
                        else "$base\n${list.size}개: " + list.take(5).joinToString(", ") { Phone.pretty(it) } +
                            if (list.size > 5) " …" else ""
                    }
                }
            }
        }
    }
}
