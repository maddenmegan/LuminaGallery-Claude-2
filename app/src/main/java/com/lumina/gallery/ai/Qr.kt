package com.lumina.gallery.ai

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

data class QrHit(val value: String, val isUrl: Boolean)

object Qr {
    private val scanner by lazy {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    }

    fun toHit(b: Barcode): QrHit? {
        val v = b.rawValue ?: return null
        val url = b.valueType == Barcode.TYPE_URL || v.startsWith("http://") || v.startsWith("https://")
        return QrHit(v, url)
    }

    /** Zero-click poster auto-detect: used by the photo viewer for every image. */
    suspend fun scan(bmp: Bitmap): List<QrHit> = try {
        scanner.process(InputImage.fromBitmap(bmp, 0)).await().mapNotNull { toHit(it) }.distinctBy { it.value }
    } catch (e: Exception) {
        emptyList()
    }
}
