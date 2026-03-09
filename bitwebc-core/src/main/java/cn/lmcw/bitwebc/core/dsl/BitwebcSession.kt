package cn.lmcw.bitwebc.core.dsl

import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import kotlinx.coroutines.flow.SharedFlow
import java.util.UUID

class BitwebcSession internal constructor(
    public val id: String = UUID.randomUUID().toString(),
    public val webView: WebView,
    private val lifecycleOwner: LifecycleOwner,
    private val lifecycleObserver: BitwebcLifecycleObserver,
    private val backPressedCallback: OnBackPressedCallback,
    private val eventHub: BitwebcEventHub,
    private val chromeClient: DefaultWebChromeClient? = null
) {
    private var released = false

    public val events: SharedFlow<BitwebcEvent> = eventHub.events

    fun loadUrl(url: String) {
        webView.loadUrl(url)
    }

    fun addEventListener(listener: BitwebcEventListener) {
        eventHub.addListener(listener)
    }

    fun removeEventListener(listener: BitwebcEventListener) {
        eventHub.removeListener(listener)
    }

    fun release() {
        if (released) return
        released = true
        chromeClient?.release()
        backPressedCallback.remove()
        lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
        lifecycleObserver.release()
    }
}
