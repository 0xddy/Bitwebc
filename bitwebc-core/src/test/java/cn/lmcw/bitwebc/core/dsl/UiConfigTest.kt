package cn.lmcw.bitwebc.core.dsl

import androidx.activity.ComponentActivity
import android.content.Context
import android.view.View
import cn.lmcw.bitwebc.core.api.WebIndicator
import cn.lmcw.bitwebc.core.testutil.UnsafeAndroidAllocator
import cn.lmcw.bitwebc.core.ui.DefaultWebLayout
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Test

class UiConfigTest {

    @Test
    fun `default UI factories create session-owned instances`() {
        val activity = UnsafeAndroidAllocator.allocate(ComponentActivity::class.java)
        val snapshot = UiConfig().apply {
            customIndicator { StubIndicator() }
        }.snapshot()

        assertNotSame(
            snapshot.layoutFactory.create(activity),
            snapshot.layoutFactory.create(activity)
        )
        assertNotSame(
            snapshot.indicatorFactory.create(activity),
            snapshot.indicatorFactory.create(activity)
        )
    }

    private class StubIndicator : WebIndicator {
        override fun createView(context: Context): View = error("not used")
        override fun onPageStarted() = Unit
        override fun onProgressChanged(progress: Int) = Unit
        override fun onPageFinished() = Unit
        override fun reset() = Unit
    }

    @Test
    fun `layout choices cannot silently override each other`() {
        val config = UiConfig()
        config.layout { DefaultWebLayout() }

        assertThrows(IllegalStateException::class.java) {
            config.errorPage { }
        }
    }
}
