package cn.lmcw.bitwebc.core.dsl

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import cn.lmcw.bitwebc.core.pool.BitwebcWebViewPool

object Bitwebc {
    fun with(activity: ComponentActivity, block: BitwebcBuilder.() -> Unit): BitwebcSession {
        return BitwebcBuilder(activity, activity).apply(block).launch()
    }

    fun with(fragment: Fragment, block: BitwebcBuilder.() -> Unit): BitwebcSession {
        val activity = fragment.requireActivity() as? ComponentActivity
            ?: error("Bitwebc.with(fragment) requires Fragment hosted by ComponentActivity")
        return BitwebcBuilder(activity, fragment.viewLifecycleOwner).apply(block).launch()
    }

    fun prewarm(activity: ComponentActivity, count: Int = 1) {
        BitwebcWebViewPool.prewarm(activity, count)
    }

    fun setMaxPoolSize(size: Int) {
        BitwebcWebViewPool.setMaxPoolSize(size)
    }

    fun clearPool() {
        BitwebcWebViewPool.clear()
    }
}
