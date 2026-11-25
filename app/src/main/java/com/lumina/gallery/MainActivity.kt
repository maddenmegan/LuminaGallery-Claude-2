package com.lumina.gallery

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import com.lumina.gallery.ui.GalleryViewModel
import com.lumina.gallery.ui.LuminaRoot
import com.lumina.gallery.ui.LuminaTheme

class MainActivity : FragmentActivity() {
    private var pendingCb: ((Boolean) -> Unit)? = null
    private val senderLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        pendingCb?.invoke(r.resultCode == RESULT_OK)
        pendingCb = null
    }
    private val vm: GalleryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        SystemBridge.launcher = { sender, cb ->
            pendingCb = cb
            senderLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
        setContent { LuminaTheme { LuminaRoot(vm, this) } }
    }

    override fun onStop() {
        super.onStop()
        LuminaApp.instance.vault.lock()   // vault auto-locks whenever the app leaves the foreground
    }
}
