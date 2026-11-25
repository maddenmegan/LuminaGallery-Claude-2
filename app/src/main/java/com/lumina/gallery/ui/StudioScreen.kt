@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.editor.Adjust
import com.lumina.gallery.editor.ImageOps
import com.lumina.gallery.editor.Presets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

private enum class Tool(val label: String) {
    CROP("Crop"), ADJUST("Adjust"), PRESETS("Presets"), CUTOUT("Cutout"), RETOUCH("Retouch"), ERASER("Eraser"), ENHANCE("HD Enhance")
}

private data class FRect(val l: Float, val t: Float, val r: Float, val b: Float)

private fun hitTest(p: Offset, l: Float, t: Float, r: Float, b: Float, th: Float): Int {
    val inY = p.y > t - th && p.y < b + th
    val inX = p.x > l - th && p.x < r + th
    var h = 0
    if (abs(p.x - l) < th && inY) h = h or 1
    if (abs(p.y - t) < th && inX) h = h or 2
    if (abs(p.x - r) < th && inY) h = h or 4
    if (abs(p.y - b) < th && inX) h = h or 8
    if (h == 0 && p.x in l..r && p.y in t..b) h = 16
    return h
}

private fun applyDrag(c: FRect, h: Int, dx: Float, dy: Float): FRect {
    val mn = 0.08f
    if (h == 16) {
        val w = c.r - c.l
        val hh = c.b - c.t
        val nl = (c.l + dx).coerceIn(0f, 1f - w)
        val nt = (c.t + dy).coerceIn(0f, 1f - hh)
        return FRect(nl, nt, nl + w, nt + hh)
    }
    var l = c.l
    var t = c.t
    var r = c.r
    var b = c.b
    if ((h and 1) != 0) l = (l + dx).coerceIn(0f, r - mn)
    if ((h and 4) != 0) r = (r + dx).coerceIn(l + mn, 1f)
    if ((h and 2) != 0) t = (t + dy).coerceIn(0f, b - mn)
    if ((h and 8) != 0) b = (b + dy).coerceIn(t + mn, 1f)
    return FRect(l, t, r, b)
}

private fun aspectRect(ar: Float, bw: Int, bh: Int): FRect {
    val imgAr = bw.toFloat() / bh
    return if (ar > imgAr) {
        val h = imgAr / ar
        FRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
    } else {
        val w = ar / imgAr
        FRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
    }
}

private fun DrawScope.checker(l: Float, t: Float, w: Float, h: Float) {
    val s = 16.dp.toPx()
    var y = 0f
    var row = 0
    while (y < h) {
        var x = 0f
        var col = row
        while (x < w) {
            drawRect(
                if (col % 2 == 0) Color(0xFF3A3A3A) else Color(0xFF2A2A2A),
                Offset(l + x, t + y), Size(min(s, w - x), min(s, h - y))
            )
            x += s
            col++
        }
        y += s
        row++
    }
}

@Composable
fun StudioScreen(vm: GalleryViewModel, id: Long) {
    val item = vm.byId[id]
    if (item == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Photo not found") }
        return
    }
    val scope = rememberCoroutineScope()

    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val undo = remember { mutableStateListOf<Bitmap>() }
    var tool by remember { mutableStateOf(Tool.ADJUST) }
    var adj by remember { mutableStateOf(Adjust()) }
    var crop by remember { mutableStateOf(FRect(0f, 0f, 1f, 1f)) }
    var cutout by remember { mutableStateOf<Bitmap?>(null) }
    var mask by remember { mutableStateOf<Bitmap?>(null) }
    var restoreMode by remember { mutableStateOf(false) }
    var brush by remember { mutableFloatStateOf(0.04f) }
    var strength by remember { mutableFloatStateOf(0.6f) }
    var busy by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }

    LaunchedEffect(id) { bmp = withContext(Dispatchers.IO) { vm.repo.loadBitmap(item.uri, 2560) } }

    fun commit(nb: Bitmap) {
        bmp?.let { undo.add(it); if (undo.size > 5) undo.removeAt(0) }
        bmp = nb
        crop = FRect(0f, 0f, 1f, 1f)
        adj = Adjust()
        mask = null
        version++
    }

    fun save(png: Boolean) {
        val b = cutout ?: bmp ?: return
        busy = true
        scope.launch {
            val base = item.name.substringBeforeLast('.')
            val name = "${base}_studio_${System.currentTimeMillis() / 1000}." + if (png) "png" else "jpg"
            val u = withContext(Dispatchers.IO) { vm.repo.saveBitmap(b, name, "Pictures/Lumina Studio/", png) }
            busy = false
            vm.toast(if (u != null) "Saved to Pictures/Lumina Studio" else "Save failed")
            if (u != null) vm.load()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Studio Pro", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(enabled = undo.isNotEmpty(), onClick = { bmp = undo.removeAt(undo.lastIndex); cutout = null; mask = null; version++ }) {
                Icon(Icons.Default.Undo, "Undo")
            }
            Box {
                IconButton(onClick = { saveMenu = true }) { Icon(Icons.Default.Save, "Save") }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Save copy (JPEG)") }, onClick = { saveMenu = false; save(false) })
                    DropdownMenuItem(text = { Text("Save as PNG (keeps transparency)") }, onClick = { saveMenu = false; save(true) })
                }
            }
        }

        // ------------------------------------------------ preview + interaction
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cw = constraints.maxWidth.toFloat()
            val ch = constraints.maxHeight.toFloat()
            val b = bmp
            if (b == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                val co = cutout
                val shown = if (tool == Tool.CUTOUT && co != null) co else b
                val scale = min(cw / shown.width, ch / shown.height)
                val dw = shown.width * scale
                val dh = shown.height * scale
                val left = (cw - dw) / 2f
                val top = (ch - dh) / 2f
                val img = remember(shown, version) { shown.asImageBitmap() }
                val live = tool == Tool.ADJUST || tool == Tool.PRESETS
                val filter = remember(adj, live) {
                    if (live && !adj.isIdentity) ColorFilter.colorMatrix(ComposeColorMatrix(ImageOps.matrix(adj).array)) else null
                }
                val mk = mask

                Canvas(
                    Modifier.fillMaxSize().pointerInput(tool, co != null, scale, shown) {
                        if (tool == Tool.CROP) {
                            var handle = 0
                            detectDragGestures(
                                onDragStart = { p ->
                                    val c = crop
                                    handle = hitTest(p, left + c.l * dw, top + c.t * dh, left + c.r * dw, top + c.b * dh, 56f)
                                },
                                onDrag = { change, d ->
                                    change.consume()
                                    if (handle != 0) crop = applyDrag(crop, handle, d.x / dw, d.y / dh)
                                },
                                onDragEnd = { handle = 0 }
                            )
                        } else if (tool == Tool.CUTOUT && co != null) {
                            var last = Offset.Zero
                            val width = { brush * max(co.width, co.height).toFloat() }
                            detectDragGestures(
                                onDragStart = { p ->
                                    last = p
                                    val bx = (p.x - left) / scale
                                    val by = (p.y - top) / scale
                                    ImageOps.brush(co, b, bx to by, bx to by, width(), restoreMode)
                                    version++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val p = change.position
                                    ImageOps.brush(
                                        co, b, ((last.x - left) / scale) to ((last.y - top) / scale),
                                        ((p.x - left) / scale) to ((p.y - top) / scale), width(), restoreMode
                                    )
                                    last = p
                                    version++
                                }
                            )
                        } else if (tool == Tool.ERASER) {
                            var last = Offset.Zero
                            val width = { brush * max(b.width, b.height).toFloat() }
                            detectDragGestures(
                                onDragStart = { p ->
                                    last = p
                                    val m = mask ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { mask = it }
                                    val bx = (p.x - left) / scale
                                    val by = (p.y - top) / scale
                                    ImageOps.maskStroke(m, bx to by, bx to by, width())
                                    version++
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val m = mask ?: Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888).also { mask = it }
                                    val p = change.position
                                    ImageOps.maskStroke(
                                        m, ((last.x - left) / scale) to ((last.y - top) / scale),
                                        ((p.x - left) / scale) to ((p.y - top) / scale), width()
                                    )
                                    last = p
                                    version++
                                }
                            )
                        }
                    }
                ) {
                    @Suppress("UNUSED_VARIABLE") val tick = version   // redraw when bitmaps change in place
                    if (tool == Tool.CUTOUT && co != null) checker(left, top, dw, dh)
                    drawImage(img, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(dw.roundToInt(), dh.roundToInt()), colorFilter = filter)
                    if (live && adj.vignette > 0f) {
                        drawRect(
                            Brush.radialGradient(
                                listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = adj.vignette * 0.78f)),
                                center = Offset(left + dw / 2f, top + dh / 2f), radius = kotlin.math.hypot(dw, dh) / 2f
                            ),
                            topLeft = Offset(left, top), size = Size(dw, dh)
                        )
                    }
                    if (tool == Tool.ERASER && mk != null) {
                        drawImage(
                            mk.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()), colorFilter = ColorFilter.tint(Color(0xAAFF1744))
                        )
                    }
                    if (tool == Tool.CROP) {
                        val l = left + crop.l * dw
                        val t = top + crop.t * dh
                        val r = left + crop.r * dw
                        val bt = top + crop.b * dh
                        val dim = Color(0xAA000000)
                        drawRect(dim, Offset(left, top), Size(dw, crop.t * dh))
                        drawRect(dim, Offset(left, bt), Size(dw, top + dh - bt))
                        drawRect(dim, Offset(left, t), Size(l - left, bt - t))
                        drawRect(dim, Offset(r, t), Size(left + dw - r, bt - t))
                        drawRect(Color.White, Offset(l, t), Size(r - l, bt - t), style = Stroke(2.dp.toPx()))
                        for (i in 1..2) {
                            val gx = l + (r - l) * i / 3f
                            val gy = t + (bt - t) * i / 3f
                            drawLine(Color(0x88FFFFFF), Offset(gx, t), Offset(gx, bt), 1.dp.toPx())
                            drawLine(Color(0x88FFFFFF), Offset(l, gy), Offset(r, gy), 1.dp.toPx())
                        }
                        val hr = 9.dp.toPx()
                        listOf(l to t, r to t, l to bt, r to bt).forEach { (x, y) -> drawCircle(Color.White, hr, Offset(x, y)) }
                        listOf((l + r) / 2f to t, (l + r) / 2f to bt, l to (t + bt) / 2f, r to (t + bt) / 2f)
                            .forEach { (x, y) -> drawCircle(Color(0xFF8AB4F8), hr * 0.75f, Offset(x, y)) }
                    }
                }
                if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }

        // ------------------------------------------------ tool controls
        Column(Modifier.fillMaxWidth().heightIn(max = 210.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            val b = bmp
            when (tool) {
                Tool.CROP -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                        OutlinedButton(onClick = { crop = FRect(0f, 0f, 1f, 1f) }) { Text("Free") }
                        if (b != null) {
                            OutlinedButton(onClick = { crop = aspectRect(1f, b.width, b.height) }) { Text("1:1") }
                            OutlinedButton(onClick = { crop = aspectRect(4f / 3f, b.width, b.height) }) { Text("4:3") }
                            OutlinedButton(onClick = { crop = aspectRect(16f / 9f, b.width, b.height) }) { Text("16:9") }
                        }
                    }
                    Text("Drag the corners, edges or the box itself.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = b != null && !busy,
                        onClick = {
                            val src = b ?: return@Button
                            val x = (crop.l * src.width).roundToInt().coerceIn(0, src.width - 2)
                            val y = (crop.t * src.height).roundToInt().coerceIn(0, src.height - 2)
                            val w = ((crop.r - crop.l) * src.width).roundToInt().coerceIn(2, src.width - x)
                            val h = ((crop.b - crop.t) * src.height).roundToInt().coerceIn(2, src.height - y)
                            commit(Bitmap.createBitmap(src, x, y, w, h))
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text("Apply crop") }
                }
                Tool.ADJUST -> {
                    @Composable
                    fun slider(label: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(label, fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp).weight(0.28f))
                            Slider(value = v, onValueChange = set, valueRange = range, modifier = Modifier.weight(0.72f))
                        }
                    }
                    slider("Brightness", adj.brightness, -0.5f..0.5f) { adj = adj.copy(brightness = it) }
                    slider("Contrast", adj.contrast, 0.5f..1.6f) { adj = adj.copy(contrast = it) }
                    slider("Saturation", adj.saturation, 0f..2f) { adj = adj.copy(saturation = it) }
                    slider("Warmth", adj.warmth, -1f..1f) { adj = adj.copy(warmth = it) }
                    slider("Fade", adj.fade, 0f..1f) { adj = adj.copy(fade = it) }
                    slider("Vignette", adj.vignette, 0f..1f) { adj = adj.copy(vignette = it) }
                    slider("Sharpness", adj.sharpness, 0f..1f) { adj = adj.copy(sharpness = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { adj = Adjust() }) { Text("Reset") }
                        Button(
                            enabled = b != null && !busy && !adj.isIdentity,
                            onClick = {
                                val src = b ?: return@Button
                                val a = adj
                                busy = true
                                scope.launch { val r = withContext(Dispatchers.Default) { ImageOps.applyAdjust(src, a) }; commit(r); busy = false }
                            }, modifier = Modifier.weight(1f)
                        ) { Text("Apply (sharpness is applied here)") }
                    }
                }
                Tool.PRESETS -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                        items(Presets.all) { (name, a) ->
                            FilterChip(selected = adj == a, onClick = { adj = a }, label = { Text(name) })
                        }
                    }
                    Button(
                        enabled = b != null && !busy && !adj.isIdentity,
                        onClick = {
                            val src = b ?: return@Button
                            val a = adj
                            busy = true
                            scope.launch { val r = withContext(Dispatchers.Default) { ImageOps.applyAdjust(src, a) }; commit(r); busy = false }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Apply preset") }
                }
                Tool.CUTOUT -> {
                    val co = cutout
                    if (co == null) {
                        Text("ML Kit subject segmentation: people, clothes, products and cartoons.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            enabled = b != null && !busy,
                            onClick = {
                                val src = b ?: return@Button
                                busy = true
                                scope.launch {
                                    try {
                                        val r = ImageOps.segmentSubject(src)
                                        if (r != null) cutout = r.copy(Bitmap.Config.ARGB_8888, true) else vm.toast("No subject found")
                                    } catch (e: Exception) {
                                        vm.toast("Segmentation failed: ${e.message}. The model downloads on first use and needs internet once.")
                                    }
                                    busy = false
                                    version++
                                }
                            }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                        ) { Text("Detect subject") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilterChip(selected = !restoreMode, onClick = { restoreMode = false }, label = { Text("Erase") })
                            FilterChip(selected = restoreMode, onClick = { restoreMode = true }, label = { Text("Restore") })
                            Text("Brush", fontSize = 12.sp)
                            Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.01f..0.12f, modifier = Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = { cutout = null; version++ }) { Text("Discard") }
                            OutlinedButton(onClick = { commit(co); cutout = null }) { Text("Use as image") }
                            Button(onClick = { save(true) }, modifier = Modifier.weight(1f)) { Text("Export PNG") }
                        }
                    }
                }
                Tool.RETOUCH -> {
                    Text("Natural Beauty: smooths skin on detected faces while keeping texture.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Strength", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        Slider(value = strength, onValueChange = { strength = it }, valueRange = 0.1f..1f, modifier = Modifier.weight(1f))
                    }
                    Button(
                        enabled = b != null && !busy,
                        onClick = {
                            val src = b ?: return@Button
                            busy = true
                            scope.launch {
                                try {
                                    val (r, n) = withContext(Dispatchers.Default) { ImageOps.beauty(src, strength) }
                                    if (n == 0) vm.toast("No faces detected") else commit(r)
                                } catch (e: Exception) {
                                    vm.toast("Retouch failed: ${e.message}")
                                }
                                busy = false
                            }
                        }, modifier = Modifier.fillMaxWidth()
                    ) { Text("Apply retouch") }
                }
                Tool.ERASER -> {
                    Text("Paint over what you want removed, then tap Erase.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Brush", fontSize = 12.sp, modifier = Modifier.padding(end = 8.dp))
                        Slider(value = brush, onValueChange = { brush = it }, valueRange = 0.01f..0.12f, modifier = Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { mask = null; version++ }) { Text("Clear") }
                        Button(
                            enabled = b != null && mask != null && !busy,
                            onClick = {
                                val src = b ?: return@Button
                                val mk = mask ?: return@Button
                                busy = true
                                scope.launch { val r = withContext(Dispatchers.Default) { ImageOps.inpaint(src, mk) }; commit(r); busy = false }
                            }, modifier = Modifier.weight(1f)
                        ) { Text("Erase") }
                    }
                }
                Tool.ENHANCE -> {
                    Text("Upscales up to 2× (max 4096 px) with edge-aware sharpening. Classical enhancement, not generative.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(
                        enabled = b != null && !busy,
                        onClick = {
                            val src = b ?: return@Button
                            busy = true
                            scope.launch { val r = withContext(Dispatchers.Default) { ImageOps.hdEnhance(src) }; commit(r); busy = false }
                        }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                    ) { Text("Enhance") }
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(Tool.entries.toList()) { t ->
                FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.label) })
            }
        }
    }
}
