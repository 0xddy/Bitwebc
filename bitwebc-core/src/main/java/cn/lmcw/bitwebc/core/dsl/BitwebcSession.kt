package cn.lmcw.bitwebc.core.dsl

import android.os.Bundle
import android.webkit.WebView
import androidx.activity.OnBackPressedCallback
import androidx.annotation.MainThread
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.client.DefaultWebChromeClient
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.event.BitwebcEventHub
import cn.lmcw.bitwebc.core.event.BitwebcEventListener
import cn.lmcw.bitwebc.core.lifecycle.BitwebcLifecycleObserver
import cn.lmcw.bitwebc.core.state.BitwebcPageState
import cn.lmcw.bitwebc.core.state.BitwebcPageStateStore
import cn.lmcw.bitwebc.core.utils.BitwebcBrowsingData
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.net.URI

class BitwebcSession private constructor(
    public val id: String = UUID.randomUUID().toString(),
    webView: WebView,
    lifecycleOwner: LifecycleOwner,
    lifecycleObserver: BitwebcLifecycleObserver,
    backPressedCallback: OnBackPressedCallback,
    private val eventHub: BitwebcEventHub,
    private val pageStateStore: BitwebcPageStateStore = BitwebcPageStateStore(initialUrl = null),
    chromeClient: DefaultWebChromeClient? = null
) {
    companion object {
        private const val MAX_SUPERSEDED_NAVIGATION_URLS = 16

        @JvmSynthetic
        internal fun create(
            id: String = UUID.randomUUID().toString(),
            webView: WebView,
            lifecycleOwner: LifecycleOwner,
            lifecycleObserver: BitwebcLifecycleObserver,
            backPressedCallback: OnBackPressedCallback,
            eventHub: BitwebcEventHub,
            pageStateStore: BitwebcPageStateStore = BitwebcPageStateStore(initialUrl = null),
            chromeClient: DefaultWebChromeClient? = null
        ): BitwebcSession = BitwebcSession(
            id = id,
            webView = webView,
            lifecycleOwner = lifecycleOwner,
            lifecycleObserver = lifecycleObserver,
            backPressedCallback = backPressedCallback,
            eventHub = eventHub,
            pageStateStore = pageStateStore,
            chromeClient = chromeClient
        )
    }

    private sealed interface PendingNavigation {
        val targetUrl: String
        fun markDispatched(webView: WebView, sourceUrl: String?)
        fun load(webView: WebView, sourceUrl: String?)
        fun isConfirmedBy(webView: WebView, url: String, allowRedirect: Boolean): Boolean
    }

    private class UrlNavigation(
        override val targetUrl: String,
        private val headers: Map<String, String>? = null,
        val supersededTargets: List<String> = emptyList()
    ) : PendingNavigation {
        private var dispatchedWebView: WebView? = null
        private var sourceUrl: String? = null

        override fun markDispatched(webView: WebView, sourceUrl: String?) {
            dispatchedWebView = webView
            this.sourceUrl = sourceUrl
        }

        override fun load(webView: WebView, sourceUrl: String?) {
            markDispatched(webView, sourceUrl)
            if (headers == null) {
                webView.loadUrl(targetUrl)
            } else {
                webView.loadUrl(targetUrl, headers)
            }
        }

        override fun isConfirmedBy(
            webView: WebView,
            url: String,
            allowRedirect: Boolean
        ): Boolean {
            if (dispatchedWebView !== webView) return false
            if (navigationUrlsEquivalent(targetUrl, url)) return true
            if (supersededTargets.any { navigationUrlsEquivalent(it, url) }) return false
            val source = sourceUrl
            return allowRedirect && (
                source.isNullOrBlank() || !navigationUrlsEquivalent(source, url)
            )
        }
    }

    @Volatile
    private var currentWebView: WebView? = webView
    @Volatile
    private var currentWebViewUsable = true
    private var lifecycleOwner: LifecycleOwner? = lifecycleOwner
    private var lifecycleObserver: BitwebcLifecycleObserver? = lifecycleObserver
    private var backPressedCallback: OnBackPressedCallback? = backPressedCallback
    private var chromeClient: DefaultWebChromeClient? = chromeClient
    private var pendingNavigation: PendingNavigation? = null
    private val released = AtomicBoolean(false)

    public val events: SharedFlow<BitwebcEvent> = eventHub.events

    /** Observable page/navigation state. Use this instead of polling the underlying [WebView]. */
    public val state: StateFlow<BitwebcPageState> = pageStateStore.state

    public val isReleased: Boolean
        get() = released.get()

    @MainThread
    fun loadUrl(url: String) {
        checkMainThread()
        require(url.isNotBlank()) { "url must not be blank" }
        check(!released.get()) { "This BitwebcSession has already been released" }
        val navigation = createUrlNavigation(url)
        pendingNavigation = navigation
        if (!currentWebViewUsable) {
            return
        }
        navigation.load(requireCurrentWebView(), pageStateStore.state.value.url)
    }

    @MainThread
    fun loadUrl(url: String, additionalHttpHeaders: Map<String, String>) {
        checkMainThread()
        require(url.isNotBlank()) { "url must not be blank" }
        check(!released.get()) { "This BitwebcSession has already been released" }
        val navigation = createUrlNavigation(url, additionalHttpHeaders.toMap())
        pendingNavigation = navigation
        if (!currentWebViewUsable) {
            return
        }
        navigation.load(requireCurrentWebView(), pageStateStore.state.value.url)
    }

    /** Navigates back when history is available and reports whether the command was handled. */
    @MainThread
    fun goBack(): Boolean {
        checkMainThread()
        val webView = requireUsableWebView()
        if (!webView.canGoBack()) return false
        pendingNavigation = null
        webView.goBack()
        pageStateStore.syncNavigation(webView)
        return true
    }

    /** Navigates forward when history is available and reports whether the command was handled. */
    @MainThread
    fun goForward(): Boolean {
        checkMainThread()
        val webView = requireUsableWebView()
        if (!webView.canGoForward()) return false
        pendingNavigation = null
        webView.goForward()
        pageStateStore.syncNavigation(webView)
        return true
    }

    @MainThread
    fun reload() {
        checkMainThread()
        check(!released.get()) { "This BitwebcSession has already been released" }
        val lastKnownUrl = state.value.url
        if (!currentWebViewUsable) {
            if (pendingNavigation == null && !lastKnownUrl.isNullOrBlank()) {
                pendingNavigation = createUrlNavigation(lastKnownUrl)
            }
            return
        }
        val webView = requireCurrentWebView()
        pendingNavigation?.let { navigation ->
            navigation.load(webView, pageStateStore.state.value.url)
            return
        }
        val rendererUrl = runCatching { webView.url }.getOrNull()
        if (rendererUrl.isNullOrBlank() && !lastKnownUrl.isNullOrBlank()) {
            webView.loadUrl(lastKnownUrl)
        } else {
            webView.reload()
        }
    }

    @MainThread
    fun stopLoading() {
        checkMainThread()
        check(!released.get()) { "This BitwebcSession has already been released" }
        pendingNavigation = null
        pageStateStore.markLoadingStopped()
        if (!currentWebViewUsable) return
        requireCurrentWebView().stopLoading()
    }

    /** Evaluates JavaScript against the current renderer without exposing a replaceable WebView. */
    @MainThread
    @JvmOverloads
    fun evaluateJavaScript(script: String, onResult: (String?) -> Unit = {}) {
        checkMainThread()
        requireUsableWebView().evaluateJavascript(script, onResult)
    }

    /** Clears navigation history and transient form/find state for this Session's WebView. */
    @MainThread
    fun clearViewState() {
        checkMainThread()
        BitwebcBrowsingData.clearViewState(requireUsableWebView())
    }

    /** Clears the application-wide WebView HTTP cache. */
    @MainThread
    @JvmOverloads
    fun clearSharedHttpCache(includeDiskFiles: Boolean = true) {
        checkMainThread()
        BitwebcBrowsingData.clearSharedHttpCache(requireUsableWebView(), includeDiskFiles)
    }

    /** Clears application-wide remembered SSL certificate decisions. */
    @MainThread
    fun clearSharedSslPreferences() {
        checkMainThread()
        BitwebcBrowsingData.clearSharedSslPreferences(requireUsableWebView())
    }

    /** Saves navigation history and scroll position without exposing a renderer-bound WebView. */
    @MainThread
    fun saveState(outState: Bundle): Boolean {
        checkMainThread()
        if (released.get() || !currentWebViewUsable) return false
        val webView = currentWebView ?: return false
        return runCatching { webView.saveState(outState) != null }.getOrDefault(false)
    }

    fun addEventListener(listener: BitwebcEventListener) {
        eventHub.addListener(listener)
    }

    fun removeEventListener(listener: BitwebcEventListener) {
        eventHub.removeListener(listener)
    }

    @MainThread
    fun release() {
        checkMainThread()
        if (!released.compareAndSet(false, true)) return
        markReleaseStarted()
        val observer = lifecycleObserver
        runCatching { chromeClient?.release() }
        runCatching { backPressedCallback?.remove() }
        runCatching {
            if (observer != null) lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
        try {
            observer?.release()
        } finally {
            clearRuntimeReferences()
        }
    }

    @JvmSynthetic
    internal fun replaceWebView(expected: WebView, replacement: WebView): Boolean {
        if (released.get() || currentWebView !== expected) return false
        currentWebView = replacement
        currentWebViewUsable = true
        pageStateStore.onWebViewChanged(replacement)
        val queuedNavigation = pendingNavigation
        if (queuedNavigation != null) {
            val sourceUrl = pageStateStore.state.value.url
            queuedNavigation.markDispatched(replacement, sourceUrl)
            runCatching {
                replacement.post {
                    if (
                        !released.get() && currentWebView === replacement && currentWebViewUsable &&
                        pendingNavigation === queuedNavigation
                    ) {
                        runCatching {
                            queuedNavigation.load(replacement, sourceUrl)
                        }
                    }
                }
            }
        }
        return true
    }

    @JvmSynthetic
    internal fun markWebViewUnusable(expected: WebView) {
        if (!released.get() && currentWebView === expected) currentWebViewUsable = false
    }

    @JvmSynthetic
    internal fun confirmMainFrameNavigation(
        expected: WebView,
        url: String?,
        allowRedirect: Boolean
    ) {
        if (released.get() || currentWebView !== expected || url.isNullOrBlank()) return
        val navigation = pendingNavigation ?: return
        if (navigation.isConfirmedBy(expected, url, allowRedirect)) {
            pendingNavigation = null
        }
    }

    @JvmSynthetic
    internal fun currentWebViewForRecovery(): WebView? =
        currentWebView?.takeUnless { released.get() }

    @JvmSynthetic
    internal fun beginReleaseFromLifecycle() {
        released.set(true)
        markReleaseStarted()
    }

    @JvmSynthetic
    internal fun finishReleaseFromLifecycle() {
        released.set(true)
        clearRuntimeReferences()
    }

    private fun checkMainThread() {
        val currentResult = runCatching { android.os.Looper.myLooper() }
        val mainResult = runCatching { android.os.Looper.getMainLooper() }
        // Plain JVM unit tests use an unimplemented android.jar; Android callers are enforced.
        if (currentResult.isFailure || mainResult.isFailure) return
        check(currentResult.getOrNull() == mainResult.getOrNull()) {
            "BitwebcSession WebView operations must run on the main thread"
        }
    }

    private fun requireCurrentWebView(): WebView = checkNotNull(currentWebView) {
        "This BitwebcSession no longer owns a WebView"
    }

    private fun requireUsableWebView(): WebView {
        check(!released.get()) { "This BitwebcSession has already been released" }
        check(currentWebViewUsable) { "The WebView renderer is being recovered" }
        return requireCurrentWebView()
    }

    private fun clearRuntimeReferences() {
        markReleaseStarted()
        currentWebView = null
        lifecycleOwner = null
        lifecycleObserver = null
        backPressedCallback = null
        chromeClient = null
    }

    private fun markReleaseStarted() {
        currentWebViewUsable = false
        pendingNavigation = null
        eventHub.close()
        pageStateStore.markReleased()
    }

    private fun createUrlNavigation(
        url: String,
        headers: Map<String, String>? = null
    ): UrlNavigation {
        val previous = pendingNavigation as? UrlNavigation
        val supersededTargets = previous?.let {
            (it.supersededTargets + it.targetUrl)
                .distinct()
                .takeLast(MAX_SUPERSEDED_NAVIGATION_URLS)
        }.orEmpty()
        return UrlNavigation(url, headers, supersededTargets)
    }
}

private fun navigationUrlsEquivalent(expected: String, actual: String): Boolean {
    if (expected == actual) return true
    val expectedUri = runCatching { URI(expected) }.getOrNull() ?: return false
    val actualUri = runCatching { URI(actual) }.getOrNull() ?: return false
    if (
        expectedUri.scheme?.lowercase() !in setOf("http", "https") ||
        actualUri.scheme?.lowercase() !in setOf("http", "https")
    ) {
        return false
    }
    fun URI.effectivePort(): Int = when {
        port >= 0 -> port
        scheme.equals("https", ignoreCase = true) -> 443
        else -> 80
    }
    fun URI.normalizedPath(): String = rawPath.orEmpty().ifEmpty { "/" }
    return expectedUri.scheme.equals(actualUri.scheme, ignoreCase = true) &&
        expectedUri.host.equals(actualUri.host, ignoreCase = true) &&
        expectedUri.effectivePort() == actualUri.effectivePort() &&
        expectedUri.normalizedPath() == actualUri.normalizedPath() &&
        expectedUri.rawQuery == actualUri.rawQuery &&
        expectedUri.rawFragment == actualUri.rawFragment
}
