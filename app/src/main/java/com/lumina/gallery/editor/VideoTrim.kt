package com.lumina.gallery.editor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Lossless trim: streams the encoded samples between two timestamps into a new MP4 without re-encoding.
 * The cut starts on the sync (key) frame at or before the chosen start, which is what makes it lossless.
 */
object VideoTrim {
    fun trim(ctx: Context, uri: Uri, startMs: Long, endMs: Long, out: File): Boolean {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(ctx, uri, null)
            val mx = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val map = HashMap<Int, Int>()
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    ex.selectTrack(i)
                    map[i] = mx.addTrack(f)
                }
            }
            if (map.isEmpty()) return false
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { mx.setOrientationHint(it) }
            } catch (_: Exception) {
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
            mx.start()
            started = true
            ex.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val buf = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            var base = -1L
            var written = 0
            while (true) {
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                val t = ex.sampleTime
                if (t > endMs * 1000) break
                if (base < 0) base = t
                info.set(0, size, max(0L, t - base), ex.sampleFlags)
                val track = map[ex.sampleTrackIndex]
                if (track != null) {
                    mx.writeSampleData(track, buf, info)
                    written++
                }
                ex.advance()
            }
            return written > 0
        } catch (e: Exception) {
            return false
        } finally {
            try { if (started) muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
