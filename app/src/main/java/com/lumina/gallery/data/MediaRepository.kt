package com.lumina.gallery.data

import android.app.WallpaperManager
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.database.ContentObserver
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.debounce
import java.io.File
import java.io.OutputStream
import kotlin.math.max

class MediaRepository(private val ctx: Context) {
    val cr: ContentResolver = ctx.contentResolver
    private val files: Uri = MediaStore.Files.getContentUri("external")

    private val proj = arrayOf(
        MediaStore.Files.FileColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DATE_TAKEN,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.IS_FAVORITE
    )

    /** One cursor over images + videos + PDFs. Handles 17,000+ rows in well under a second. */
    fun query(trashed: Boolean): List<Media> {
        val out = ArrayList<Media>(20000)
        val args = Bundle().apply {
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) OR ${MediaStore.MediaColumns.MIME_TYPE} = 'application/pdf')" +
                    " AND ${MediaStore.MediaColumns.SIZE} > 0"
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, if (trashed) MediaStore.MATCH_ONLY else MediaStore.MATCH_EXCLUDE)
        }
        val c = try {
            cr.query(files, proj, args, null)
        } catch (e: Exception) {
            null
        } ?: return out
        c.use {
            val iId = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val iName = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val iMime = it.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val iType = it.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            val iSize = it.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val iMod = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val iTaken = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val iBid = it.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
            val iBname = it.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            val iRel = it.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val iDur = it.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
            val iW = it.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val iH = it.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val iFav = it.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
            while (it.moveToNext()) {
                val id = it.getLong(iId)
                val type = it.getInt(iType)
                val rel = it.getString(iRel) ?: ""
                var bucket = it.getString(iBname) ?: ""
                if (bucket.isEmpty()) bucket = rel.trimEnd('/').substringAfterLast('/').ifEmpty { "Other" }
                var bid = it.getLong(iBid)
                if (bid == 0L) bid = rel.hashCode().toLong()
                val base = when (type) {
                    3 -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    1 -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> files
                }
                out.add(
                    Media(
                        id = id,
                        uri = ContentUris.withAppendedId(base, id),
                        name = it.getString(iName) ?: "file",
                        mime = it.getString(iMime) ?: "",
                        isVideo = type == 3,
                        size = it.getLong(iSize),
                        modified = it.getLong(iMod),
                        taken = it.getLong(iTaken),
                        bucketId = bid,
                        bucket = bucket,
                        relPath = rel,
                        duration = it.getLong(iDur),
                        width = it.getInt(iW),
                        height = it.getInt(iH),
                        favorite = it.getInt(iFav) == 1
                    )
                )
            }
        }
        return out
    }

    /** Debounced by 1.5 s so bursts of MediaStore notifications never trigger repeated reloads. */
    @OptIn(FlowPreview::class)
    fun changes(): Flow<Unit> = callbackFlow {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        cr.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, obs)
        cr.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, obs)
        cr.registerContentObserver(files, true, obs)
        awaitClose { cr.unregisterContentObserver(obs) }
    }.debounce(1500)

    // ---------- bitmaps ----------
    fun loadBitmap(uri: Uri, maxDim: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            dec.isMutableRequired = true
            val w = info.size.width
            val h = info.size.height
            val s = max(w, h)
            if (s > maxDim) {
                val f = maxDim.toFloat() / s
                dec.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
            }
        }
    } catch (e: Throwable) {
        null
    }

    fun videoFrame(uri: Uri): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, uri)
            r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    // ---------- inserts / copies ----------
    fun normalizeRel(rel: String, isVideo: Boolean, isPdf: Boolean): String {
        val parts = rel.trim().trim('/').split('/').filter { it.isNotEmpty() }
        val allowed = when {
            isPdf -> listOf("Documents", "Download")
            isVideo -> listOf("DCIM", "Movies")
            else -> listOf("DCIM", "Pictures")
        }
        if (parts.isNotEmpty() && parts[0] in allowed) return parts.joinToString("/") + "/"
        val def = if (isPdf) "Documents" else if (isVideo) "Movies" else "Pictures"
        val known = setOf("DCIM", "Pictures", "Movies", "Documents", "Download")
        val rest = if (parts.isNotEmpty() && parts[0] in known) parts.drop(1) else parts
        return (listOf(def) + rest).joinToString("/") + "/"
    }

    fun insertMedia(
        name: String, mime: String, isVideo: Boolean, isPdf: Boolean, relPath: String, taken: Long = 0,
        writer: (OutputStream) -> Unit
    ): Uri? {
        val collection = when {
            isVideo -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isPdf -> MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val cv = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, normalizeRel(relPath, isVideo, isPdf))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (taken > 0) put(MediaStore.MediaColumns.DATE_TAKEN, taken)
        }
        val uri = cr.insert(collection, cv) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { writer(it) } ?: throw IllegalStateException("no stream")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            try { cr.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    fun saveBitmap(bmp: Bitmap, name: String, relPath: String, png: Boolean, quality: Int = 95): Uri? =
        insertMedia(name, if (png) "image/png" else "image/jpeg", false, false, relPath) {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, quality, it)
        }

    fun copy(m: Media, destRel: String): Uri? =
        insertMedia(m.name, m.mime, m.isVideo, m.isPdf, destRel, m.taken) { out ->
            cr.openInputStream(m.uri)?.use { it.copyTo(out, 256 * 1024) }
        }

    fun saveFile(f: File, relPath: String): Uri? {
        val isVideo = f.extension.lowercase() in setOf("mp4", "3gp", "mkv", "webm")
        val mime = if (isVideo) "video/mp4" else if (f.extension.equals("png", true)) "image/png" else "image/jpeg"
        return insertMedia(f.name, mime, isVideo, false, relPath) { out -> f.inputStream().use { it.copyTo(out, 256 * 1024) } }
    }

    /** Moves a file by rewriting its RELATIVE_PATH (needs write access, see GalleryViewModel.withWrite). */
    fun move(m: Media, destRel: String) {
        val v = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, normalizeRel(destRel, m.isVideo, m.isPdf)) }
        cr.update(m.uri, v, null, null)
    }

    fun rename(m: Media, newName: String) {
        var n = newName.trim()
        val ext = m.name.substringAfterLast('.', "")
        if (ext.isNotEmpty() && !n.contains('.')) n = "$n.$ext"
        cr.update(m.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, n) }, null, null)
    }

    fun setWallpaper(m: Media, which: Int): Boolean = try {
        cr.openInputStream(m.uri)?.use { WallpaperManager.getInstance(ctx).setStream(it, null, true, which) }
        true
    } catch (e: Exception) {
        false
    }

    /** Re-encodes the image so no EXIF / GPS metadata survives. */
    fun cleanBitmap(m: Media): Bitmap? = loadBitmap(m.uri, 8192)

    fun cleanCopy(m: Media): Uri? {
        val bmp = cleanBitmap(m) ?: return null
        val png = m.mime == "image/png"
        val base = m.name.substringBeforeLast('.')
        return saveBitmap(bmp, "${base}_clean." + if (png) "png" else "jpg", m.relPath, png)
    }

    fun fileProviderUri(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)

    /** EXIF/GPS-free temp file for "Safe Share". */
    fun safeShareFile(m: Media): Uri? {
        val bmp = loadBitmap(m.uri, 4096) ?: return null
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "safe_${System.currentTimeMillis()}_${m.id}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        return fileProviderUri(f)
    }

    // ---------- EXIF X-Ray ----------
    fun exif(m: Media): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        out.add("File" to m.name)
        out.add("Folder" to m.relPath.ifEmpty { m.bucket })
        out.add("Size" to formatSize(m.size))
        out.add("Type" to m.mime)
        if (m.width > 0) out.add("Dimensions" to "${m.width} × ${m.height}")
        if (!m.isImage) return out
        val stream = try {
            cr.openInputStream(MediaStore.setRequireOriginal(m.uri))
        } catch (e: Exception) {
            try { cr.openInputStream(m.uri) } catch (e2: Exception) { null }
        } ?: return out
        try {
            stream.use {
                val e = ExifInterface(it)
                fun add(label: String, tag: String) {
                    e.getAttribute(tag)?.takeIf { v -> v.isNotBlank() }?.let { v -> out.add(label to v) }
                }
                add("Camera make", ExifInterface.TAG_MAKE)
                add("Camera model", ExifInterface.TAG_MODEL)
                add("Lens", ExifInterface.TAG_LENS_MODEL)
                add("Taken", ExifInterface.TAG_DATETIME_ORIGINAL)
                add("Exposure", ExifInterface.TAG_EXPOSURE_TIME)
                add("Aperture f/", ExifInterface.TAG_F_NUMBER)
                add("ISO", ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                add("Focal length", ExifInterface.TAG_FOCAL_LENGTH)
                add("Flash", ExifInterface.TAG_FLASH)
                add("White balance", ExifInterface.TAG_WHITE_BALANCE)
                add("Software", ExifInterface.TAG_SOFTWARE)
                val ll = e.latLong
                if (ll != null) out.add("GPS" to String.format("%.5f, %.5f", ll[0], ll[1]))
            }
        } catch (_: Exception) {
        }
        return out
    }
}
