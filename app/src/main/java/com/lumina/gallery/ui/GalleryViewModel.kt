package com.lumina.gallery.ui

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumina.gallery.LuminaApp
import com.lumina.gallery.SystemBridge
import com.lumina.gallery.data.Album
import com.lumina.gallery.data.Media
import com.lumina.gallery.data.MediaFilter
import com.lumina.gallery.data.SortField
import com.lumina.gallery.data.matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data class Folder(val bucketId: Long, val title: String) : Screen
    data object Timeline : Screen
    data object Bin : Screen
    data class Collection(val title: String, val ids: List<Long>) : Screen
    data class Viewer(val ids: List<Long>, val index: Int) : Screen
    data class Studio(val id: Long) : Screen
    data class Ocr(val id: Long) : Screen
    data class Trim(val id: Long) : Screen
    data object QrLive : Screen
    data object SwipeClean : Screen
    data object Radar : Screen
    data object Statuses : Screen
}

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val la = app as LuminaApp
    val prefs = la.prefs
    val repo = la.repo
    val indexer = la.indexer
    val faces = la.faces
    val vault = la.vault
    val events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    // ---- data ----
    var media by mutableStateOf<List<Media>>(emptyList()); private set
    var trashed by mutableStateOf<List<Media>>(emptyList()); private set
    var byId by mutableStateOf<Map<Long, Media>>(emptyMap()); private set
    var loaded by mutableStateOf(false); private set
    var visible by mutableStateOf<List<Media>>(emptyList()); private set
    var albums by mutableStateOf<List<Album>>(emptyList()); private set

    // ---- albums UI state ----
    var filter by mutableStateOf(MediaFilter.ALL); private set
    var sortField by mutableStateOf(runCatching { SortField.valueOf(prefs.sortField) }.getOrDefault(SortField.MODIFIED)); private set
    var sortAsc by mutableStateOf(prefs.sortAsc); private set
    var listView by mutableStateOf(prefs.viewList); private set
    var columns by mutableIntStateOf(prefs.columns); private set
    var pinned by mutableStateOf(prefs.pinned); private set
    var hidden by mutableStateOf(prefs.hidden); private set
    var showHidden by mutableStateOf(false)
    var query by mutableStateOf("")

    // ---- navigation ----
    var tab by mutableIntStateOf(0)
    val stack = mutableStateListOf<Screen>()
    var selected by mutableStateOf<Set<Long>>(emptySet())

    // ---- AI find ----
    var aiQuery by mutableStateOf("")
    var aiResults by mutableStateOf<List<Media>>(emptyList()); private set

    private var started = false

    fun toast(s: String) { events.tryEmit(s) }

    // ======================= loading =======================
    fun onPermissionGranted() {
        if (started) { load(); return }
        started = true
        load()
        viewModelScope.launch { repo.changes().collect { load() } }
    }

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            val m = repo.query(false)
            val t = repo.query(true)
            val map = HashMap<Long, Media>(m.size + t.size)
            t.forEach { map[it.id] = it }
            m.forEach { map[it.id] = it }
            withContext(Dispatchers.Main) {
                byId = map
                media = m
                trashed = t
                loaded = true
                recompute()
                if (faces.started) faces.start(m)
            }
            la.indexer.onMediaChanged(m)
        }
    }

    private fun recompute() {
        val m = media
        val f = filter
        val sf = sortField
        val asc = sortAsc
        val seed = prefs.randomSeed
        val pin = pinned
        val hid = hidden
        val sh = showHidden
        val empties = prefs.emptyFolders
        viewModelScope.launch(Dispatchers.Default) {
            val filtered = m.filter { it.matches(f) }
            val cmp: Comparator<Media> = when (sf) {
                SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Media -> it.name }
                SortField.PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Media -> it.relPath + it.name }
                SortField.SIZE -> compareBy { it: Media -> it.size }
                SortField.TAKEN -> compareBy { it: Media -> it.time }
                SortField.RANDOM -> compareBy { it: Media -> (it.id xor seed) * -7046029254386353131L }
                SortField.MODIFIED -> compareBy { it: Media -> it.modified }
            }
            val items = filtered.sortedWith(if (asc) cmp else cmp.reversed())

            class Agg(val id: Long, val name: String, val path: String) {
                var count = 0; var size = 0L; var mod = 0L; var taken = 0L
                var cover: Media? = null
            }
            val groups = LinkedHashMap<Long, Agg>()
            for (x in filtered) {
                val g = groups.getOrPut(x.bucketId) { Agg(x.bucketId, x.bucket, x.relPath) }
                g.count++
                g.size += x.size
                if (x.modified > g.mod) { g.mod = x.modified; g.cover = x }
                if (x.time > g.taken) g.taken = x.time
            }
            val aggs = ArrayList(groups.values)
            val realPaths = aggs.map { it.path }.toSet()
            if (f == MediaFilter.ALL) {
                for (rel in empties) {
                    if (rel in realPaths) continue
                    aggs.add(Agg(rel.hashCode().toLong(), rel.trimEnd('/').substringAfterLast('/'), rel))
                }
            }
            val acmp: Comparator<Agg> = when (sf) {
                SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Agg -> it.name }
                SortField.PATH -> compareBy(String.CASE_INSENSITIVE_ORDER) { it: Agg -> it.path }
                SortField.SIZE -> compareBy { it: Agg -> it.size }
                SortField.TAKEN -> compareBy { it: Agg -> it.taken }
                SortField.RANDOM -> compareBy { it: Agg -> (it.id xor seed) * -7046029254386353131L }
                SortField.MODIFIED -> compareBy { it: Agg -> it.mod }
            }
            val sortedAggs = aggs.sortedWith(if (asc) acmp else acmp.reversed())
            val shown = sortedAggs.filter { sh || it.id !in hid }
            val ordered = shown.filter { it.id in pin } + shown.filter { it.id !in pin }
            val list = ordered.map { Album(it.id, it.name, it.path, it.count, it.cover) }
            withContext(Dispatchers.Main) {
                visible = items
                albums = list
            }
        }
    }

    fun setFilter(f: MediaFilter) { filter = f; recompute() }

    fun setSort(f: SortField) {
        sortField = f
        prefs.sortField = f.name
        if (f == SortField.RANDOM) prefs.randomSeed = System.nanoTime()
        recompute()
    }

    fun setAsc(a: Boolean) { sortAsc = a; prefs.sortAsc = a; recompute() }
    fun setListView(v: Boolean) { listView = v; prefs.viewList = v }
    fun setColumns(c: Int) { columns = c; prefs.columns = c }
    fun toggleShowHidden() { showHidden = !showHidden; recompute() }

    fun togglePin(id: Long) {
        pinned = if (id in pinned) pinned - id else pinned + id
        prefs.pinned = pinned
        recompute()
    }

    fun toggleHide(id: Long) {
        hidden = if (id in hidden) hidden - id else hidden + id
        prefs.hidden = hidden
        recompute()
        toast(if (id in hidden) "Album hidden. Use ⋮ → Temporarily show hidden to see it." else "Album unhidden")
    }

    fun createFolder(name: String) {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        if (clean.isEmpty()) return
        runCatching { File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), clean).mkdirs() }
        prefs.emptyFolders = prefs.emptyFolders + "Pictures/$clean/"
        recompute()
        toast("Folder “$clean” created. It appears in pickers and fills once you copy or move items in.")
    }

    fun isTrashed(id: Long) = trashed.any { it.id == id }

    // ======================= navigation / back stack =======================
    fun push(s: Screen) {
        if (s is Screen.Folder || s is Screen.Timeline || s is Screen.Bin || s is Screen.Collection) selected = emptySet()
        stack.add(s)
    }

    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    fun replaceTop(s: Screen) { if (stack.isNotEmpty()) stack[stack.lastIndex] = s }

    /** Back order: Studio → Viewer → (multi-select) → Folder → other tab → Albums → exit. */
    fun canHandleBack(): Boolean = stack.isNotEmpty() || selected.isNotEmpty() || tab != 0 || query.isNotEmpty()

    fun handleBack() {
        val top = stack.lastOrNull()
        when {
            top is Screen.Studio || top is Screen.Viewer || top is Screen.Ocr || top is Screen.Trim ||
                top is Screen.QrLive || top is Screen.SwipeClean || top is Screen.Radar || top is Screen.Statuses -> pop()
            selected.isNotEmpty() -> selected = emptySet()
            stack.isNotEmpty() -> pop()
            tab != 0 -> tab = 0
            query.isNotEmpty() -> query = ""
        }
    }

    // ======================= selection =======================
    fun toggleSelect(id: Long) { selected = if (id in selected) selected - id else selected + id }
    fun selectAll(ids: Collection<Long>) { selected = ids.toSet() }
    fun clearSelection() { selected = emptySet() }

    // ======================= AI find =======================
    fun runSearch() {
        val q = aiQuery
        val all = media
        viewModelScope.launch(Dispatchers.Default) {
            val r = indexer.search(q, all)
            withContext(Dispatchers.Main) { if (q == aiQuery) aiResults = r }
        }
    }

    fun startFaces() { if (!faces.started) faces.start(media) }

    // ======================= trash / delete =======================
    fun trash(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        val pi = MediaStore.createTrashRequest(repo.cr, items.map { it.uri }, true)
        SystemBridge.launch(pi.intentSender) { ok ->
            if (ok) {
                toast("Moved ${items.size} item(s) to Recycle Bin")
                selected = emptySet()
                load()
                after()
            }
        }
    }

    fun restore(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        val pi = MediaStore.createTrashRequest(repo.cr, items.map { it.uri }, false)
        SystemBridge.launch(pi.intentSender) { ok ->
            if (ok) { toast("Restored ${items.size} item(s)"); selected = emptySet(); load(); after() }
        }
    }

    fun deleteForever(items: List<Media>, after: () -> Unit = {}) {
        if (items.isEmpty()) return
        val pi = MediaStore.createDeleteRequest(repo.cr, items.map { it.uri })
        SystemBridge.launch(pi.intentSender) { ok ->
            if (ok) { toast("Deleted ${items.size} item(s)"); selected = emptySet(); load(); after() }
        }
    }

    // ======================= write-access helper =======================
    /** Runs [work]; if the OS says we lack write access, shows ONE confirmation prompt for all uris, then retries. */
    private fun withWrite(uris: List<Uri>, work: () -> Int, done: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val n = work()
                withContext(Dispatchers.Main) { done(n) }
            } catch (e: SecurityException) {
                val pi = MediaStore.createWriteRequest(repo.cr, uris)
                withContext(Dispatchers.Main) {
                    SystemBridge.launch(pi.intentSender) { ok ->
                        if (ok) {
                            viewModelScope.launch(Dispatchers.IO) {
                                val n = runCatching { work() }.getOrDefault(0)
                                withContext(Dispatchers.Main) { done(n) }
                            }
                        } else toast("Cancelled")
                    }
                }
            }
        }
    }

    fun rename(m: Media, newName: String) {
        withWrite(listOf(m.uri), {
            try { repo.rename(m, newName); 1 } catch (e: SecurityException) { throw e } catch (e: Exception) { 0 }
        }) { n -> toast(if (n > 0) "Renamed" else "Rename failed (name may already exist)"); load() }
    }

    fun moveTo(items: List<Media>, destRel: String) {
        withWrite(items.map { it.uri }, {
            var n = 0
            for (m in items) {
                try { repo.move(m, destRel); n++ } catch (e: SecurityException) { throw e } catch (e: Exception) { }
            }
            n
        }) { n -> toast("Moved $n of ${items.size}"); selected = emptySet(); load() }
    }

    fun copyTo(items: List<Media>, destRel: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var n = 0
            for (m in items) if (repo.copy(m, destRel) != null) n++
            withContext(Dispatchers.Main) { toast("Copied $n of ${items.size}"); selected = emptySet(); load() }
        }
    }

    fun setFavorite(items: List<Media>, value: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                items.forEach {
                    repo.cr.update(it.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (value) 1 else 0) }, null, null)
                }
                withContext(Dispatchers.Main) { load() }
            } catch (e: SecurityException) {
                val pi = MediaStore.createFavoriteRequest(repo.cr, items.map { it.uri }, value)
                withContext(Dispatchers.Main) { SystemBridge.launch(pi.intentSender) { ok -> if (ok) load() } }
            }
        }
    }

    fun setWallpaper(m: Media, which: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = repo.setWallpaper(m, which)
            toast(if (ok) "Wallpaper set" else "Could not set wallpaper")
        }
    }

    fun cleanCopy(m: Media) {
        viewModelScope.launch(Dispatchers.IO) {
            val u = repo.cleanCopy(m)
            toast(if (u != null) "Saved EXIF-free copy next to the original" else "Could not create clean copy")
            if (u != null) withContext(Dispatchers.Main) { load() }
        }
    }

    // ======================= share =======================
    fun share(items: List<Media>) {
        if (items.isEmpty()) return
        val ctx = getApplication<Application>()
        val i = if (items.size == 1) Intent(Intent.ACTION_SEND).apply {
            type = items[0].mime.ifEmpty { "*/*" }
            putExtra(Intent.EXTRA_STREAM, items[0].uri)
        } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(i, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Safe Share: re-encodes images so GPS / EXIF never leave the device. */
    fun safeShare(items: List<Media>) {
        viewModelScope.launch(Dispatchers.IO) {
            val imgs = items.filter { it.isImage }
            val uris = imgs.mapNotNull { repo.safeShareFile(it) }
            if (uris.isEmpty()) { toast("Safe Share works on photos only"); return@launch }
            val ctx = getApplication<Application>()
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"; putExtra(Intent.EXTRA_STREAM, uris[0])
            } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/jpeg"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent.createChooser(i, "Safe Share (metadata stripped)").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    // ======================= vault =======================
    fun toVault(items: List<Media>) {
        if (!vault.hasPin) { toast("Create your Vault PIN in the Vault tab first"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = vault.add(items)
            if (ok.isEmpty()) { toast("Nothing could be encrypted"); return@launch }
            withContext(Dispatchers.Main) {
                val pi = MediaStore.createDeleteRequest(repo.cr, ok.map { it.uri })
                SystemBridge.launch(pi.intentSender) { res ->
                    toast(if (res) "Moved ${ok.size} item(s) to the Vault" else "Encrypted copies saved; originals kept")
                    selected = emptySet()
                    load()
                }
            }
        }
    }
}
