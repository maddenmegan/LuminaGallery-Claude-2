package com.lumina.gallery

import android.app.Application
import android.content.IntentSender
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import com.lumina.gallery.ai.FaceScanner
import com.lumina.gallery.ai.Indexer
import com.lumina.gallery.data.IndexDb
import com.lumina.gallery.data.MediaRepository
import com.lumina.gallery.data.Prefs
import com.lumina.gallery.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class LuminaApp : Application(), ImageLoaderFactory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var prefs: Prefs
    lateinit var repo: MediaRepository
    lateinit var db: IndexDb
    lateinit var indexer: Indexer
    lateinit var faces: FaceScanner
    lateinit var vault: VaultManager

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        repo = MediaRepository(this)
        db = IndexDb(this)
        indexer = Indexer(this, repo, db, appScope)
        faces = FaceScanner(repo, db, appScope)
        vault = VaultManager(this, prefs)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(VideoFrameDecoder.Factory())
            add(ImageDecoderDecoder.Factory())
        }
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: LuminaApp
            private set
    }
}

/** Lets non-UI code launch MediaStore write/trash/delete confirmation prompts from the Activity. */
object SystemBridge {
    var launcher: ((IntentSender, (Boolean) -> Unit) -> Unit)? = null

    fun launch(sender: IntentSender, cb: (Boolean) -> Unit) {
        val l = launcher
        if (l == null) cb(false) else l(sender, cb)
    }
}
