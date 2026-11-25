@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.LuminaApp
import com.lumina.gallery.data.PersonRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PeopleScreen(vm: GalleryViewModel) {
    val fs = vm.faces
    val db = remember { LuminaApp.instance.db }
    LaunchedEffect(vm.loaded) { if (vm.loaded) vm.startFaces() }

    val pausedNow by fs.paused.collectAsState()
    var minTwo by remember { mutableStateOf(true) }
    var showHidden by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var dialog by remember { mutableStateOf("") }

    val people by produceState<List<PersonRow>>(emptyList(), fs.version, vm.media) {
        val ids = vm.media.map { it.id }.toHashSet()
        value = withContext(Dispatchers.IO) { db.loadPeople(ids) }
    }
    val shown = remember(people, minTwo, showHidden) {
        people.filter { (showHidden || !it.hidden) && (!minTwo || it.mids.size >= 2) }.sortedByDescending { it.mids.size }
    }
    val selPeople = remember(sel, people) { people.filter { it.pid in sel } }

    Column(Modifier.fillMaxSize()) {
        if (sel.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { sel = emptySet() }) { Icon(Icons.Default.Close, "Clear") }
                Text("${sel.size} selected", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (sel.size >= 2) IconButton(onClick = {
                    val target = selPeople.maxByOrNull { it.mids.size }?.pid
                    if (target != null) { fs.merge(target, sel.toList()); vm.toast("Merged ${sel.size} people"); sel = emptySet() }
                }) { Icon(Icons.Default.MergeType, "Merge people") }
                if (sel.size == 1) IconButton(onClick = { dialog = "rename" }) { Icon(Icons.Default.Edit, "Rename") }
                val allHidden = selPeople.isNotEmpty() && selPeople.all { it.hidden }
                IconButton(onClick = { fs.hide(sel, !allHidden); sel = emptySet() }) {
                    Icon(if (allHidden) Icons.Default.Visibility else Icons.Default.VisibilityOff, if (allHidden) "Unhide" else "Hide person")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("People", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (fs.running) "Scanning ${fs.scanned}/${fs.total} · ${fs.facesFound} faces found" else "Scan complete · ${fs.scanned} photos checked",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (fs.running || pausedNow) {
                    val p = pausedNow
                    IconButton(onClick = { fs.setPaused(!p) }) { Icon(if (p) Icons.Default.PlayArrow else Icons.Default.Pause, if (p) "Resume" else "Pause") }
                }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = minTwo, onClick = { minTwo = !minTwo }, label = { Text("2+ photos") }) }
            item { FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Show hidden") }) }
        }

        if (shown.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    if (fs.running) "Looking for faces… people appear here as they are found.\nOnly clear, front-facing faces are accepted."
                    else "No people yet. Turn off “2+ photos” to see single-photo faces.",
                    color = Color.Gray, fontSize = 13.sp, textAlign = TextAlign.Center
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(shown, key = { it.pid }) { p ->
                    val bmp = remember(p.pid) { p.thumb?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() } }
                    val isSel = p.pid in sel
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.combinedClickable(
                            onClick = {
                                if (sel.isNotEmpty()) sel = if (isSel) sel - p.pid else sel + p.pid
                                else vm.push(Screen.Collection(p.name.ifEmpty { "Person" }, p.mids))
                            },
                            onLongClick = { sel = if (isSel) sel - p.pid else sel + p.pid }
                        )
                    ) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                            if (bmp != null) Image(bmp, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (p.hidden) Box(Modifier.fillMaxSize().background(Color(0x99000000)))
                            if (isSel) Icon(
                                Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(26.dp)
                            )
                        }
                        Text(
                            p.name.ifEmpty { "Unnamed" }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp)
                        )
                        Text("${p.mids.size} photos", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    if (dialog == "rename" && selPeople.size == 1) {
        val p = selPeople[0]
        TextInputDialog("Rename person", "Name", initial = p.name, confirm = "Save", onDismiss = { dialog = "" }) {
            fs.rename(p.pid, it.trim()); sel = emptySet()
        }
    }
}
