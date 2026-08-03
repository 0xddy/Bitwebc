package cn.lmcw.bitwebc.sample

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {

    @Test
    fun explicitIntegrationsAndWebViewLifecycleStartSuccessfully() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertWebViewAttached(scenario)
            scenario.recreate()
            assertWebViewAttached(scenario)
        }
    }

    private fun assertWebViewAttached(scenario: ActivityScenario<MainActivity>) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity { activity ->
            assertNotNull(findWebView(activity.window.decorView))
        }
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
