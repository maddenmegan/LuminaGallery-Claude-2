package com.lumina.gallery.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.lumina.gallery.data.IndexDb
import com.lumina.gallery.data.Media
import com.lumina.gallery.data.MediaRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.yield
import java.io.ByteArrayOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Strict face gate + eye-aligned feature embedding.
 * All thresholds are constants so they can be tuned against your own library.
 */
object FaceEngine {
    const val SAME_PERSON_THRESHOLD = 0.72f
    const val MIN_FACE_PX = 70
    const val MIN_SHARPNESS = 18.0
    const val MIN_SKIN_RATIO = 0.25f

    val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.08f)
                .build()
        )
    }

    class Accepted(val emb: FloatArray, val thumb: ByteArray)

    fun evaluate(bmp: Bitmap, face: Face): Accepted? {
        val box = face.boundingBox
        if (min(box.width(), box.height()) < MIN_FACE_PX) return null
        if (abs(face.headEulerAngleZ) > 40f || abs(face.headEulerAngleY) > 50f) return null

        fun lm(type: Int): PointF? = face.getLandmark(type)?.position
        val le = lm(FaceLandmark.LEFT_EYE) ?: return null
        val re = lm(FaceLandmark.RIGHT_EYE) ?: return null
        val nose = lm(FaceLandmark.NOSE_BASE) ?: return null
        val ml = lm(FaceLandmark.MOUTH_LEFT) ?: return null
        val mr = lm(FaceLandmark.MOUTH_RIGHT) ?: return null

        val eyeL = if (le.x <= re.x) le else re
        val eyeR = if (le.x <= re.x) re else le
        val mouthL = if (ml.x <= mr.x) ml else mr
        val mouthR = if (ml.x <= mr.x) mr else ml

        val ex = eyeR.x - eyeL.x
        val ey = eyeR.y - eyeL.y
        val eyeDist = hypot(ex, ey)
        if (eyeDist < 1f) return null
        val ux = ex / eyeDist
        val uy = ey / eyeDist
        val dx = -uy
        val dy = ux
        val midX = (eyeL.x + eyeR.x) / 2f
        val midY = (eyeL.y + eyeR.y) / 2f
        fun down(p: PointF) = ((p.x - midX) * dx + (p.y - midY) * dy) / eyeDist
        fun along(p: PointF) = ((p.x - midX) * ux + (p.y - midY) * uy) / eyeDist
        val mouthMid = PointF((mouthL.x + mouthR.x) / 2f, (mouthL.y + mouthR.y) / 2f)

        // 5-landmark geometry gate
        val eyeRatio = eyeDist / box.width()
        val noseD = down(nose)
        val mouthD = down(mouthMid)
        val mouthW = hypot(mouthR.x - mouthL.x, mouthR.y - mouthL.y) / eyeDist
        if (eyeRatio !in 0.28f..0.70f) return null
        if (noseD !in 0.30f..1.20f) return null
        if (mouthD !in 0.80f..2.00f) return null
        if (mouthD < noseD * 1.15f) return null
        if (mouthW !in 0.40f..1.40f) return null
        if (abs(along(nose)) > 0.40f) return null

        // eye-aligned 64x64 crop (eyes + mouth centre -> fixed template)
        val src = floatArrayOf(eyeL.x, eyeL.y, eyeR.x, eyeR.y, mouthMid.x, mouthMid.y)
        val dst = floatArrayOf(20f, 26f, 44f, 26f, 32f, 50f)
        val m = Matrix()
        if (!m.setPolyToPoly(src, 0, dst, 0, 3)) return null
        val aligned = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        Canvas(aligned).drawBitmap(bmp, m, Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(64 * 64)
        aligned.getPixels(px, 0, 64, 0, 0, 64, 64)
        aligned.recycle()

        // YCbCr skin-tone verification (blocks tomatoes, bottles, cartoons, etc.)
        var skin = 0
        var tot = 0
        for (y in 16..56) for (x in 14..50) {
            val p = px[y * 64 + x]
            val r = (p shr 16) and 255
            val g = (p shr 8) and 255
            val b = p and 255
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cb in 77f..127f && cr in 133f..173f) skin++
            tot++
        }
        if (skin.toFloat() / tot < MIN_SKIN_RATIO) return null

        val gray = FloatArray(64 * 64) {
            val p = px[it]
            0.299f * ((p shr 16) and 255) + 0.587f * ((p shr 8) and 255) + 0.114f * (p and 255)
        }
        if (laplacianVariance(gray) < MIN_SHARPNESS) return null

        return Accepted(embed(gray), thumb(bmp, box))
    }

    private fun laplacianVariance(g: FloatArray): Double {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until 63) for (x in 1 until 63) {
            val c = g[y * 64 + x]
            val l = 4 * c - g[(y - 1) * 64 + x] - g[(y + 1) * 64 + x] - g[y * 64 + x - 1] - g[y * 64 + x + 1]
            sum += l
            sum2 += l * l
            n++
        }
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    /** 16x16 normalised patch (256) + 4x4 grid of 16-bin LBP histograms (256) -> 512 dims, L2-normalised. */
    private fun embed(g: FloatArray): FloatArray {
        val patch = FloatArray(256)
        for (y in 0 until 16) for (x in 0 until 16) {
            var s = 0f
            for (j in 0 until 4) for (i in 0 until 4) s += g[(y * 4 + j) * 64 + x * 4 + i]
            patch[y * 16 + x] = s / 16f
        }
        normalise(patch)

        val hist = FloatArray(256)
        for (y in 1 until 63) for (x in 1 until 63) {
            val c = g[y * 64 + x]
            var code = 0
            if (g[(y - 1) * 64 + x] >= c) code = code or 1
            if (g[y * 64 + x + 1] >= c) code = code or 2
            if (g[(y + 1) * 64 + x] >= c) code = code or 4
            if (g[y * 64 + x - 1] >= c) code = code or 8
            val cell = (y / 16) * 4 + (x / 16)
            hist[cell * 16 + code] += 1f / 256f
        }
        normalise(hist)

        val out = FloatArray(512)
        for (i in 0 until 256) {
            out[i] = patch[i] * 0.7f
            out[256 + i] = hist[i] * 0.7f
        }
        l2(out)
        return out
    }

    private fun normalise(v: FloatArray) {
        var mean = 0f
        v.forEach { mean += it }
        mean /= v.size
        for (i in v.indices) v[i] -= mean
        l2(v)
    }

    fun l2(v: FloatArray) {
        var s = 0f
        v.forEach { s += it * it }
        val n = sqrt(s).coerceAtLeast(1e-6f)
        for (i in v.indices) v[i] /= n
    }

    private fun thumb(bmp: Bitmap, box: Rect): ByteArray {
        val side = (max(box.width(), box.height()) * 1.4f).toInt().coerceAtLeast(16)
        var l = box.centerX() - side / 2
        var t = box.centerY() - side / 2
        l = l.coerceIn(0, max(0, bmp.width - side))
        t = t.coerceIn(0, max(0, bmp.height - side))
        val r = min(bmp.width, l + side)
        val b = min(bmp.height, t + side)
        val out = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, Rect(l, t, r, b), Rect(0, 0, 96, 96), Paint(Paint.FILTER_BITMAP_FLAG))
        val baos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        out.recycle()
        return baos.toByteArray()
    }
}

class FaceScanner(
    private val repo: MediaRepository,
    private val db: IndexDb,
    private val scope: CoroutineScope
) {
    val paused = MutableStateFlow(false)
    var running by mutableStateOf(false)
        private set
    var scanned by mutableIntStateOf(0)
        private set
    var total by mutableIntStateOf(0)
        private set
    var facesFound by mutableIntStateOf(0)
        private set
    var version by mutableIntStateOf(0)
        private set
    var started = false
        private set

    private class Centroid(var vec: FloatArray, var n: Int)

    private val centroids = HashMap<Long, Centroid>()
    private val ready = CompletableDeferred<Unit>()
    private var job: Job? = null

    init {
        scope.launch(Dispatchers.IO) {
            for ((pid, emb) in db.allEmbeddings()) addToCentroid(pid, emb)
            ready.complete(Unit)
        }
    }

    fun setPaused(p: Boolean) { paused.value = p }

    private fun addToCentroid(pid: Long, emb: FloatArray) {
        synchronized(centroids) {
            val c = centroids[pid]
            if (c == null) {
                centroids[pid] = Centroid(emb.copyOf(), 1)
            } else {
                val v = FloatArray(c.vec.size) { c.vec[it] * c.n + emb[it] }
                FaceEngine.l2(v)
                c.vec = v
                c.n = c.n + 1
            }
        }
    }

    private fun bestMatch(emb: FloatArray): Long = synchronized(centroids) {
        var best = -1L
        var bs = -1f
        for ((pid, c) in centroids) {
            var s = 0f
            for (i in emb.indices) s += emb[i] * c.vec[i]
            if (s > bs) { bs = s; best = pid }
        }
        if (best != -1L && bs >= FaceEngine.SAME_PERSON_THRESHOLD) best else -1L
    }

    fun start(list: List<Media>) {
        started = true
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            ready.await()
            val done = db.scannedIds()
            val candidates = list
                .filter { it.isImage && !it.isGif && !it.isScreenshot && it.id !in done }
                .sortedByDescending { it.modified }
            total = candidates.size + done.size
            scanned = done.size
            if (candidates.isEmpty()) { running = false; return@launch }
            running = true
            for ((n, m) in candidates.withIndex()) {
                paused.first { !it }
                try {
                    val bmp = repo.loadBitmap(m.uri, 1280)
                    if (bmp != null) {
                        val faces = FaceEngine.detector.process(InputImage.fromBitmap(bmp, 0)).await()
                        for (f in faces) {
                            val acc = FaceEngine.evaluate(bmp, f) ?: continue
                            var pid = bestMatch(acc.emb)
                            if (pid == -1L) pid = db.newPerson(acc.thumb)
                            db.addFace(m.id, pid, acc.emb)
                            addToCentroid(pid, acc.emb)
                            facesFound++
                        }
                        bmp.recycle()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // skip unreadable image
                }
                db.markScanned(m.id)
                scanned++
                if (n % 10 == 0) { version++; yield() }
            }
            version++
            running = false
        }
    }

    fun merge(target: Long, sources: List<Long>) {
        db.mergePeople(target, sources)
        synchronized(centroids) {
            val t = centroids[target]
            for (s in sources) {
                if (s == target) continue
                val c = centroids.remove(s) ?: continue
                if (t == null) { centroids[target] = c; continue }
                val v = FloatArray(t.vec.size) { t.vec[it] * t.n + c.vec[it] * c.n }
                FaceEngine.l2(v)
                t.vec = v
                t.n += c.n
            }
        }
        version++
    }

    fun rename(pid: Long, name: String) { db.rename(pid, name); version++ }

    fun hide(pids: Collection<Long>, hidden: Boolean) { db.setHidden(pids, hidden); version++ }
}
