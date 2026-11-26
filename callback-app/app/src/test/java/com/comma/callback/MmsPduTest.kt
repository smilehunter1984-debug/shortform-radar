package com.comma.callback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MmsPduTest {
    private fun u(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun headersAndBody() {
        val text = "안녕하세요 콤마인테리어필름입니다"
        val pdu = MmsPdu.buildSendReq("01012345678", text)

        // 메시지 종류(m-send-req)
        assertArrayEquals(u(0x8C, 0x80), pdu.copyOfRange(0, 2))
        // 거래 ID 헤더 다음 텍스트는 NUL 로 끝남
        assertEquals(0x98, pdu[2].toInt() and 0xFF)
        var i = 3
        while (pdu[i].toInt() != 0) i++
        i++
        // MMS 버전 1.2, From(insert-address)
        assertArrayEquals(u(0x8D, 0x92, 0x89, 0x01, 0x81), pdu.copyOfRange(i, i + 5))
        i += 5
        // To
        assertEquals(0x97, pdu[i].toInt() and 0xFF)
        val to = "01012345678/TYPE=PLMN".toByteArray()
        assertArrayEquals(to, pdu.copyOfRange(i + 1, i + 1 + to.size))
        assertEquals(0, pdu[i + 1 + to.size].toInt())

        // 본문은 UTF-8 텍스트로 끝나야 함
        val data = text.toByteArray(Charsets.UTF_8)
        assertArrayEquals(data, pdu.copyOfRange(pdu.size - data.size, pdu.size))

        // Content-Type multipart.mixed 다음 파트 수 1, 헤더길이, 데이터길이(uintvar)
        val ct = (0 until pdu.size - 1).first { (pdu[it].toInt() and 0xFF) == 0x84 && (pdu[it + 1].toInt() and 0xFF) == 0xA3 }
        assertEquals(1, pdu[ct + 2].toInt())
        val headerLen = pdu[ct + 3].toInt() and 0xFF
        // 데이터 길이 uintvar 해독
        var p = ct + 4
        var len = 0
        while (true) {
            val b = pdu[p++].toInt() and 0xFF
            len = (len shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) break
        }
        assertEquals(data.size, len)
        assertEquals(pdu.size, p + headerLen + len)
        // 파트 Content-Type: text/plain; charset=utf-8
        assertArrayEquals(u(0x03, 0x83, 0x81, 0xEA), pdu.copyOfRange(p, p + 4))
    }

    @Test fun longTextUsesMultiByteLength() {
        val text = "가".repeat(100) // 300바이트 → uintvar 2바이트
        val pdu = MmsPdu.buildSendReq("01012345678", text)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, 300)
        val tail = pdu.copyOfRange(pdu.size - 300, pdu.size)
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), tail)
    }

    @Test fun subjectIsUtf8() {
        val pdu = MmsPdu.buildSendReq("01012345678", "본문", "콤마인테리어필름")
        val subj = "콤마인테리어필름".toByteArray(Charsets.UTF_8)
        val at = (0 until pdu.size - 1).first { (pdu[it].toInt() and 0xFF) == 0x96 }
        // 0x96, 길이, UTF-8(0xEA), 제목, NUL
        assertEquals(subj.size + 2, pdu[at + 1].toInt() and 0xFF)
        assertEquals(0xEA, pdu[at + 2].toInt() and 0xFF)
        assertArrayEquals(subj, pdu.copyOfRange(at + 3, at + 3 + subj.size))
        assertEquals(0, pdu[at + 3 + subj.size].toInt())
    }

    @Test fun imagePartsComeBeforeText() {
        val img1 = ByteArray(500) { 1 }
        val img2 = ByteArray(200) { 2 }
        val text = "사진 문자"
        val pdu = MmsPdu.buildSendReq("01012345678", text, null, listOf(img1, img2))
        val ct = (0 until pdu.size - 1).first { (pdu[it].toInt() and 0xFF) == 0x84 && (pdu[it + 1].toInt() and 0xFF) == 0xA3 }
        var p = ct + 2
        assertEquals(3, pdu[p++].toInt()) // 파트 3개
        fun uv(): Int { var v = 0; while (true) { val b = pdu[p++].toInt() and 0xFF; v = (v shl 7) or (b and 0x7F); if (b and 0x80 == 0) return v } }
        for (img in listOf(img1, img2)) {
            val hl = uv(); val dl = uv()
            assertEquals(0x9E, pdu[p].toInt() and 0xFF) // image/jpeg
            assertEquals(img.size, dl)
            p += hl
            assertArrayEquals(img, pdu.copyOfRange(p, p + dl))
            p += dl
        }
        val hl = uv(); val dl = uv()
        p += hl
        assertArrayEquals(text.toByteArray(Charsets.UTF_8), pdu.copyOfRange(p, p + dl))
        assertEquals(pdu.size, p + dl)
    }
}
