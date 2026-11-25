@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumina.gallery.data.Media
import com.lumina.gallery.data.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
fun Thumb(m: Media, modifier: Modifier = Modifier, size: Int = 320) {
    if (m.isPdf) {
        Box(modifier.background(Color(0xFF1E2126)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(6.dp)) {
                Icon(Icons.Default.PictureAsPdf, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(32.dp))
                Text(m.name, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.LightGray)
            }
        }
    } else {
        val ctx = LocalContext.current
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(m.uri).size(size).memoryCacheKey("${m.id}-$size-${m.modified}").build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.background(Color(0xFF111316))
        )
    }
}

sealed interface Cell { val key: String }
data class HeaderCell(val label: String, val group: List<Media>) : Cell {
    override val key: String get() = "h$label"
}
data class ItemCell(val m: Media, val index: Int) : Cell {
    override val key: String get() = "m${m.id}"
}

private val dayFmt = SimpleDateFormat("EEE, d MMM yyyy", Locale.getDefault())

fun dayKey(t: Long): Long = (t + TimeZone.getDefault().getOffset(t)) / 86_400_000L

fun dayLabel(t: Long): String {
    val today = dayKey(System.currentTimeMillis())
    return when (dayKey(t)) {
        today -> "Today"
        today - 1 -> "Yesterday"
        else -> dayFmt.format(Date(t))
    }
}

fun buildCells(items: List<Media>, grouped: Boolean): List<Cell> {
    val out = ArrayList<Cell>(items.size + 64)
    if (!grouped) {
        items.forEachIndexed { i, m -> out.add(ItemCell(m, i)) }
        return out
    }
    var curKey = Long.MIN_VALUE
    var start = 0
    fun flush(end: Int) {
        if (end > start) {
            val g = items.subList(start, end)
            out.add(HeaderCell(dayLabel(g[0].time), g))
            for (i in start until end) out.add(ItemCell(items[i], i))
        }
    }
    items.forEachIndexed { i, m ->
        val k = dayKey(m.time)
        if (k != curKey) { flush(i); start = i; curKey = k }
    }
    flush(items.size)
    return out
}

@Composable
fun MediaGrid(
    items: List<Media>,
    columns: Int,
    selected: Set<Long>,
    state: LazyGridState,
    grouped: Boolean,
    onClick: (Int, Media) -> Unit,
    onLongClick: (Media) -> Unit,
    onHeader: (List<Media>) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp)
) {
    val cells = remember(items, grouped) { buildCells(items, grouped) }
    val firstId = items.firstOrNull()?.id
    var prevFirst by remember { mutableStateOf<Long?>(null) }
    // Newly added items must show up at the top without making a scrolled list jump.
    LaunchedEffect(firstId) {
        val pf = prevFirst
        if (pf != null && pf != firstId) {
            val idx = cells.indexOfFirst { it is ItemCell && it.m.id == pf }
            if (idx > 0 && state.firstVisibleItemIndex <= idx && state.firstVisibleItemScrollOffset == 0) state.scrollToItem(0)
        }
        prevFirst = firstId
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        modifier = modifier,
        contentPadding = contentPadding
    ) {
        items(
            cells,
            key = { it.key },
            span = { c -> if (c is HeaderCell) GridItemSpan(maxLineSpan) else GridItemSpan(1) }
        ) { c ->
            when (c) {
                is HeaderCell -> Text(
                    c.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onHeader(c.group) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                )
                is ItemCell -> Tile(c.m, c.index, selected, onClick, onLongClick)
            }
        }
    }
}

@Composable
private fun Tile(m: Media, index: Int, selected: Set<Long>, onClick: (Int, Media) -> Unit, onLong: (Media) -> Unit) {
    val sel = m.id in selected
    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .combinedClickable(onClick = { onClick(index, m) }, onLongClick = { onLong(m) })
    ) {
        Thumb(m, Modifier.fillMaxSize())
        if (sel) Box(Modifier.fillMaxSize().background(Color(0x77000000)))
        if (m.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(formatDuration(m.duration), color = Color.White, fontSize = 10.sp)
            }
        } else if (m.isGif) {
            Text(
                "GIF", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000)).padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        if (m.favorite) Icon(
            Icons.Default.Favorite, null, tint = Color(0xFFFF6B81),
            modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(14.dp)
        )
        if (selected.isNotEmpty()) Icon(
            if (sel) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            null,
            tint = if (sel) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp).clip(CircleShape)
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String = "OK",
    secret: Boolean = false,
    hint: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = v, onValueChange = { v = it }, label = { Text(label) }, singleLine = true,
                    visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
                )
                if (hint.isNotEmpty()) Text(
                    hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(v); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Pick an existing album folder or type a new folder name; returns a MediaStore relative path. */
@Composable
fun FolderPickerDialog(vm: GalleryViewModel, title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var newName by remember { mutableStateOf("") }
    val paths = remember(vm.albums) {
        vm.albums.filter { it.path.isNotEmpty() }.distinctBy { it.path }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                    label = { Text("New folder name (under Pictures)") }, modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(Modifier.heightIn(max = 300.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(paths, key = { it.path }) { a ->
                        Column(
                            Modifier.fillMaxWidth().clickable { onPick(a.path); onDismiss() }.padding(vertical = 8.dp)
                        ) {
                            Text(a.name, fontWeight = FontWeight.Medium)
                            Text(a.path, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.isNotBlank(),
                onClick = { onPick("Pictures/" + newName.trim().replace('/', '_') + "/"); onDismiss() }
            ) { Text("Use new folder") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
