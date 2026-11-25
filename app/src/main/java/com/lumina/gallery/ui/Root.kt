package com.lumina.gallery.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

val LocalAllFiles = compositionLocalOf { false }

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)

private fun hasMediaPermission(ctx: android.content.Context): Boolean {
    val core = if (Build.VERSION.SDK_INT >= 33)
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    return core.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
}

@Composable
fun LuminaRoot(vm: GalleryViewModel, activity: FragmentActivity) {
    val ctx = LocalContext.current
    var hasMedia by remember { mutableStateOf(hasMediaPermission(ctx)) }
    var allFiles by remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                hasMedia = hasMediaPermission(ctx)
                val af = Environment.isExternalStorageManager()
                if (af != allFiles) {
                    allFiles = af
                    if (hasMedia) vm.load()
                }
            }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(hasMedia) { if (hasMedia) vm.onPermissionGranted() }
    LaunchedEffect(Unit) { vm.events.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }

    val permLauncher = rememberLauncher { hasMedia = hasMediaPermission(ctx) }

    if (!hasMedia) {
        PermissionGate(
            onGrant = { permLauncher.launch(mediaPermissions()) },
            onAllFiles = { openAllFiles(ctx) },
            allFiles = allFiles
        )
        return
    }

    val albumsGrid = rememberLazyGridState()
    val albumsList = rememberLazyListState()
    BackHandler(enabled = vm.canHandleBack()) { vm.handleBack() }

    CompositionLocalProvider(LocalAllFiles provides allFiles) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Scaffold(
                containerColor = Color.Black,
                bottomBar = { if (vm.stack.isEmpty()) BottomBar(vm) }
            ) { pad ->
                Box(Modifier.padding(pad).fillMaxSize()) {
                    when (vm.tab) {
                        0 -> AlbumsScreen(vm, albumsGrid, albumsList)
                        1 -> AiFindScreen(vm)
                        2 -> PeopleScreen(vm)
                        3 -> CleanScreen(vm)
                        else -> VaultScreen(vm, activity)
                    }
                }
            }
            vm.stack.forEachIndexed { i, s ->
                key(i) { Overlay(vm, s) }
            }
        }
    }
}

@Composable
private fun rememberLauncher(onResult: () -> Unit) =
    androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }

private fun openAllFiles(ctx: android.content.Context) {
    try {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
fun AllFilesBanner() {
    if (LocalAllFiles.current) return
    val ctx = LocalContext.current
    Column(
        Modifier.padding(12.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text("All-files access is off", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(
            "PDF search, .thumbnails cleaner and WhatsApp status saver need it.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { openAllFiles(ctx) }, modifier = Modifier.padding(top = 8.dp)) { Text("Allow") }
    }
}

@Composable
private fun PermissionGate(onGrant: () -> Unit, onAllFiles: () -> Unit, allFiles: Boolean) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.PhotoLibrary, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 16.dp))
        Text("Lumina Gallery", fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(
            "Allow access to your photos and videos to get started. Everything stays on your device.",
            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 16.dp)
        )
        Button(onClick = onGrant) { Text("Grant media access") }
        if (!allFiles) OutlinedButton(onClick = onAllFiles, modifier = Modifier.padding(top = 10.dp)) {
            Text("Also allow all-files access (PDFs, cleaners)")
        }
    }
}

@Composable
private fun BottomBar(vm: GalleryViewModel) {
    val tabs = listOf(
        "Albums" to Icons.Default.PhotoLibrary,
        "AI Find" to Icons.Default.AutoAwesome,
        "People" to Icons.Default.Face,
        "Clean" to Icons.Default.CleaningServices,
        "Vault" to Icons.Default.Lock
    )
    NavigationBar(containerColor = Color.Black) {
        tabs.forEachIndexed { i, (label, icon) ->
            NavigationBarItem(
                selected = vm.tab == i,
                onClick = { vm.tab = i },
                icon = { Icon(icon, label) },
                label = { Text(label, fontSize = 11.sp) }
            )
        }
    }
}

@Composable
private fun Overlay(vm: GalleryViewModel, s: Screen) {
    val base = Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { } }
    val mod = if (s is Screen.Viewer || s is Screen.QrLive) base else base.statusBarsPadding().navigationBarsPadding()
    Box(mod) {
        when (s) {
            is Screen.Folder, Screen.Timeline, Screen.Bin, is Screen.Collection -> MediaListScreen(vm, s)
            is Screen.Viewer -> ViewerScreen(vm, s)
            is Screen.Studio -> StudioScreen(vm, s.id)
            is Screen.Ocr -> OcrScreen(vm, s.id)
            is Screen.Trim -> TrimScreen(vm, s.id)
            Screen.QrLive -> QrLiveScreen(vm)
            Screen.SwipeClean -> SwipeCleanScreen(vm)
            Screen.Radar -> RadarScreen(vm)
            Screen.Statuses -> StatusesScreen(vm)
        }
    }
}
