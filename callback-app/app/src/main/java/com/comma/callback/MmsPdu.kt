package com.comma.callback

import java.io.ByteArrayOutputStream

/**
 * 장문(LMS)을 보내기 위한 MMS "보내기 요청(M-Send.req)" 데이터를 만든다.
 * (OMA MMS 1.2 / WAP-230 규격의 최소 구성: 텍스트 파트 1개)
 * 2단계에서 사진 파트를 여기에 추가하면 사진 문자(MMS)가 된다.
 */
object MmsPdu {

    fun buildSendReq(to: String, text: String, subject: String? = null, images: List<ByteArray> = emptyList()): ByteArray {
        val out = ByteArrayOutputStream()

        // X-Mms-Message-Type: m-send-req
        out.write(0x8C); out.write(0x80)
        // X-Mms-Transaction-ID
        out.write(0x98); writeText(out, "T" + System.currentTimeMillis().toString(16))
        // X-Mms-MMS-Version: 1.2
        out.write(0x8D); out.write(0x92)
        // From: insert-address-token (통신사가 내 번호를 채워 넣음)
        out.write(0x89); out.write(0x01); out.write(0x81)
        // To
        out.write(0x97); writeText(out, "$to/TYPE=PLMN")
        // Subject (UTF-8)
        if (!subject.isNullOrBlank()) {
            out.write(0x96); writeEncodedUtf8(out, subject)
        }
        // X-Mms-Message-Class: personal
        out.write(0x8A); out.write(0x80)
        // X-Mms-Priority: normal
        out.write(0x8F); out.write(0x81)
        // X-Mms-Delivery-Report: no / X-Mms-Read-Report: no
        out.write(0x86); out.write(0x81)
        out.write(0x90); out.write(0x81)
        // Content-Type: application/vnd.wap.multipart.mixed (반드시 마지막 헤더)
        out.write(0x84); out.write(0xA3)

        // ----- 본문(파트 목록): 사진들 + 글자 -----
        writeUintVar(out, images.size + 1)

        for ((i, img) in images.withIndex()) {
            val h = ByteArrayOutputStream().apply {
                write(0x9E) // Content-Type: image/jpeg
                write(0x8E); writeText(this, "image_${i + 1}.jpg")
                write(0xC0); writeText(this, "<image_${i + 1}>")
            }.toByteArray()
            writeUintVar(out, h.size)
            writeUintVar(out, img.size)
            out.write(h)
            out.write(img)
        }

        val data = text.toByteArray(Charsets.UTF_8)
        val headers = ByteArrayOutputStream().apply {
            // Content-Type: text/plain; charset=utf-8
            write(0x03); write(0x83); write(0x81); write(0xEA)
            // Content-Location: text_0.txt
            write(0x8E); writeText(this, "text_0.txt")
            // Content-ID: <text_0>
            write(0xC0); writeText(this, "<text_0>")
        }.toByteArray()

        writeUintVar(out, headers.size)
        writeUintVar(out, data.size)
        out.write(headers)
        out.write(data)

        return out.toByteArray()
    }

    private fun writeText(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        if (b.isNotEmpty() && (b[0].toInt() and 0xFF) > 127) out.write(0x7F)
        out.write(b); out.write(0x00)
    }

    /** Encoded-string-value: Value-length, Charset(UTF-8=106), Text */
    private fun writeEncodedUtf8(out: ByteArrayOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        val len = 1 + b.size + 1 // charset + text + NUL
        writeValueLength(out, len)
        out.write(0xEA)
        out.write(b); out.write(0x00)
    }

    private fun writeValueLength(out: ByteArrayOutputStream, len: Int) {
        if (len < 31) out.write(len) else { out.write(31); writeUintVar(out, len) }
    }

    private fun writeUintVar(out: ByteArrayOutputStream, value: Int) {
        var v = value
        val tmp = ArrayList<Int>()
        tmp.add(v and 0x7F)
        v = v ushr 7
        while (v > 0) {
            tmp.add((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        for (i in tmp.indices.reversed()) out.write(tmp[i])
    }
}
