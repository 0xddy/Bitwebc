package cn.lmcw.bitwebc.compose

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cn.lmcw.bitwebc.core.settings.WebResourceCachePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class BitwebcComposeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unrelatedRecompositionKeepsTheSameWebView() {
        var generation by mutableIntStateOf(0)

        composeRule.setContent {
            generation // Deliberately observed to trigger this call site's recomposition.
            val state = rememberBitwebcState("about:blank", BitwebcSavePolicy.None)
            Bitwebc(state = state)
        }

        composeRule.waitUntil { findWebView(composeRule.activity.window.decorView) != null }
        lateinit var first: WebView
        composeRule.runOnIdle {
            first = checkNotNull(findWebView(composeRule.activity.window.decorView))
            generation += 1
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertSame(first, findWebView(composeRule.activity.window.decorView))
        }
    }

    @Test
    fun leavingCompositionReleasesTheState() {
        var visible by mutableStateOf(true)
        lateinit var state: BitwebcState

        composeRule.setContent {
            state = rememberBitwebcState("about:blank", BitwebcSavePolicy.None)
            if (visible) Bitwebc(state = state)
        }

        composeRule.waitUntil { findWebView(composeRule.activity.window.decorView) != null }
        composeRule.runOnIdle { visible = false }
        composeRule.waitUntil { findWebView(composeRule.activity.window.decorView) == null }
        composeRule.runOnIdle { assertTrue(state.pageState.isReleased) }
    }

    @Test
    fun changingSessionKeyKeepsTheReplacementSessionBound() {
        var sessionKey by mutableIntStateOf(0)
        lateinit var state: BitwebcState

        composeRule.setContent {
            state = rememberBitwebcState("about:blank", BitwebcSavePolicy.None)
            Bitwebc(state = state, sessionKey = sessionKey.toString())
        }

        composeRule.waitUntil { findWebView(composeRule.activity.window.decorView) != null }
        lateinit var first: WebView
        composeRule.runOnIdle {
            first = checkNotNull(findWebView(composeRule.activity.window.decorView))
            sessionKey += 1
        }
        composeRule.waitUntil {
            findWebView(composeRule.activity.window.decorView)?.let { it !== first } == true
        }
        composeRule.runOnIdle {
            assertNotSame(first, findWebView(composeRule.activity.window.decorView))
            assertFalse(state.pageState.isReleased)
            assertTrue(state.withSession { })
        }
    }

    @Test
    fun releasedSessionIsRecreatedWhileTheHostRemains() {
        lateinit var state: BitwebcState

        composeRule.setContent {
            state = rememberBitwebcState("about:blank", BitwebcSavePolicy.None)
            Bitwebc(state = state)
        }

        composeRule.waitUntil { findWebView(composeRule.activity.window.decorView) != null }
        lateinit var first: WebView
        composeRule.runOnIdle {
            first = checkNotNull(findWebView(composeRule.activity.window.decorView))
            assertTrue(state.withSession { it.release() })
        }
        composeRule.waitUntil(15_000) {
            findWebView(composeRule.activity.window.decorView)?.let { it !== first } == true &&
                !state.pageState.isReleased
        }
    }

    @Test
    fun savedStateCapturesTheLatestNavigationHistory() {
        val restorationTester = StateRestorationTester(composeRule)
        lateinit var state: BitwebcState
        val firstUrl = "data:text/html,<title>first</title><p>first</p>"
        val secondUrl = "data:text/html,<title>second</title><p>second</p>"

        restorationTester.setContent {
            state = rememberBitwebcState(firstUrl)
            Bitwebc(state = state)
        }

        composeRule.waitUntil(15_000) { state.pageState.title == "first" }
        composeRule.runOnIdle { state.loadUrl(secondUrl) }
        composeRule.waitUntil(15_000) {
            state.pageState.title == "second" && state.pageState.canGoBack
        }
        val previousState = state
        val instanceId = state.instanceId

        restorationTester.emulateSavedInstanceStateRestore()

        composeRule.waitUntil(15_000) {
            state !== previousState &&
                state.instanceId == instanceId &&
                state.pageState.canGoBack
        }
        composeRule.runOnIdle { assertTrue(state.goBack()) }
        composeRule.waitUntil(15_000) { state.pageState.title == "first" }
    }

    @Test
    fun urlOnlyOverloadOwnsItsStateAndAcceptsStaticConfiguration() {
        val url = "data:text/html,<title>url-only</title><p>url-only</p>"
        val loaded = AtomicBoolean(false)

        composeRule.setContent {
            Bitwebc(url = url) {
                webSettings {
                    cache { policy = WebResourceCachePolicy.NetworkOnly }
                }
                clients {
                    webViewClient { object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loaded.set(view.title == "url-only")
                        }
                    } }
                }
            }
        }

        composeRule.waitUntil(15_000) { loaded.get() }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
