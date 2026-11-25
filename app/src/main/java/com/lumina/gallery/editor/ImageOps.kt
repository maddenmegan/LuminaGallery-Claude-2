package com.lumina.gallery.editor

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

data class Adjust(
    val brightness: Float = 0f,   // -1..1
    val contrast: Float = 1f,     // 0.5..1.6
    val saturation: Float = 1f,   // 0..2
    val warmth: Float = 0f,       // -1..1
    val fade: Float = 0f,         // 0..1
    val vignette: Float = 0f,     // 0..1
    val sharpness: Float = 0f     // 0..1 (applied on Apply)
) {
    val isIdentity: Boolean
        get() = this == Adjust()
}

object Presets {
    val all: List<Pair<String, Adjust>> = listOf(
        "Original" to Adjust(),
        "Vivid" to Adjust(contrast = 1.15f, saturation = 1.45f, sharpness = 0.2f),
        "Warm" to Adjust(warmth = 0.5f, saturation = 1.1f, brightness = 0.03f),
        "Cool" to Adjust(warmth = -0.5f, saturation = 1.05f),
        "B&W" to Adjust(saturation = 0f, contrast = 1.2f),
        "Film" to Adjust(contrast = 0.92f, saturation = 0.85f, fade = 0.35f, warmth = 0.2f, vignette = 0.25f),
        "Fade" to Adjust(contrast = 0.85f, saturation = 0.8f, fade = 0.6f),
        "Drama" to Adjust(contrast = 1.45f, saturation = 0.9f, vignette = 0.5f, sharpness = 0.3f),
        "Soft" to Adjust(brightness = 0.06f, contrast = 0.9f, saturation = 1.1f, fade = 0.15f)
    )
}

object ImageOps {

    fun matrix(a: Adjust): ColorMatrix {
        val sat = ColorMatrix().apply { setSaturation(a.saturation) }
        val c = a.contrast
        val t = 128f * (1f - c) + a.brightness * 255f
        sat.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
        val wr = 1f + 0.18f * a.warmth
        val wb = 1f - 0.18f * a.warmth
        sat.postConcat(ColorMatrix(floatArrayOf(wr, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, wb, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
        val fs = 1f - 0.15f * a.fade
        val ft = 38f * a.fade
        sat.postConcat(ColorMatrix(floatArrayOf(fs, 0f, 0f, 0f, ft, 0f, fs, 0f, 0f, ft, 0f, 0f, fs, 0f, ft, 0f, 0f, 0f, 1f, 0f)))
        return sat
    }

    fun applyAdjust(src: Bitmap, a: Adjust): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix(a)) }
        c.drawBitmap(src, 0f, 0f, p)
        if (a.vignette > 0f) {
            val w = src.width.toFloat()
            val h = src.height.toFloat()
            val vp = Paint().apply {
                shader = RadialGradient(
                    w / 2f, h / 2f, hypot(w, h) / 2f,
                    intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, Color.argb((a.vignette * 200).toInt(), 0, 0, 0)),
                    floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
                )
            }
            c.drawRect(0f, 0f, w, h, vp)
        }
        return if (a.sharpness > 0f) sharpen(out, a.sharpness) else out
    }

    // ---------------- blur / sharpen ----------------
    fun boxBlur(src: IntArray, w: Int, h: Int, r: Int): IntArray {
        if (r < 1) return src.copyOf()
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        blurPass(src, tmp, w, h, r, true)
        blurPass(tmp, out, w, h, r, false)
        return out
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val k = i.coerceIn(0, len - 1)
                val p = src[if (horizontal) line * w + k else k * w + line]
                sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
            }
            for (i in 0 until len) {
                dst[if (horizontal) line * w + i else i * w + line] =
                    ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val ai = (i + r + 1).coerceAtMost(len - 1)
                val ri = (i - r).coerceAtLeast(0)
                val add = src[if (horizontal) line * w + ai else ai * w + line]
                val rem = src[if (horizontal) line * w + ri else ri * w + line]
                sa += (add ushr 24) - (rem ushr 24)
                sr += ((add shr 16) and 255) - ((rem shr 16) and 255)
                sg += ((add shr 8) and 255) - ((rem shr 8) and 255)
                sb += (add and 255) - (rem and 255)
            }
        }
    }

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    fun sharpen(src: Bitmap, amount: Float, radius: Int = 2): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val blur = boxBlur(px, w, h, radius)
        for (i in px.indices) {
            val o = px[i]
            val b = blur[i]
            val r = clamp(((o shr 16) and 255) + (amount * 1.5f * (((o shr 16) and 255) - ((b shr 16) and 255))).toInt())
            val g = clamp(((o shr 8) and 255) + (amount * 1.5f * (((o shr 8) and 255) - ((b shr 8) and 255))).toInt())
            val bl = clamp((o and 255) + (amount * 1.5f * ((o and 255) - (b and 255))).toInt())
            px[i] = (o and -0x1000000) or (r shl 16) or (g shl 8) or bl
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** "AI HD Enhance": 2x Lanczos-style upscale (capped at 4096 px) followed by an unsharp pass. */
    fun hdEnhance(src: Bitmap): Bitmap {
        val longest = max(src.width, src.height)
        val scale = if (longest * 2 <= 4096) 2f else 4096f / longest
        val base = if (scale > 1.01f)
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        else src.copy(Bitmap.Config.ARGB_8888, true)
        return sharpen(base, 0.55f, 1)
    }

    // ---------------- cut-out refine brush ----------------
    fun brush(target: Bitmap, original: Bitmap, from: Pair<Float, Float>, to: Pair<Float, Float>, width: Float, restore: Boolean) {
        val c = Canvas(target)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        if (restore) {
            val src = if (original.width == target.width && original.height == target.height) original
            else Bitmap.createScaledBitmap(original, target.width, target.height, true)
            p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        } else {
            p.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        c.drawLine(from.first, from.second, to.first, to.second, p)
    }

    fun maskStroke(mask: Bitmap, from: Pair<Float, Float>, to: Pair<Float, Float>, width: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = width
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }
        Canvas(mask).drawLine(from.first, from.second, to.first, to.second, p)
    }

    // ---------------- subject cut-out (ML Kit) ----------------
    suspend fun segmentSubject(bmp: Bitmap): Bitmap? {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        val res = seg.process(InputImage.fromBitmap(bmp, 0)).await()
        return res.foregroundBitmap
    }

    // ---------------- Natural Beauty retouch ----------------
    suspend fun beauty(src: Bitmap, strength: Float): Pair<Bitmap, Int> {
        val det = FaceDetection.getClient(
            FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build()
        )
        val faces = det.process(InputImage.fromBitmap(src, 0)).await()
        if (faces.isEmpty()) return src to 0
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        for (f in faces) {
            val b = f.boundingBox
            val padX = (b.width() * 0.12f).toInt()
            val padY = (b.height() * 0.12f).toInt()
            val rect = Rect(
                max(0, b.left - padX), max(0, b.top - padY),
                min(out.width, b.right + padX), min(out.height, b.bottom + padY)
            )
            val w = rect.width()
            val h = rect.height()
            if (w < 8 || h < 8) continue
            val px = IntArray(w * h)
            out.getPixels(px, 0, w, rect.left, rect.top, w, h)
            val blur = boxBlur(px, w, h, max(2, w / 38))
            val keep = 1f - 0.7f * strength
            val cx = w / 2f
            val cy = h / 2f
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val o = px[i]
                val r = (o shr 16) and 255
                val g = (o shr 8) and 255
                val bl = o and 255
                val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * bl
                val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * bl
                if (cb !in 77f..127f || cr !in 133f..173f) continue
                val nx = (x - cx) / cx
                val ny = (y - cy) / cy
                val d = nx * nx + ny * ny
                if (d >= 1f) continue
                val m = (1f - d).coerceIn(0f, 1f) * strength * 0.9f
                val bp = blur[i]
                val sr = ((bp shr 16) and 255) + (r - ((bp shr 16) and 255)) * keep
                val sg = ((bp shr 8) and 255) + (g - ((bp shr 8) and 255)) * keep
                val sb = (bp and 255) + (bl - (bp and 255)) * keep
                val nr = clamp((r + (sr - r) * m + 4 * strength).toInt())
                val ng = clamp((g + (sg - g) * m + 3 * strength).toInt())
                val nb = clamp((bl + (sb - bl) * m + 3 * strength).toInt())
                px[i] = (o and -0x1000000) or (nr shl 16) or (ng shl 8) or nb
            }
            out.setPixels(px, 0, w, rect.left, rect.top, w, h)
        }
        return out to faces.size
    }

    // ---------------- Magic Eraser (classic onion-peel inpainting) ----------------
    fun inpaint(src: Bitmap, mask: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val mpx = IntArray(w * h)
        mask.getPixels(mpx, 0, w, 0, 0, w, h)
        var unknown = BooleanArray(w * h) { (mpx[it] ushr 24) > 40 }
        repeat(2) {
            val c = unknown.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
                if (!c[y * w + x]) continue
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h) unknown[ny * w + nx] = true
                }
            }
        }
        val original = unknown.copyOf()
        var remaining = unknown.count { it }
        if (remaining == 0) return src
        var guard = 0
        while (remaining > 0 && guard++ < 4000) {
            val fill = ArrayList<Int>()
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                if (!unknown[i]) continue
                var has = false
                loop@ for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && !unknown[ny * w + nx]) { has = true; break@loop }
                }
                if (has) fill.add(i)
            }
            if (fill.isEmpty()) break
            val newVals = IntArray(fill.size)
            for ((k, i) in fill.withIndex()) {
                val x = i % w
                val y = i / w
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until w && ny in 0 until h && !unknown[ny * w + nx]) {
                        val p = px[ny * w + nx]
                        sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255; n++
                    }
                }
                newVals[k] = if (n == 0) px[i] else {
                    val j = Random.nextInt(7) - 3
                    (0xFF shl 24) or (clamp(sr / n + j) shl 16) or (clamp(sg / n + j) shl 8) or clamp(sb / n + j)
                }
            }
            for ((k, i) in fill.withIndex()) { px[i] = newVals[k]; unknown[i] = false }
            remaining -= fill.size
        }
        // soften the filled area slightly
        val blur = boxBlur(px, w, h, 2)
        for (i in px.indices) if (original[i]) {
            val o = px[i]
            val b = blur[i]
            val r = (((o shr 16) and 255) + ((b shr 16) and 255)) / 2
            val g = (((o shr 8) and 255) + ((b shr 8) and 255)) / 2
            val bl = ((o and 255) + (b and 255)) / 2
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    // ---------------- palette ----------------
    fun palette(src: Bitmap, n: Int = 6): List<Int> {
        val bmp = Bitmap.createScaledBitmap(src, 64, 64, true)
        val px = IntArray(64 * 64)
        bmp.getPixels(px, 0, 64, 0, 0, 64, 64)
        val bins = HashMap<Int, Int>()
        for (p in px) {
            if ((p ushr 24) < 128) continue
            val key = (((p shr 20) and 15) shl 8) or (((p shr 12) and 15) shl 4) or ((p shr 4) and 15)
            bins[key] = (bins[key] ?: 0) + 1
        }
        val picked = ArrayList<Int>()
        for ((k, _) in bins.entries.sortedByDescending { it.value }) {
            val r = ((k shr 8) and 15) * 17
            val g = ((k shr 4) and 15) * 17
            val b = (k and 15) * 17
            val far = picked.all {
                abs(Color.red(it) - r) + abs(Color.green(it) - g) + abs(Color.blue(it) - b) > 90
            }
            if (far) picked.add(Color.rgb(r, g, b))
            if (picked.size >= n) break
        }
        return picked
    }

    fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
}
