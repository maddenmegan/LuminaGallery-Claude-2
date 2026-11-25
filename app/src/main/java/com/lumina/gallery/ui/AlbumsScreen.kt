@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.data.Album
import com.lumina.gallery.data.MediaFilter
import com.lumina.gallery.data.SortField

@Composable
fun AlbumsScreen(vm: GalleryViewModel, gridState: LazyGridState, listState: LazyListState) {
    var dialog by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<Long?>(null) }

    val bin = remember(vm.trashed) {
        if (vm.trashed.isEmpty()) null else Album(-1L, "Recycle Bin", "", vm.trashed.size, vm.trashed.first(), true)
    }
    val list = remember(vm.albums, bin, vm.query) {
        val q = vm.query.trim()
        val filtered = if (q.isEmpty()) vm.albums else vm.albums.filter { it.name.contains(q, true) || it.path.contains(q, true) }
        listOfNotNull(bin) + filtered
    }

    Column(Modifier.fillMaxSize()) {
        FloatingSearchBar(vm, onDialog = { dialog = it })
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(MediaFilter.entries.toList()) { f ->
                FilterChip(selected = vm.filter == f, onClick = { vm.setFilter(f) }, label = { Text(f.label) })
            }
        }
        if (!vm.loaded) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading your library…", color = Color.Gray) }
        } else if (list.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No albums match", color = Color.Gray) }
        } else if (!vm.listView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(vm.columns),
                state = gridState,
                contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(list, key = { it.id }) { a ->
                    AlbumTile(vm, a, menuFor == a.id, { menuFor = if (it) a.id else null }, grid = true)
                }
            }
        } else {
            LazyColumn(state = listState, contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(list, key = { it.id }) { a ->
                    AlbumTile(vm, a, menuFor == a.id, { menuFor = if (it) a.id else null }, grid = false)
                }
            }
        }
    }

    when (dialog) {
        "folder" -> TextInputDialog("Create folder", "Folder name", confirm = "Create", onDismiss = { dialog = "" }) { vm.createFolder(it) }
        "key" -> TextInputDialog(
            "AI OCR Language Key", "Gemini API key", initial = vm.prefs.geminiKey, confirm = "Save", secret = true,
            hint = "Used for Bangla, Arabic and all-language OCR. Stored only on this device.",
            onDismiss = { dialog = "" }
        ) { vm.prefs.geminiKey = it; vm.toast("Key saved") }
    }
}

@Composable
private fun FloatingSearchBar(vm: GalleryViewModel, onDialog: (String) -> Unit) {
    val ctx = LocalContext.current
    var sortMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(Modifier.padding(start = 14.dp, end = 2.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            BasicTextField(
                value = vm.query,
                onValueChange = { vm.query = it },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(cs.primary),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (vm.query.isEmpty()) Text("Search folders", color = cs.onSurfaceVariant, fontSize = 14.sp, maxLines = 1)
                        inner()
                    }
                }
            )
            IconButton(onClick = { vm.push(Screen.QrLive) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.QrCodeScanner, "Live QR scanner", modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = {
                try { ctx.startActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE)) }
                catch (e: ActivityNotFoundException) { vm.toast("No camera app found") }
            }, modifier = Modifier.size(38.dp)) { Icon(Icons.Default.CameraAlt, "Camera", modifier = Modifier.size(22.dp)) }
            IconButton(onClick = { vm.push(Screen.Timeline) }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Timeline, "Timeline", modifier = Modifier.size(22.dp))
            }
            Box {
                IconButton(onClick = { sortMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort by", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Text("Sort by", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    SortField.entries.forEach { f ->
                        DropdownMenuItem(
                            text = { Text(f.label) },
                            leadingIcon = { if (vm.sortField == f) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                            onClick = { vm.setSort(f); sortMenu = false }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Ascending") },
                        leadingIcon = { if (vm.sortAsc) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { vm.setAsc(true); sortMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Descending") },
                        leadingIcon = { if (!vm.sortAsc) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { vm.setAsc(false); sortMenu = false }
                    )
                }
            }
            Box {
                IconButton(onClick = { moreMenu = true }, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.Default.MoreVert, "More", modifier = Modifier.size(22.dp))
                }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Temporarily show hidden") },
                        leadingIcon = { if (vm.showHidden) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { vm.toggleShowHidden(); moreMenu = false }
                    )
                    HorizontalDivider()
                    Text("View type", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    DropdownMenuItem(
                        text = { Text("Grid") },
                        leadingIcon = { if (!vm.listView) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { vm.setListView(false); moreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("List") },
                        leadingIcon = { if (vm.listView) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                        onClick = { vm.setListView(true); moreMenu = false }
                    )
                    HorizontalDivider()
                    Text("Column count", fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    (2..5).forEach { n ->
                        DropdownMenuItem(
                            text = { Text("$n columns") },
                            leadingIcon = { if (vm.columns == n) Icon(Icons.Default.Check, null) else Box(Modifier.size(24.dp)) },
                            onClick = { vm.setColumns(n); moreMenu = false }
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("Create folder") }, onClick = { moreMenu = false; onDialog("folder") })
                    DropdownMenuItem(text = { Text("AI OCR Language Key") }, onClick = { moreMenu = false; onDialog("key") })
                }
            }
        }
    }
}

@Composable
private fun AlbumTile(vm: GalleryViewModel, a: Album, menuOpen: Boolean, onMenu: (Boolean) -> Unit, grid: Boolean) {
    val pinned = a.id in vm.pinned
    val isHidden = a.id in vm.hidden
    val click = { if (a.isBin) vm.push(Screen.Bin) else vm.push(Screen.Folder(a.id, a.name)) }
    val cs = MaterialTheme.colorScheme

    @Composable
    fun cover(mod: Modifier) {
        Box(mod.clip(RoundedCornerShape(16.dp)).background(cs.surfaceContainerHigh)) {
            if (a.cover != null) Thumb(a.cover, Modifier.fillMaxSize(), 400)
            else Icon(Icons.Default.Folder, null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center).size(36.dp))
            if (a.isBin) {
                Box(Modifier.fillMaxSize().background(Color(0x88000000)))
                Icon(Icons.Default.Delete, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(34.dp))
            }
            if (pinned) Icon(Icons.Default.PushPin, null, tint = Color.White, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(16.dp))
            if (isHidden) Icon(Icons.Default.VisibilityOff, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
        }
    }

    Box {
        if (grid) {
            Column(Modifier.combinedClickable(onClick = { click() }, onLongClick = { if (!a.isBin) onMenu(true) })) {
                cover(Modifier.fillMaxWidth().aspectRatio(1f))
                Text(a.name, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                Text("${a.count}", fontSize = 11.sp, color = cs.onSurfaceVariant)
            }
        } else {
            Row(
                Modifier.fillMaxWidth().combinedClickable(onClick = { click() }, onLongClick = { if (!a.isBin) onMenu(true) }),
                verticalAlignment = Alignment.CenterVertically
            ) {
                cover(Modifier.size(64.dp))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(a.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${a.count} items" + if (a.path.isNotEmpty()) " · ${a.path}" else "", fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Unpin from top" else "Pin to Top") },
                leadingIcon = { Icon(Icons.Default.PushPin, null) },
                onClick = { onMenu(false); vm.togglePin(a.id) }
            )
            DropdownMenuItem(
                text = { Text(if (isHidden) "Unhide Album" else "Hide Album") },
                leadingIcon = { Icon(Icons.Default.VisibilityOff, null) },
                onClick = { onMenu(false); vm.toggleHide(a.id) }
            )
        }
    }
}
