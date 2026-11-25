@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.lumina.gallery.ui

import android.content.Intent
import android.graphics.Bitmap
import android.util.LruCache
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.lumina.gallery.vault.VaultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val thumbCache = LruCache<String, Bitmap>(160)

private fun canBiometric(a: FragmentActivity) =
    BiometricManager.from(a).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

private fun promptBiometric(a: FragmentActivity, onOk: () -> Unit) {
    if (!canBiometric(a)) return
    val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Vault")
            .setSubtitle("Use your fingerprint")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()
    )
}

@Composable
fun VaultScreen(vm: GalleryViewModel, activity: FragmentActivity) {
    val v = vm.vault
    val unlocked = v.unlocked
    val ver = v.version
    DisposableEffect(unlocked) {
        // no screenshots / screen recording of the vault while it is open
        if (unlocked) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    when {
        !v.hasPin -> SetupPin(vm)
        !unlocked -> LockedView(vm, activity)
        else -> UnlockedView(vm, ver)
    }
}

@Composable
private fun PinPad(title: String, subtitle: String, error: String, extra: @Composable () -> Unit = {}, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Row(Modifier.padding(vertical = 18.dp).height(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(pin.length) { Box(Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)) }
        }
        Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.height(18.dp))
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "OK")
        for (row in keys.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                for (k in row) {
                    Box(
                        Modifier.size(68.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable {
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "OK" -> if (pin.length >= 4) { val p = pin; pin = ""; onDone(p) }
                                else -> if (pin.length < 8) pin += k
                            }
                        },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k == "⌫") Icon(Icons.Default.Backspace, "Delete digit") else Text(k, fontSize = 22.sp)
                    }
                }
            }
        }
        extra()
    }
}

@Composable
private fun SetupPin(vm: GalleryViewModel) {
    var first by remember { mutableStateOf<String?>(null) }
    var err by remember { mutableStateOf("") }
    PinPad(
        title = if (first == null) "Create Vault PIN" else "Confirm PIN",
        subtitle = "4–8 digits. Files are AES-256 encrypted with a hardware-backed key.",
        error = err
    ) { p ->
        val f = first
        if (f == null) { first = p; err = "" }
        else if (f == p) vm.vault.setPin(p)
        else { first = null; err = "PINs did not match. Start again." }
    }
}

@Composable
private fun LockedView(vm: GalleryViewModel, activity: FragmentActivity) {
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { promptBiometric(activity) { vm.vault.unlockWithBiometric() } }
    PinPad(
        title = "Vault locked", subtitle = "Enter your PIN or use your fingerprint.", error = err,
        extra = {
            if (canBiometric(activity)) TextButton(onClick = { promptBiometric(activity) { vm.vault.unlockWithBiometric() } }) {
                Icon(Icons.Default.Fingerprint, null); Text("  Use fingerprint")
            }
        }
    ) { p -> err = vm.vault.unlockWithPin(p) ?: "" }
}

@Composable
private fun UnlockedView(vm: GalleryViewModel, ver: Int) {
    val v = vm.vault
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by produceState<List<VaultItem>>(emptyList(), ver) { value = withContext(Dispatchers.IO) { v.items().sortedByDescending { it.added } } }
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var open by remember { mutableStateOf<VaultItem?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sel.isNotEmpty()) {
                IconButton(onClick = { sel = emptySet() }) { Icon(Icons.Default.Close, "Clear") }
                Text("${sel.size} selected", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                IconButton(onClick = {
                    val chosen = items.filter { it.id in sel }
                    scope.launch {
                        val n = withContext(Dispatchers.IO) {
                            var ok = 0
                            val done = ArrayList<String>()
                            for (vi in chosen) {
                                val u = vm.repo.insertMedia(vi.name, vi.mime, vi.isVideo, vi.mime == "application/pdf", vi.relPath.ifEmpty { "Pictures/Restored/" }) { out -> v.decryptTo(vi, out) }
                                if (u != null) { ok++; done.add(vi.id) }
                            }
                            v.remove(done)
                            ok
                        }
                        vm.toast("Restored $n item(s) to the gallery")
                        sel = emptySet()
                        vm.load()
                    }
                }) { Icon(Icons.Default.RestoreFromTrash, "Restore to gallery") }
                IconButton(onClick = {
                    val ids = sel
                    scope.launch { withContext(Dispatchers.IO) { v.remove(ids) }; sel = emptySet() }
                }) { Icon(Icons.Default.DeleteForever, "Delete permanently") }
            } else {
                Column(Modifier.weight(1f)) {
                    Text("Vault", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("${items.size} encrypted item(s)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { v.lock() }) { Icon(Icons.Default.Lock, null); Text(" Lock") }
            }
        }
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.TopCenter) {
                Text(
                    "Your vault is empty.\nIn any folder, long-press photos → ⋮ → Move to Vault.",
                    color = Color.Gray, textAlign = TextAlign.Center, fontSize = 13.sp
                )
            }
        } else {
            LazyVerticalGrid(columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize()) {
                items(items, key = { vi -> vi.id }) { vi ->
                    val on = vi.id in sel
                    val bmp by produceState<Bitmap?>(thumbCache.get(vi.id), vi.id) {
                        if (value == null) {
                            value = withContext(Dispatchers.IO) { v.thumb(vi) }?.also { b -> thumbCache.put(vi.id, b) }
                        }
                    }
                    Box(
                        Modifier.aspectRatio(1f).padding(1.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh).combinedClickable(
                            onClick = { if (sel.isNotEmpty()) sel = if (on) sel - vi.id else sel + vi.id else open = vi },
                            onLongClick = { sel = if (on) sel - vi.id else sel + vi.id }
                        )
                    ) {
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Icon(if (vi.isVideo) Icons.Default.PlayArrow else Icons.Default.Lock, null, tint = Color.Gray, modifier = Modifier.align(Alignment.Center))
                        if (on) {
                            Box(Modifier.fillMaxSize().background(Color(0x77000000)))
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp))
                        }
                    }
                }
            }
        }
    }

    val o = open
    if (o != null) {
        if (o.isVideo || !o.mime.startsWith("image/")) {
            LaunchedEffect(o.id) {
                val f = withContext(Dispatchers.IO) { v.tempFile(o) }
                open = null
                if (f != null) {
                    val uri = vm.repo.fileProviderUri(f)
                    try {
                        ctx.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(uri, o.mime)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) { vm.toast("No app can open this file") }
                }
            }
        } else {
            Dialog(onDismissRequest = { open = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                val full by produceState<Bitmap?>(null, o.id) { value = withContext(Dispatchers.IO) { v.full(o) } }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val b = full
                    if (b != null) ZoomableImage(model = b, onTap = { })
                    else Text("Decrypting…", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                    IconButton(onClick = { open = null }, modifier = Modifier.align(Alignment.TopStart).padding(top = 32.dp, start = 8.dp)) {
                        Icon(Icons.Default.Close, "Close", tint = Color.White)
                    }
                }
            }
        }
    }
}
