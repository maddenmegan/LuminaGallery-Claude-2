package com.lumina.gallery.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.lumina.gallery.data.IndexDb
import com.lumina.gallery.data.IndexRow
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
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Unlimited background indexer. No file cap: every photo, video, screenshot and PDF is processed.
 * PDFs and screenshots go first. Progress is persisted per item, so Pause/Resume and app restarts
 * continue where they left off.
 */
class Indexer(
    private val ctx: Context,
    private val repo: MediaRepository,
    private val db: IndexDb,
    private val scope: CoroutineScope
) {
    val rows = ConcurrentHashMap<Long, IndexRow>()
    val paused = MutableStateFlow(false)
    var total by mutableIntStateOf(0)
        private set
    var done by mutableIntStateOf(0)
        private set
    var running by mutableStateOf(false)
        private set
    var phase by mutableStateOf("Waiting for media")
        private set

    private val loaded = CompletableDeferred<Unit>()
    private var job: Job? = null
    private val labeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
    }
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    init {
        scope.launch(Dispatchers.IO) {
            db.loadIdx().forEach { rows[it.id] = it }
            loaded.complete(Unit)
        }
    }

    fun setPaused(p: Boolean) {
        paused.value = p
        if (p) phase = "Paused"
    }

    private fun priority(m: Media): Int = when {
        m.isPdf -> 0
        m.isScreenshot && !m.isVideo -> 1
        m.isVideo -> 3
        else -> 2
    }

    fun onMediaChanged(list: List<Media>) {
        job?.cancel()
        job = scope.launch(Dispatchers.Default) {
            loaded.await()
            total = list.size
            if (list.isNotEmpty()) {
                val ids = list.map { it.id }.toHashSet()
                val gone = rows.keys.filter { it !in ids }
                gone.forEach { rows.remove(it) }
                if (gone.isNotEmpty()) db.deleteIdx(gone)
            }
            done = list.count { rows.containsKey(it.id) }
            val pending = list.filter { !rows.containsKey(it.id) }
                .sortedWith(compareBy<Media>({ priority(it) }, { -it.modified }))
            if (pending.isEmpty()) {
                running = false
                phase = "Index up to date"
                return@launch
            }
            running = true
            for ((n, m) in pending.withIndex()) {
                if (paused.value) phase = "Paused"
                paused.first { !it }
                phase = when (priority(m)) {
                    0 -> "Reading PDFs"
                    1 -> "Reading screenshots"
                    2 -> "Analysing photos"
                    else -> "Analysing videos"
                }
                val row = process(m)
                rows[m.id] = row
                db.upsertIdx(row)
                done++
                if (n % 20 == 0) yield()
            }
            running = false
            phase = "Index complete"
        }
    }

    private suspend fun process(m: Media): IndexRow {
        var colors = ""
        var rgb = 0
        var labels = ""
        var text = ""
        try {
            when {
                m.isPdf -> text = pdfText(m)
                m.isVideo -> {
                    val bmp = repo.videoFrame(m.uri)
                    if (bmp != null) {
                        val c = ColorTools.analyze(bmp)
                        colors = c.first; rgb = c.second
                        labels = labelOf(bmp)
                    }
                }
                m.isScreenshot -> {
                    val bmp = repo.loadBitmap(m.uri, 1600)
                    if (bmp != null) {
                        val c = ColorTools.analyze(bmp)
                        colors = c.first; rgb = c.second
                        text = ocr(bmp)
                    }
                }
                else -> {
                    val bmp = repo.loadBitmap(m.uri, 384)
                    if (bmp != null) {
                        val c = ColorTools.analyze(bmp)
                        colors = c.first; rgb = c.second
                        labels = labelOf(bmp)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // unreadable file: store an empty row so we never retry it forever
        }
        return IndexRow(m.id, colors, rgb, labels.lowercase(), text.lowercase().take(6000))
    }

    private suspend fun labelOf(bmp: Bitmap): String =
        labeler.process(InputImage.fromBitmap(bmp, 0)).await().joinToString(" ") { it.text }

    private suspend fun ocr(bmp: Bitmap): String =
        recognizer.process(InputImage.fromBitmap(bmp, 0)).await().text

    private suspend fun pdfText(m: Media): String {
        val sb = StringBuilder()
        var pfd: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        try {
            pfd = ctx.contentResolver.openFileDescriptor(m.uri, "r") ?: return ""
            renderer = PdfRenderer(pfd)
            val pages = minOf(3, renderer.pageCount)
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                val w = 1000
                val h = (w * page.height.toFloat() / page.width).toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                sb.append(ocr(bmp)).append('\n')
                bmp.recycle()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // encrypted / corrupt PDF
        } finally {
            try { renderer?.close() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
        }
        return sb.toString()
    }

    // ---------------- search ----------------
    private val hexRe = Regex("^#?[0-9a-fA-F]{6}$")

    /** Instant: filename / folder / kind match works before any indexing. Index adds labels, OCR text and colour. */
    fun search(query: String, all: List<Media>): List<Media> {
        val tokens = query.lowercase().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val groups = tokens.map { Hints.expand(it) }
        val scored = ArrayList<Pair<Media, Int>>()
        for (m in all) {
            val name = m.name.lowercase()
            val folder = m.bucket.lowercase()
            val row = rows[m.id]
            var score = 0
            var ok = true
            for (g in groups) {
                var best = 0
                for (t in g) {
                    var s = 0
                    if (name.contains(t)) s = maxOf(s, 6)
                    if (folder.contains(t)) s = maxOf(s, 4)
                    if (kindMatch(t, m)) s = maxOf(s, 3)
                    if (row != null) {
                        if (row.labels.contains(t)) s = maxOf(s, 5)
                        if (row.text.contains(t)) s = maxOf(s, 5)
                        if (row.colors.isNotEmpty() && colorMatch(t, row)) s = maxOf(s, 4)
                    }
                    if (s > best) best = s
                }
                if (best == 0) { ok = false; break }
                score += best
            }
            if (ok) scored.add(m to score)
        }
        return scored.sortedWith(compareByDescending<Pair<Media, Int>> { it.second }.thenByDescending { it.first.modified })
            .map { it.first }
    }

    private fun kindMatch(t: String, m: Media): Boolean = when (t) {
        "pdf", "document", "documents" -> m.isPdf
        "video", "videos" -> m.isVideo
        "screenshot", "screenshots" -> m.isScreenshot
        "gif", "gifs" -> m.isGif
        "photo", "photos", "image", "images", "picture", "pictures" -> m.isImage
        else -> false
    }

    private fun colorMatch(t: String, row: IndexRow): Boolean {
        if (t in ColorTools.NAMES) return row.colors.split(',').contains(t)
        if (hexRe.matches(t)) {
            val v = t.trimStart('#').toInt(16)
            val dr = Color.red(row.rgb) - ((v shr 16) and 255)
            val dg = Color.green(row.rgb) - ((v shr 8) and 255)
            val db = Color.blue(row.rgb) - (v and 255)
            return dr * dr + dg * dg + db * db < 70 * 70
        }
        return false
    }
}
