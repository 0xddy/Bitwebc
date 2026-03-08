package cn.lmcw.bitwebc.core.dsl

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.fragment.app.Fragment
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.event.BitwebcEventCenter
import cn.lmcw.bitwebc.core.pool.BitwebcWebViewPool
import kotlinx.coroutines.flow.SharedFlow

object Bitwebc {
    fun with(activity: ComponentActivity, block: BitwebcBuilder.() -> Unit): BitwebcSession {
        return BitwebcBuilder(activity).apply(block).launch()
    }

    fun with(fragment: Fragment, container: ViewGroup, block: BitwebcBuilder.() -> Unit): BitwebcSession {
        val activity = fragment.requireActivity() as? ComponentActivity
            ?: error("Bitwebc requires Fragment hosted by ComponentActivity")
        return BitwebcBuilder(activity).apply {
            attachTo(container)
            block()
        }.launch()
    }

    fun prewarm(activity: ComponentActivity, count: Int = 1) {
        BitwebcWebViewPool.prewarm(activity, count)
    }

    fun events(activity: ComponentActivity): SharedFlow<BitwebcEvent> {
        return BitwebcEventCenter.hub(activity).events
    }

    fun eventReporter(activity: ComponentActivity): (BitwebcEvent) -> Unit {
        return BitwebcEventCenter.reporter(activity)
    }
}
