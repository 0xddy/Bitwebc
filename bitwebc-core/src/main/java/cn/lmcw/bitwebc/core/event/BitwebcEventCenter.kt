package cn.lmcw.bitwebc.core.event

import android.app.Activity
import java.util.WeakHashMap

object BitwebcEventCenter {
    private val hubs = WeakHashMap<Activity, BitwebcEventHub>()
    private val lock = Any()

    fun hub(activity: Activity): BitwebcEventHub {
        synchronized(lock) {
            return hubs.getOrPut(activity) { BitwebcEventHub() }
        }
    }

    fun reporter(activity: Activity): (BitwebcEvent) -> Unit {
        val hub = hub(activity)
        return { event -> hub.emit(event) }
    }

    fun clear(activity: Activity) {
        synchronized(lock) {
            hubs.remove(activity)
        }
    }
}
