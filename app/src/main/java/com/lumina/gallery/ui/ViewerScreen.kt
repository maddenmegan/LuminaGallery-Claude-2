@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.app.WallpaperManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem as ExoItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.ai.Qr
import com.lumina.gallery.ai.QrHit
import com.lumina.gallery.data.Media
import com.lumina.gallery.editor.ImageOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ViewerScreen(vm: GalleryViewModel, s: Screen.Viewer) {
    val ctx = LocalContext.current
    val pager = rememberPagerState(initialPage = s.index.coerceIn(0, (s.ids.size - 1).coerceAtLeast(0))) { s.ids.size }
    var bars by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var info by remember { mutableStateOf(false) }
    val item = vm.byId[s.ids.getOrNull(pager.currentPage) ?: -1L]
    val inBin = item != null && vm.isTrashed(item.id)

    fun removeCurrent(m: Media) {
        val ids = s.ids.filter { it != m.id }
        if (ids.isEmpty()) vm.pop() else vm.replaceTop(Screen.Viewer(ids, pager.currentPage.coerceAtMost(ids.size - 1)))
    }

    // Zero-click QR / poster auto-detect for the visible photo.
    val qr by produceState<List<QrHit>>(emptyList(), item?.id) {
        value = emptyList()
        val m = item
        if (m != null && m.isImage && !m.isGif) {
            val b = withContext(Dispatchers.IO) { vm.repo.loadBitmap(m.uri, 1600) }
            if (b != null) value = Qr.scan(b)
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pager,
            key = { s.ids[it] },
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val m = vm.byId[s.ids[page]]
            if (m != null) {
                when {
                    m.isVideo -> VideoPage(m, pager.currentPage == page)
                    m.isPdf -> PdfPage(m)
                    else -> {
                        val req = remember(m.id) { ImageRequest.Builder(ctx).data(m.uri).size(4096).memoryCacheKey("full-${m.id}").build() }
                        ZoomableImage(model = req, onTap = { bars = !bars })
                    }
                }
            }
        }

        if (bars && item != null) {
            Row(
                Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp).align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(item.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), fontSize = 15.sp)
                if (!inBin && !item.isPdf) IconButton(onClick = { vm.setFavorite(listOf(item), !item.favorite) }) {
                    Icon(if (item.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorite", tint = if (item.favorite) Color(0xFFFF6B81) else Color.White)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu", tint = Color.White) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (inBin) {
                            DropdownMenuItem(text = { Text("Restore") }, onClick = { menu = false; vm.restore(listOf(item)) { removeCurrent(item) } })
                            DropdownMenuItem(text = { Text("Delete forever") }, onClick = { menu = false; vm.deleteForever(listOf(item)) { removeCurrent(item) } })
                        } else {
                            DropdownMenuItem(text = { Text("Info · EXIF X-Ray · Colors") }, onClick = { menu = false; info = true })
                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; dialog = "rename" })
                            DropdownMenuItem(text = { Text("Copy to folder") }, onClick = { menu = false; dialog = "copy" })
                            DropdownMenuItem(text = { Text("Move to folder") }, onClick = { menu = false; dialog = "move" })
                            if (item.isImage) {
                                DropdownMenuItem(text = { Text("Set as wallpaper") }, onClick = { menu = false; dialog = "wallpaper" })
                                DropdownMenuItem(text = { Text("Remove EXIF (clean copy)") }, onClick = { menu = false; vm.cleanCopy(item) })
                                DropdownMenuItem(text = { Text("Safe Share (strip GPS/EXIF)") }, onClick = { menu = false; vm.safeShare(listOf(item)) })
                                DropdownMenuItem(text = { Text("Extract text (OCR)") }, onClick = { menu = false; vm.push(Screen.Ocr(item.id)) })
                                DropdownMenuItem(text = { Text("Studio Pro") }, onClick = { menu = false; vm.push(Screen.Studio(item.id)) })
                            }
                            if (item.isVideo) DropdownMenuItem(text = { Text("Trim video (lossless)") }, onClick = { menu = false; vm.push(Screen.Trim(item.id)) })
                            DropdownMenuItem(text = { Text("Move to Vault") }, onClick = { menu = false; vm.toVault(listOf(item)) })
                            DropdownMenuItem(text = { Text("Move to Recycle Bin") }, onClick = { menu = false; vm.trash(listOf(item)) { removeCurrent(item) } })
                        }
                    }
                }
            }
        }

        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) {
            if (qr.isNotEmpty()) QrChip(qr[0])
            if (bars && item != null) {
                Row(
                    Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (inBin) {
                        IconButton(onClick = { vm.restore(listOf(item)) { removeCurrent(item) } }) { Icon(Icons.Default.RestoreFromTrash, "Restore", tint = Color.White) }
                        IconButton(onClick = { vm.deleteForever(listOf(item)) { removeCurrent(item) } }) { Icon(Icons.Default.DeleteForever, "Delete forever", tint = Color.White) }
                    } else {
                        IconButton(onClick = { vm.share(listOf(item)) }) { Icon(Icons.Default.Share, "Share", tint = Color.White) }
                        if (item.isImage) IconButton(onClick = { vm.push(Screen.Studio(item.id)) }) { Icon(Icons.Default.AutoFixHigh, "Edit", tint = Color.White) }
                        if (item.isVideo) IconButton(onClick = { vm.push(Screen.Trim(item.id)) }) { Icon(Icons.Default.ContentCut, "Trim", tint = Color.White) }
                        IconButton(onClick = { info = true }) { Icon(Icons.Default.Info, "Info", tint = Color.White) }
                        IconButton(onClick = { vm.trash(listOf(item)) { removeCurrent(item) } }) { Icon(Icons.Default.Delete, "Delete", tint = Color.White) }
                    }
                }
            }
        }
    }

    if (item != null) {
        when (dialog) {
            "rename" -> TextInputDialog("Rename", "File name", initial = item.name, confirm = "Rename", onDismiss = { dialog = "" }) { vm.rename(item, it) }
            "copy" -> FolderPickerDialog(vm, "Copy to folder", onDismiss = { dialog = "" }) { vm.copyTo(listOf(item), it) }
            "move" -> FolderPickerDialog(vm, "Move to folder", onDismiss = { dialog = "" }) { vm.moveTo(listOf(item), it) }
            "wallpaper" -> AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("Set as wallpaper") },
                text = { Text("Choose where to apply this photo.") },
                confirmButton = {
                    Row {
                        TextButton(onClick = { dialog = ""; vm.setWallpaper(item, WallpaperManager.FLAG_SYSTEM) }) { Text("Home") }
                        TextButton(onClick = { dialog = ""; vm.setWallpaper(item, WallpaperManager.FLAG_LOCK) }) { Text("Lock") }
                        TextButton(onClick = { dialog = ""; vm.setWallpaper(item, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) }) { Text("Both") }
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("Cancel") } }
            )
        }
        if (info) InfoSheet(vm, item) { info = false }
    }
}

@Composable
private fun QrChip(hit: QrHit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("QR code detected", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(hit.value, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            if (hit.isUrl) TextButton(onClick = {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, hit.value.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }) { Text("Open") }
            TextButton(onClick = { clip.setText(AnnotatedString(hit.value)) }) { Text("Copy") }
        }
    }
}

@Composable
private fun VideoPage(m: Media, active: Boolean) {
    val ctx = LocalContext.current
    if (!active) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(m.uri).size(800).build(),
            contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        return
    }
    val player = remember(m.id) {
        ExoPlayer.Builder(ctx).build().apply {
            setMediaItem(ExoItem.fromUri(m.uri))
            prepare()
            playWhenReady = false
        }
    }
    DisposableEffect(m.id) { onDispose { player.release() } }
    AndroidView(
        factory = { c -> PlayerView(c).apply { this.player = player; useController = true } },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
private fun PdfPage(m: Media) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null, m.id) {
        value = withContext(Dispatchers.IO) {
            try {
                ctx.contentResolver.openFileDescriptor(m.uri, "r")?.use { pfd ->
                    PdfRenderer(pfd).use { r ->
                        r.openPage(0).use { p ->
                            val w = 1400
                            val h = (w * p.height.toFloat() / p.width).toInt().coerceAtLeast(1)
                            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            b.eraseColor(AColor.WHITE)
                            p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            b
                        }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) ZoomableBitmap(b) else Text("Rendering PDF…", color = Color.Gray)
        Surface(
            onClick = {
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(m.uri, "application/pdf")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Exception) {}
            },
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp)
        ) { Text("Open in PDF app", modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) }
    }
}

@Composable
private fun ZoomableBitmap(b: Bitmap) {
    Image(b.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

@Composable
private fun InfoSheet(vm: GalleryViewModel, m: Media, onDismiss: () -> Unit) {
    val clip = LocalClipboardManager.current
    val exif by produceState<List<Pair<String, String>>>(emptyList(), m.id) {
        value = withContext(Dispatchers.IO) { vm.repo.exif(m) }
    }
    val palette by produceState<List<Int>>(emptyList(), m.id) {
        value = withContext(Dispatchers.IO) {
            val b = if (m.isImage) vm.repo.loadBitmap(m.uri, 256) else if (m.isVideo) vm.repo.videoFrame(m.uri) else null
            if (b != null) ImageOps.palette(b) else emptyList()
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("EXIF X-Ray", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (exif.any { it.first == "GPS" }) Text(
                "This photo contains GPS location data. Use Safe Share or Remove EXIF before sending it.",
                color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp)
            )
            exif.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(0.38f))
                    Text(v, fontSize = 13.sp, modifier = Modifier.weight(0.62f))
                }
            }
            if (palette.isNotEmpty()) {
                Text("Color palette", fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    palette.forEach { c ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { clip.setText(AnnotatedString(ImageOps.hex(c))) }) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Color(c)))
                            Text(ImageOps.hex(c), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Text("Tap a swatch to copy its HEX code", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
