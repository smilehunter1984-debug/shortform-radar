package com.comma.callback

object Phone {
    /** 숫자만 남기고 +82 / 82 로 시작하면 0으로 바꾼다. 예) +82 10-1234-5678 → 01012345678 */
    fun normalize(raw: String?): String {
        if (raw == null) return ""
        var d = raw.filter { it.isDigit() }
        if (d.startsWith("82") && d.length >= 11) d = "0" + d.substring(2)
        return d
    }

    fun isMobile(n: String): Boolean =
        n.length in 10..11 && (n.startsWith("010") || n.startsWith("011") || n.startsWith("016") ||
            n.startsWith("017") || n.startsWith("018") || n.startsWith("019"))

    fun parseList(text: String?): Set<String> =
        (text ?: "").split(',', '\n', ' ', ';').map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    fun pretty(n: String): String = when {
        n.length == 11 -> "${n.substring(0, 3)}-${n.substring(3, 7)}-${n.substring(7)}"
        n.length == 10 && n.startsWith("02") -> "02-${n.substring(2, 6)}-${n.substring(6)}"
        n.length == 10 -> "${n.substring(0, 3)}-${n.substring(3, 6)}-${n.substring(6)}"
        n.isEmpty() -> "(번호 없음)"
        else -> n
    }

    /** 한국 문자 기준 바이트 수(한글 등은 2바이트, 영문·숫자는 1바이트). 80바이트 넘으면 장문. */
    fun smsBytes(text: String): Int {
        var n = 0
        for (c in text) n += if (c.code < 0x80) 1 else 2
        return n
    }

    const val SMS_MAX_BYTES = 80
}
