package com.comma.callback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File

/** 문자에 같이 보낼 사진(최대 3장). 문자용 크기로 줄여서 앱 안에 저장해 둔다. */
object Attachments {
    const val MAX = 3
    private const val MAX_SIDE = 1280
    private const val TOTAL_BUDGET = 800 * 1024   // 사진 전체 합계 약 800KB
    private const val PER_IMAGE_MAX = 300 * 1024   // 한 장 최대 약 300KB

    private fun dir(c: Context) = File(c.filesDir, "attach").apply { mkdirs() }

    fun list(c: Context): List<File> =
        dir(c).listFiles { f -> f.name.endsWith(".jpg") }?.sortedBy { it.name } ?: emptyList()

    fun clear(c: Context) {
        dir(c).listFiles()?.forEach { it.delete() }
    }

    fun totalBytes(c: Context): Long = list(c).sumOf { it.length() }

    /** 고른 사진들을 줄여서 저장한다. 기존 사진은 지운다. 저장한 장 수를 돌려준다. */
    fun save(c: Context, uris: List<Uri>): Int {
        clear(c)
        val picked = uris.take(MAX)
        if (picked.isEmpty()) return 0
        val budget = minOf(PER_IMAGE_MAX, TOTAL_BUDGET / picked.size)
        var n = 0
        for ((i, uri) in picked.withIndex()) {
            val bmp = decode(c, uri) ?: continue
            val bytes = compress(bmp, budget)
            bmp.recycle()
            File(dir(c), "photo_${i + 1}.jpg").writeBytes(bytes)
            n++
        }
        return n
    }

    private fun decode(c: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder 는 사진 회전 정보(EXIF)를 반영해 준다
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val scale = MAX_SIDE.toFloat() / maxOf(w, h)
                if (scale < 1f) d.setTargetSize((w * scale).toInt(), (h * scale).toInt())
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }

    /** 용량 안에 들어올 때까지 화질과 크기를 낮춘다 */
    private fun compress(src: Bitmap, budget: Int): ByteArray {
        var bmp = src
        while (true) {
            for (q in intArrayOf(85, 75, 65, 55, 45)) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, q, out)
                if (out.size() <= budget) return out.toByteArray()
            }
            val w = (bmp.width * 0.8f).toInt()
            val h = (bmp.height * 0.8f).toInt()
            if (w < 200 || h < 200) {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 40, out)
                return out.toByteArray()
            }
            val smaller = Bitmap.createScaledBitmap(bmp, w, h, true)
            if (bmp !== src) bmp.recycle()
            bmp = smaller
        }
    }
}
