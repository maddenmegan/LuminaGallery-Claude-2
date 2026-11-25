@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumina.gallery.data.Media

/** Folder, Timeline, Recycle Bin and generic collections (people / duplicates): all share multi-select + bulk actions. */
@Composable
fun MediaListScreen(vm: GalleryViewModel, screen: Screen) {
    val title = when (screen) {
        is Screen.Folder -> screen.title
        Screen.Timeline -> "Timeline"
        Screen.Bin -> "Recycle Bin"
        is Screen.Collection -> screen.title
        else -> ""
    }
    val isBin = screen is Screen.Bin
    val items: List<Media> = remember(screen, vm.visible, vm.trashed, vm.byId, vm.sortAsc) {
        when (screen) {
            is Screen.Folder -> vm.visible.filter { it.bucketId == screen.bucketId }
            Screen.Timeline -> if (vm.sortAsc) vm.visible.sortedBy { it.time } else vm.visible.sortedByDescending { it.time }
            Screen.Bin -> vm.trashed
            is Screen.Collection -> screen.ids.mapNotNull { vm.byId[it] }
            else -> emptyList()
        }
    }
    val sel = vm.selected
    val selecting = sel.isNotEmpty()
    val selItems = remember(sel, items) { if (sel.isEmpty()) emptyList() else items.filter { it.id in sel } }
    val gridState = rememberLazyGridState()
    var picker by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }
    val cols = (vm.columns + 2).coerceIn(3, 6)

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Default.Close, "Clear selection") }
                Text("${sel.size}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.selectAll(items.map { it.id }) }) { Icon(Icons.Default.SelectAll, "Select all") }
                if (isBin) {
                    IconButton(onClick = { vm.restore(selItems) }) { Icon(Icons.Default.RestoreFromTrash, "Restore") }
                    IconButton(onClick = { vm.deleteForever(selItems) }) { Icon(Icons.Default.DeleteForever, "Delete forever") }
                } else {
                    IconButton(onClick = { vm.share(selItems) }) { Icon(Icons.Default.Share, "Share") }
                    IconButton(onClick = { picker = "move" }) { Icon(Icons.Default.DriveFileMove, "Move") }
                    IconButton(onClick = { picker = "copy" }) { Icon(Icons.Default.ContentCopy, "Copy") }
                    IconButton(onClick = { vm.trash(selItems) }) { Icon(Icons.Default.Delete, "Move to Recycle Bin") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Safe Share (strip GPS/EXIF)") }, onClick = { menu = false; vm.safeShare(selItems) })
                            DropdownMenuItem(text = { Text("Move to Vault") }, onClick = { menu = false; vm.toVault(selItems) })
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                    Text("${items.size} items", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { vm.selectAll(items.map { it.id }) }) { Icon(Icons.Default.SelectAll, "Select all") }
                if (isBin && items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty") }
            }
        }

        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Nothing here", color = Color.Gray) }
        } else {
            MediaGrid(
                items = items,
                columns = cols,
                selected = sel,
                state = gridState,
                grouped = screen is Screen.Timeline,
                onClick = { index, m ->
                    if (selecting) vm.toggleSelect(m.id) else vm.push(Screen.Viewer(items.map { it.id }, index))
                },
                onLongClick = { m -> vm.toggleSelect(m.id) },
                onHeader = { group -> vm.selected = vm.selected + group.map { it.id } },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (picker.isNotEmpty()) {
        val move = picker == "move"
        FolderPickerDialog(vm, if (move) "Move to folder" else "Copy to folder", onDismiss = { picker = "" }) { rel ->
            if (move) vm.moveTo(selItems, rel) else vm.copyTo(selItems, rel)
        }
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty Recycle Bin?") },
            text = { Text("${items.size} items will be permanently deleted. Android will ask you to confirm once.") },
            confirmButton = { TextButton(onClick = { confirmEmpty = false; vm.deleteForever(vm.trashed) }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } }
        )
    }
}
