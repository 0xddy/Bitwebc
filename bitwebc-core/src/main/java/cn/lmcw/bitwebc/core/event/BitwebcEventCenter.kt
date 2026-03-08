package cn.lmcw.bitwebc.core.event

import android.app.Activity
import java.util.concurrent.ConcurrentHashMap

object BitwebcEventCenter {
    private val hubs = ConcurrentHashMap<Int, BitwebcEventHub>()

    fun hub(activity: Activity): BitwebcEventHub {
        return hubs.getOrPut(activity.hashCode()) { BitwebcEventHub() }
    }

    fun reporter(activity: Activity): (BitwebcEvent) -> Unit {
        val hub = hub(activity)
        return { event -> hub.emit(event) }
    }

    fun clear(activity: Activity) {
        hubs.remove(activity.hashCode())
    }
}
