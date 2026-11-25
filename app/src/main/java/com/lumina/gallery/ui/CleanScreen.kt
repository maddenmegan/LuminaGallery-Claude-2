@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.content.ContentResolver
import android.os.Environment
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.Media
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.data.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// ============================================================ Clean tab

@Composable
fun CleanScreen(vm: GalleryViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Text("Clean", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp))
        AllFilesBanner()
        CleanCard("Swipe Clean", "Swipe left to trash, right to keep. Review hundreds of photos in minutes.", Icons.Default.Delete) {
            vm.push(Screen.SwipeClean)
        }
        CleanCard("Duplicate & Heavy File Radar", "Find likely duplicates and the biggest files eating your storage.", Icons.Default.Storage) {
            vm.push(Screen.Radar)
        }
        ThumbnailsCard(vm)
        CleanCard("WhatsApp Status saver", "Save photos and videos from viewed WhatsApp statuses to your gallery.", Icons.Default.Download) {
            vm.push(Screen.Statuses)
        }
    }
}

@Composable
private fun CleanCard(title: String, sub: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { onClick() }.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Default.ChevronRight, null)
    }
}

private fun findThumbDirs(dir: File, depth: Int, out: MutableList<File>) {
    if (depth > 4) return
    val kids = dir.listFiles() ?: return
    for (k in kids) {
        if (!k.isDirectory) continue
        if (k.name == ".thumbnails") out.add(k) else if (k.name != "Android") findThumbDirs(k, depth + 1, out)
    }
}

@Composable
private fun ThumbnailsCard(vm: GalleryViewModel) {
    val scope = rememberCoroutineScope()
    var dirs by remember { mutableStateOf<List<File>?>(null) }
    var bytes by remember { mutableStateOf(0L) }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(16.dp)
    ) {
        Text(".thumbnails cache cleaner", fontWeight = FontWeight.SemiBold)
        Text(
            "Hidden thumbnail caches left behind by old gallery apps can silently waste gigabytes.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        val d = dirs
        if (d != null) Text(
            if (d.isEmpty()) "No .thumbnails folders found." else "${d.size} folder(s) · $count files · ${formatSize(bytes)}",
            modifier = Modifier.padding(top = 8.dp), fontWeight = FontWeight.Medium
        )
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val res = withContext(Dispatchers.IO) {
                        val list = ArrayList<File>()
                        findThumbDirs(Environment.getExternalStorageDirectory(), 0, list)
                        var b = 0L
                        var c = 0
                        list.forEach { dd -> dd.walkTopDown().filter { it.isFile }.forEach { b += it.length(); c++ } }
                        Triple(list, b, c)
                    }
                    dirs = res.first; bytes = res.second; count = res.third; busy = false
                }
            }) { Text(if (busy) "Scanning…" else "Scan") }
            Button(enabled = !busy && (dirs?.isNotEmpty() == true), onClick = {
                busy = true
                scope.launch {
                    val freed = withContext(Dispatchers.IO) {
                        var b = 0L
                        dirs?.forEach { dd ->
                            dd.walkTopDown().filter { it.isFile }.forEach { f -> val l = f.length(); if (f.delete()) b += l }
                        }
                        b
                    }
                    vm.toast("Freed ${formatSize(freed)}")
                    dirs = emptyList(); bytes = 0; count = 0; busy = false
                }
            }) { Text("Clean") }
        }
    }
}

// ============================================================ Swipe Clean

@Composable
fun SwipeCleanScreen(vm: GalleryViewModel) {
    val deck = remember { vm.media.filter { !it.isPdf }.shuffled() }
    var index by remember { mutableIntStateOf(0) }
    val marked = remember { mutableStateListOf<Long>() }
    val history = remember { mutableStateListOf<Pair<Long, Boolean>>() }
    val offset = remember { Animatable(0f) }
    var cardW by remember { mutableFloatStateOf(1f) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current

    fun decide(trash: Boolean) {
        val m = deck.getOrNull(index) ?: return
        if (trash) marked.add(m.id)
        history.add(m.id to trash)
        index++
        scope.launch { offset.snapTo(0f) }
    }

    fun undo() {
        if (history.isEmpty()) return
        val (id, t) = history.removeAt(history.lastIndex)
        if (t) marked.remove(id)
        index--
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Swipe Clean", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("${minOf(index, deck.size)} / ${deck.size} reviewed · ${marked.size} marked", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(enabled = history.isNotEmpty(), onClick = { undo() }) { Icon(Icons.Default.Undo, "Undo") }
        }

        val m = deck.getOrNull(index)
        if (m == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (deck.isEmpty()) "No photos to review" else "All reviewed 🎉", fontSize = 18.sp)
            }
        } else {
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(16.dp).onSizeChanged { cardW = it.width.toFloat().coerceAtLeast(1f) },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.fillMaxSize()
                        .offset { IntOffset(offset.value.roundToInt(), 0) }
                        .graphicsLayer { rotationZ = offset.value / 40f }
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .pointerInput(index) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    val o = offset.value
                                    if (abs(o) > cardW * 0.28f) {
                                        val trash = o < 0
                                        scope.launch {
                                            offset.animateTo((if (trash) -1.3f else 1.3f) * cardW, tween(160))
                                            decide(trash)
                                        }
                                    } else scope.launch { offset.animateTo(0f, tween(150)) }
                                },
                                onDragCancel = { scope.launch { offset.animateTo(0f) } },
                                onHorizontalDrag = { change, d -> change.consume(); scope.launch { offset.snapTo(offset.value + d) } }
                            )
                        }
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(m.uri).size(1600).build(),
                        contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                    )
                    if (offset.value < -40f) Text("TRASH", color = Color(0xFFFF5252), fontWeight = FontWeight.Black, fontSize = 32.sp, modifier = Modifier.align(Alignment.TopEnd).padding(20.dp))
                    if (offset.value > 40f) Text("KEEP", color = Color(0xFF69F0AE), fontWeight = FontWeight.Black, fontSize = 32.sp, modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
                    Text(
                        "${m.name} · ${formatSize(m.size)}", fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(8.dp)
                    )
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                enabled = m != null, onClick = { decide(true) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)), modifier = Modifier.weight(1f)
            ) { Icon(Icons.Default.Close, null); Text("Trash") }
            Button(
                enabled = m != null, onClick = { decide(false) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)), modifier = Modifier.weight(1f)
            ) { Icon(Icons.Default.Done, null); Text("Keep") }
        }
        Button(
            enabled = marked.isNotEmpty(),
            onClick = { vm.trash(marked.mapNotNull { vm.byId[it] }) { marked.clear() } },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp)
        ) { Icon(Icons.Default.Delete, null); Text("Review & trash ${marked.size} (one confirmation)") }
    }
}

// ============================================================ Duplicate & Heavy File Radar

private fun quickHash(cr: ContentResolver, m: Media): String? = try {
    val md = MessageDigest.getInstance("MD5")
    cr.openFileDescriptor(m.uri, "r")?.use { pfd ->
        FileInputStream(pfd.fileDescriptor).use { fis ->
            val ch = fis.channel
            val buf = ByteBuffer.allocate(131072)
            ch.position(0)
            val n1 = ch.read(buf)
            md.update(buf.array(), 0, max(0, n1))
            if (m.size > 262144) {
                buf.clear()
                ch.position(m.size - 131072)
                val n2 = ch.read(buf)
                md.update(buf.array(), 0, max(0, n2))
            }
        }
    } ?: throw IllegalStateException("unreadable")
    md.update(m.size.toString().toByteArray())
    md.digest().joinToString("") { "%02x".format(it) }
} catch (e: Exception) {
    null
}

private suspend fun findDuplicates(repo: MediaRepository, all: List<Media>, onProgress: (Int, Int) -> Unit): List<List<Media>> =
    withContext(Dispatchers.IO) {
        val sameSize = all.filter { it.size > 0 }.groupBy { it.size }.values.filter { it.size > 1 }
        val out = ArrayList<List<Media>>()
        var n = 0
        for (g in sameSize) {
            val byHash = HashMap<String, MutableList<Media>>()
            for (m in g) {
                val h = quickHash(repo.cr, m) ?: continue
                byHash.getOrPut(h) { ArrayList() }.add(m)
            }
            byHash.values.filter { it.size > 1 }.forEach { grp -> out.add(grp.sortedBy { it.modified }) }
            n++
            if (n % 25 == 0) { onProgress(n, sameSize.size); yield() }
        }
        onProgress(sameSize.size, sameSize.size)
        out.sortedByDescending { it[0].size * (it.size - 1) }
    }

@Composable
fun RadarScreen(vm: GalleryViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var groups by remember { mutableStateOf<List<List<Media>>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var sel by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val heavy = remember(vm.media) { vm.media.sortedByDescending { it.size }.take(200) }
    val scope = rememberCoroutineScope()

    fun startScan() {
        scanning = true
        scope.launch {
            val g = findDuplicates(vm.repo, vm.media) { a, b -> progress = if (b == 0) 1f else a.toFloat() / b }
            groups = g
            sel = g.flatMap { it.drop(1) }.map { it.id }.toSet()   // keep the oldest copy of every group
            scanning = false
        }
    }

    val selItems = remember(sel, vm.byId) { sel.mapNotNull { vm.byId[it] } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Duplicate & Heavy File Radar", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Black) {
            Tab(selected = tab == 0, onClick = { tab = 0; sel = groups?.flatMap { it.drop(1) }?.map { it.id }?.toSet() ?: emptySet() }, text = { Text("Duplicates") })
            Tab(selected = tab == 1, onClick = { tab = 1; sel = emptySet() }, text = { Text("Heavy files") })
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == 0) {
                val g = groups
                when {
                    scanning -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Comparing files…")
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    g == null -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Finds files with identical size and content fingerprint (first and last 128 KB).", fontSize = 13.sp, color = Color.Gray)
                        Button(onClick = { startScan() }, modifier = Modifier.padding(top = 14.dp)) { Text("Scan for duplicates") }
                    }
                    g.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No duplicates found 🎉") }
                    else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(g, key = { it[0].id }) { grp ->
                            Column {
                                Text("${grp.size} copies · ${formatSize(grp[0].size)} each", fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    items(grp, key = { it.id }) { m ->
                                        val on = m.id in sel
                                        Box(Modifier.size(104.dp).clip(RoundedCornerShape(12.dp)).clickable { sel = if (on) sel - m.id else sel + m.id }) {
                                            Thumb(m, Modifier.fillMaxSize())
                                            if (on) Box(Modifier.fillMaxSize().background(Color(0x88B71C1C)))
                                            Icon(
                                                if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                                tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(20.dp)
                                            )
                                            Text(
                                                if (on) "TRASH" else "KEEP", fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).padding(2.dp),
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                    }
                                }
                                Text(grp[0].bucket, fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(8.dp)) {
                    items(heavy, key = { it.id }) { m ->
                        val on = m.id in sel
                        Row(
                            Modifier.fillMaxWidth().clickable { sel = if (on) sel - m.id else sel + m.id }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Thumb(m, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)), 160)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text("${formatSize(m.size)} · ${m.bucket}", fontSize = 11.sp, color = Color.Gray)
                            }
                            Checkbox(checked = on, onCheckedChange = { sel = if (on) sel - m.id else sel + m.id })
                        }
                    }
                }
            }
        }
        if (selItems.isNotEmpty()) {
            Button(
                onClick = { vm.trash(selItems) { sel = emptySet(); groups = null } },
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            ) { Text("Trash ${selItems.size} · frees ${formatSize(selItems.sumOf { it.size })}") }
        }
    }
}

// ============================================================ WhatsApp Statuses

private fun statusDirs(): List<File> {
    val root = Environment.getExternalStorageDirectory()
    return listOf(
        File(root, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
        File(root, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
        File(root, "WhatsApp/Media/.Statuses"),
        File(root, "WhatsApp Business/Media/.Statuses")
    ).filter { it.isDirectory }
}

@Composable
fun StatusesScreen(vm: GalleryViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<File>?>(null) }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(Unit) {
        files = withContext(Dispatchers.IO) {
            statusDirs().flatMap { d -> d.listFiles()?.toList() ?: emptyList() }
                .filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "mp4", "gif", "webp") }
                .sortedByDescending { it.lastModified() }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("WhatsApp Statuses", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        }
        val list = files
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                list == null -> Text("Looking for statuses…", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    "No statuses found. Open a status in WhatsApp first, and make sure all-files access is allowed.",
                    color = Color.Gray, modifier = Modifier.align(Alignment.Center).padding(24.dp)
                )
                else -> LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
                    items(list, key = { it.path }) { f ->
                        val on = f.path in sel
                        Box(Modifier.aspectRatio(1f).padding(1.dp).clickable { sel = if (on) sel - f.path else sel + f.path }) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(f).size(320).build(), contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()
                            )
                            if (on) Box(Modifier.fillMaxSize().background(Color(0x77000000)))
                            Icon(
                                if (on) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked, null,
                                tint = if (on) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp)
                            )
                            if (f.extension.equals("mp4", true)) Text("VIDEO", fontSize = 9.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                        }
                    }
                }
            }
        }
        if (sel.isNotEmpty()) Button(
            onClick = {
                val chosen = (files ?: emptyList()).filter { it.path in sel }
                scope.launch {
                    val n = withContext(Dispatchers.IO) {
                        chosen.count { f ->
                            val video = f.extension.equals("mp4", true)
                            vm.repo.saveFile(f, if (video) "Movies/WhatsApp Statuses/" else "Pictures/WhatsApp Statuses/") != null
                        }
                    }
                    vm.toast("Saved $n of ${chosen.size} to your gallery")
                    sel = emptySet()
                    vm.load()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(12.dp)
        ) { Icon(Icons.Default.Download, null); Text("Save ${sel.size} to gallery") }
    }
}
