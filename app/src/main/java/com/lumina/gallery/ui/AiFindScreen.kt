@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val colorChips = listOf(
    "red" to Color(0xFFE53935), "orange" to Color(0xFFFB8C00), "yellow" to Color(0xFFFDD835), "green" to Color(0xFF43A047),
    "teal" to Color(0xFF00897B), "blue" to Color(0xFF1E88E5), "purple" to Color(0xFF8E24AA), "pink" to Color(0xFFEC407A),
    "brown" to Color(0xFF6D4C41), "black" to Color(0xFF212121), "white" to Color(0xFFEEEEEE), "gray" to Color(0xFF9E9E9E)
)

@Composable
fun AiFindScreen(vm: GalleryViewModel) {
    val ix = vm.indexer
    val gridState = rememberLazyGridState()
    LaunchedEffect(vm.aiQuery, vm.media) {
        delay(250)
        vm.runSearch()
    }

    Column(Modifier.fillMaxSize()) {
        Text("AI Find", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        OutlinedTextField(
            value = vm.aiQuery,
            onValueChange = { vm.aiQuery = it },
            singleLine = true,
            placeholder = { Text("cat, invoice, #ff0000, লাল, nodi…") },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = { if (vm.aiQuery.isNotEmpty()) IconButton(onClick = { vm.aiQuery = "" }) { Icon(Icons.Default.Close, "Clear") } },
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        )

        // continuous background indexing with Pause / Resume
        val paused by ix.paused.collectAsState()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("${ix.done} / ${ix.total} indexed · ${ix.phase}", fontSize = 12.sp)
                LinearProgressIndicator(
                    progress = { if (ix.total == 0) 0f else (ix.done.toFloat() / ix.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    "Search by name, folder and PDF type works instantly; labels, OCR text and colours fill in as indexing runs.",
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
            }
            if (ix.running || paused) IconButton(onClick = { ix.setPaused(!paused) }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause")
            }
        }
        AllFilesBanner()

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(listOf("pdf", "screenshots", "videos", "gif")) { k ->
                AssistChip(onClick = { vm.aiQuery = k }, label = { Text(k) })
            }
            items(colorChips) { (name, c) ->
                AssistChip(
                    onClick = { vm.aiQuery = name },
                    label = { Text(name) },
                    leadingIcon = { Box(Modifier.size(14.dp).clip(CircleShape).background(c)) }
                )
            }
        }

        if (vm.aiQuery.isBlank()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    "Search everything: photos, videos, screenshots and PDFs.\nBangla and Banglish hints work too: লাল, nil, pahar, বই.",
                    color = Color.Gray, fontSize = 13.sp
                )
            }
        } else if (vm.aiResults.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                Text("No matches yet. Indexing may still be running.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            Text("${vm.aiResults.size} results", fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            MediaGrid(
                items = vm.aiResults,
                columns = 3,
                selected = emptySet(),
                state = gridState,
                grouped = false,
                onClick = { index, _ -> vm.push(Screen.Viewer(vm.aiResults.map { it.id }, index)) },
                onLongClick = { },
                onHeader = { },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
