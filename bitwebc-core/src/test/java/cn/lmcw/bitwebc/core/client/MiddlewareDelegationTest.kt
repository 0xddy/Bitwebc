package cn.lmcw.bitwebc.core.client

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.KeyEvent
import android.view.View
import android.webkit.ClientCertRequest
import android.webkit.GeolocationPermissions
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SafeBrowsingResponse
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate

@Suppress("DEPRECATION")
class MiddlewareDelegationTest {

    @Test
    fun `web client forwards legacy and security callbacks`() {
        val downstream = allocate(RecordingWebViewClient::class.java)
        val middleware = clientMiddleware(downstream)
        val view = allocate(WebView::class.java)
        val request = fakeWebResourceRequest()
        val certRequest = allocate(RecordingClientCertRequest::class.java)
        val safeBrowsingResponse = allocate(RecordingSafeBrowsingResponse::class.java)
        val keyEvent = allocate(KeyEvent::class.java)

        assertTrue(middleware.shouldOverrideUrlLoading(view, "https://example.test"))
        middleware.onReceivedClientCertRequest(view, certRequest)
        middleware.onSafeBrowsingHit(view, request, 7, safeBrowsingResponse)
        middleware.onUnhandledKeyEvent(view, keyEvent)

        assertSame(certRequest, downstream.clientCertRequest)
        assertSame(request, downstream.safeBrowsingRequest)
        assertSame(safeBrowsingResponse, downstream.safeBrowsingResponse)
        assertSame(keyEvent, downstream.unhandledKeyEvent)
        assertTrue(downstream.safeBrowsingThreatType == 7)
    }

    @Test
    fun `web client keeps secure defaults without downstream`() {
        val middleware = clientMiddleware(null)
        val view = allocate(WebView::class.java)
        val certRequest = allocate(RecordingClientCertRequest::class.java)
        val safeBrowsingResponse = allocate(RecordingSafeBrowsingResponse::class.java)

        middleware.onReceivedClientCertRequest(view, certRequest)
        middleware.onSafeBrowsingHit(view, fakeWebResourceRequest(), 0, safeBrowsingResponse)

        assertTrue(certRequest.canceled)
        assertTrue(safeBrowsingResponse.interstitialShown)
        assertTrue(safeBrowsingResponse.allowReporting)
    }

    @Test
    fun `chrome client forwards window and javascript callbacks`() {
        val downstream = allocate(RecordingWebChromeClient::class.java)
        val middleware = chromeMiddleware(downstream)
        val view = allocate(WebView::class.java)
        val resultMessage = allocate(Message::class.java)
        val jsResult = allocate(JsResult::class.java)

        assertTrue(middleware.onCreateWindow(view, false, true, resultMessage))
        assertTrue(middleware.onJsBeforeUnload(view, "https://example.test", "leave?", jsResult))
        middleware.onRequestFocus(view)
        middleware.onCloseWindow(view)

        assertSame(resultMessage, downstream.createWindowMessage)
        assertSame(jsResult, downstream.beforeUnloadResult)
        assertSame(view, downstream.focusedView)
        assertSame(view, downstream.closedView)
    }

    @Test
    fun `chrome client forwards media history and legacy callbacks`() {
        val downstream = allocate(RecordingWebChromeClient::class.java)
        val middleware = chromeMiddleware(downstream)
        val view = allocate(WebView::class.java)
        val poster = allocate(Bitmap::class.java)
        val progressView = allocate(View::class.java)
        val historyCallback = ValueCallback<Array<String>> { }
        downstream.poster = poster
        downstream.progressView = progressView

        assertSame(poster, middleware.defaultVideoPoster)
        assertSame(progressView, middleware.videoLoadingProgressView)
        middleware.getVisitedHistory(historyCallback)
        middleware.onReceivedTouchIconUrl(view, "https://example.test/icon.png", true)
        middleware.onConsoleMessage("message", 12, "source.js")

        assertSame(historyCallback, downstream.historyCallback)
        assertTrue(downstream.touchIconPrecomposed)
        assertTrue(downstream.legacyConsoleLine == 12)
    }

    @Test
    fun `chrome client delegates permission handling and denies by default`() {
        val downstream = allocate(RecordingWebChromeClient::class.java)
        val delegatedRequest = allocate(RecordingPermissionRequest::class.java)

        chromeMiddleware(downstream).onPermissionRequest(delegatedRequest)

        assertSame(delegatedRequest, downstream.permissionRequest)
        assertFalse(delegatedRequest.denied)

        val defaultRequest = allocate(RecordingPermissionRequest::class.java)
        chromeMiddleware(null).onPermissionRequest(defaultRequest)
        assertTrue(defaultRequest.denied)

        var geolocationAllowed = true
        chromeMiddleware(null).onGeolocationPermissionsShowPrompt(
            "https://example.test",
            GeolocationPermissions.Callback { _, allow, _ -> geolocationAllowed = allow }
        )
        assertFalse(geolocationAllowed)
    }

    private fun clientMiddleware(next: WebViewClient?): MiddlewareWebClientBase {
        return allocate(MiddlewareWebClientBase::class.java).also {
            setField(it, MiddlewareWebClientBase::class.java, "next", next)
        }
    }

    private fun chromeMiddleware(next: WebChromeClient?): MiddlewareWebChromeBase {
        return allocate(MiddlewareWebChromeBase::class.java).also {
            setField(it, MiddlewareWebChromeBase::class.java, "next", next)
        }
    }

    private fun setField(target: Any, owner: Class<*>, name: String, value: Any?) {
        owner.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun <T> allocate(clazz: Class<T>): T = UnsafeAndroidAllocator.allocate(clazz)

    private fun fakeWebResourceRequest(): WebResourceRequest {
        return Proxy.newProxyInstance(
            WebResourceRequest::class.java.classLoader,
            arrayOf(WebResourceRequest::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> args?.firstOrNull() === proxy
                else -> defaultValue(method.returnType)
            }
        } as WebResourceRequest
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0
        else -> null
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class RecordingWebViewClient : WebViewClient() {
        var clientCertRequest: ClientCertRequest? = null
        var safeBrowsingRequest: WebResourceRequest? = null
        var safeBrowsingResponse: SafeBrowsingResponse? = null
        var safeBrowsingThreatType: Int = 0
        var unhandledKeyEvent: KeyEvent? = null

        override fun shouldOverrideUrlLoading(view: WebView, url: String?): Boolean = true

        override fun onReceivedClientCertRequest(view: WebView, request: ClientCertRequest) {
            clientCertRequest = request
        }

        override fun onSafeBrowsingHit(
            view: WebView,
            request: WebResourceRequest,
            threatType: Int,
            callback: SafeBrowsingResponse
        ) {
            safeBrowsingRequest = request
            safeBrowsingResponse = callback
            safeBrowsingThreatType = threatType
        }

        override fun onUnhandledKeyEvent(view: WebView, event: KeyEvent) {
            unhandledKeyEvent = event
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private class RecordingWebChromeClient : WebChromeClient() {
        var createWindowMessage: Message? = null
        var beforeUnloadResult: JsResult? = null
        var focusedView: WebView? = null
        var closedView: WebView? = null
        var poster: Bitmap? = null
        var progressView: View? = null
        var historyCallback: ValueCallback<Array<String>>? = null
        var touchIconPrecomposed: Boolean = false
        var legacyConsoleLine: Int = 0
        var permissionRequest: PermissionRequest? = null

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            createWindowMessage = resultMsg
            return true
        }

        override fun onJsBeforeUnload(
            view: WebView,
            url: String?,
            message: String?,
            result: JsResult
        ): Boolean {
            beforeUnloadResult = result
            return true
        }

        override fun onRequestFocus(view: WebView) {
            focusedView = view
        }

        override fun onCloseWindow(window: WebView) {
            closedView = window
        }

        override fun getDefaultVideoPoster(): Bitmap? = poster

        override fun getVideoLoadingProgressView(): View? = progressView

        override fun getVisitedHistory(callback: ValueCallback<Array<String>>?) {
            historyCallback = callback
        }

        override fun onReceivedTouchIconUrl(view: WebView, url: String?, precomposed: Boolean) {
            touchIconPrecomposed = precomposed
        }

        override fun onConsoleMessage(message: String?, lineNumber: Int, sourceID: String?) {
            legacyConsoleLine = lineNumber
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            permissionRequest = request
        }
    }

    private class RecordingClientCertRequest : ClientCertRequest() {
        var canceled: Boolean = false

        override fun cancel() {
            canceled = true
        }

        override fun getHost(): String = "example.test"

        override fun getKeyTypes(): Array<String> = emptyArray()

        override fun getPort(): Int = 443

        override fun getPrincipals(): Array<Principal> = emptyArray()

        override fun ignore() = Unit

        override fun proceed(privateKey: PrivateKey, chain: Array<X509Certificate>) = Unit
    }

    private class RecordingSafeBrowsingResponse : SafeBrowsingResponse() {
        var interstitialShown: Boolean = false
        var allowReporting: Boolean = false

        override fun backToSafety(report: Boolean) = Unit

        override fun proceed(report: Boolean) = Unit

        override fun showInterstitial(allowReporting: Boolean) {
            interstitialShown = true
            this.allowReporting = allowReporting
        }
    }

    private class RecordingPermissionRequest : PermissionRequest() {
        var denied: Boolean = false

        override fun deny() {
            denied = true
        }

        override fun getOrigin(): Uri = error("Not used by this test")

        override fun getResources(): Array<String> = emptyArray()

        override fun grant(resources: Array<String>) = Unit
    }
}
