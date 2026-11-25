@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.lumina.gallery.ai.GeminiResult
import com.lumina.gallery.ai.Ocr
import com.lumina.gallery.ai.Qr
import com.lumina.gallery.ai.QrHit
import com.lumina.gallery.data.formatDuration
import com.lumina.gallery.editor.VideoTrim
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

// ============================================================ OCR

@Composable
fun OcrScreen(vm: GalleryViewModel, id: Long) {
    val item = vm.byId[id]
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Photo not found") }
        return
    }
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Running offline OCR…") }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }

    fun runGemini(b: Bitmap) {
        busy = true
        errors = emptyList()
        status = "Asking Gemini: 2.5-flash → 2.5-flash-lite → 2.0-flash"
        scope.launch {
            when (val r = Ocr.gemini(b, vm.prefs.geminiKey)) {
                is GeminiResult.Ok -> { text = r.text; status = "Gemini (${r.model}) · ${r.text.length} characters" }
                is GeminiResult.Fail -> { errors = r.errors; status = "Gemini failed. Exact errors are shown below; nothing was silently substituted." }
            }
            busy = false
        }
    }

    LaunchedEffect(id) {
        val b = withContext(Dispatchers.IO) { vm.repo.loadBitmap(item.uri, 2048) }
        if (b == null) { status = "Could not open this image"; busy = false; return@LaunchedEffect }
        bmp = b
        val off = try { Ocr.offline(b) } catch (e: Exception) { "" }
        text = off
        if (off.trim().length < 8) {
            if (vm.prefs.geminiKey.isNotBlank()) runGemini(b)
            else { status = "Little or no Latin text found. For Bangla, Arabic and other scripts add a Gemini key (Albums ⋮ → AI OCR Language Key)."; busy = false }
        } else {
            status = "Offline OCR (English / Latin) · ${off.length} characters"
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Extract text", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(status, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = bmp != null && !busy, onClick = {
                val b = bmp ?: return@OutlinedButton
                busy = true
                errors = emptyList()
                scope.launch {
                    text = try { Ocr.offline(b) } catch (e: Exception) { "" }
                    status = "Offline OCR (English / Latin) · ${text.length} characters"
                    busy = false
                }
            }) { Text("Offline (English)") }
            Button(enabled = bmp != null && !busy, onClick = { bmp?.let { runGemini(it) } }) { Text("Gemini (Bangla, Arabic, all)") }
        }
        if (errors.isNotEmpty()) {
            SelectionContainer {
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp).background(Color(0x33FF5252), RoundedCornerShape(12.dp)).padding(10.dp)
                ) { errors.forEach { Text(it, fontSize = 12.sp, color = Color(0xFFFFB4AB)) } }
            }
        }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            textStyle = TextStyle(fontSize = 16.sp),
            placeholder = { Text("Extracted text appears here. You can select and edit it.") },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = text.isNotEmpty(), onClick = { clip.setText(AnnotatedString(text)); vm.toast("Copied") }, modifier = Modifier.weight(1f)) { Text("Copy All") }
            OutlinedButton(enabled = text.isNotEmpty(), onClick = {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                ctx.startActivity(Intent.createChooser(i, "Share text").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.weight(1f)) { Text("Share") }
        }
    }
}

// ============================================================ Lossless video trimmer

@Composable
fun TrimScreen(vm: GalleryViewModel, id: Long) {
    val item = vm.byId[id]
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Video not found") }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dur = item.duration.coerceAtLeast(1000L).toFloat()
    var range by remember { mutableStateOf(0f..dur) }
    var busy by remember { mutableStateOf(false) }
    val player = remember {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(ExoItem.fromUri(item.uri))
            prepare()
        }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LaunchedEffect(range) {
        while (true) {
            delay(100)
            if (player.isPlaying && player.currentPosition >= range.endInclusive) player.pause()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Lossless trim", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        AndroidView(
            factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
            modifier = Modifier.weight(1f).fillMaxWidth()
        )
        RangeSlider(
            value = range,
            onValueChange = { range = it; player.seekTo(it.start.toLong()) },
            valueRange = 0f..dur,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Start ${formatDuration(range.start.toLong())}  ·  End ${formatDuration(range.endInclusive.toLong())}  ·  Length ${formatDuration((range.endInclusive - range.start).toLong())}",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "No re-encoding: the cut snaps to the nearest earlier key-frame, so quality is untouched.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { player.seekTo(range.start.toLong()); player.play() }) { Text("Preview") }
            Button(
                enabled = !busy && range.endInclusive - range.start > 500f,
                onClick = {
                    busy = true
                    val s = range.start.toLong()
                    val e = range.endInclusive.toLong()
                    scope.launch {
                        val dir = File(ctx.cacheDir, "trim").apply { mkdirs() }
                        val out = File(dir, item.name.substringBeforeLast('.') + "_trimmed_${System.currentTimeMillis() / 1000}.mp4")
                        val ok = withContext(Dispatchers.IO) { VideoTrim.trim(ctx, item.uri, s, e, out) }
                        val saved = if (ok) withContext(Dispatchers.IO) { vm.repo.saveFile(out, "Movies/Lumina Trimmed/") } else null
                        out.delete()
                        busy = false
                        vm.toast(if (saved != null) "Saved to Movies/Lumina Trimmed" else "Trim failed for this video")
                        if (saved != null) vm.load()
                    }
                },
                modifier = Modifier.weight(1f)
            ) { Text(if (busy) "Trimming…" else "Trim & save") }
        }
    }
}

// ============================================================ Live QR scanner

@Suppress("UnsafeOptInUsageError")
@Composable
fun QrLiveScreen(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val clip = LocalClipboardManager.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var hit by remember { mutableStateOf<QrHit?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build())
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    DisposableEffect(Unit) {
        onDispose {
            try { provider?.unbindAll() } catch (_: Exception) {}
            executor.shutdown()
            scanner.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(
                factory = { c ->
                    val pv = PreviewView(c)
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        val p = fut.get()
                        provider = p
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null) {
                                proxy.close()
                            } else {
                                val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                                scanner.process(img)
                                    .addOnSuccessListener { list -> list.firstNotNullOfOrNull { Qr.toHit(it) }?.let { hit = it } }
                                    .addOnCompleteListener { proxy.close() }
                            }
                        }
                        try {
                            p.unbindAll()
                            p.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                        } catch (e: Exception) {
                            vm.toast("Camera unavailable: ${e.message}")
                        }
                    }, ContextCompat.getMainExecutor(c))
                    pv
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is needed to scan QR codes.", color = Color.White)
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 12.dp)) { Text("Allow camera") }
            }
        }

        IconButton(onClick = { vm.pop() }, modifier = Modifier.statusBarsPadding().padding(8.dp).align(Alignment.TopStart)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
        }
        Text(
            "Point the camera at a QR code or barcode", color = Color.White, fontSize = 13.sp,
            modifier = Modifier.statusBarsPadding().padding(top = 18.dp).align(Alignment.TopCenter)
        )

        val h = hit
        if (h != null) {
            Surface(
                shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (h.isUrl) "Link detected" else "Code detected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    SelectionContainer { Text(h.value, maxLines = 6, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (h.isUrl) Button(onClick = {
                            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, h.value.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                        }) { Text("Open") }
                        OutlinedButton(onClick = { clip.setText(AnnotatedString(h.value)); vm.toast("Copied") }) { Text("Copy") }
                        OutlinedButton(onClick = {
                            val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, h.value) }
                            ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }) { Text("Share") }
                        TextButton(onClick = { hit = null }) { Text("Scan again") }
                    }
                }
            }
        }
    }
}
