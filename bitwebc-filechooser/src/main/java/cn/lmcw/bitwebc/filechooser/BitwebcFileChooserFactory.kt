package cn.lmcw.bitwebc.filechooser

import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.FileChooserHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlin.jvm.JvmStatic

/** ????????????? [FileChooserHandler] ??? input type="file"? */
object BitwebcFileChooserFactory {

    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        lifecycleOwner: LifecycleOwner = activity,
        tag: String = "default",
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): FileChooserHandler {
        return object : FileChooserHandler {
            override fun createWebChromeClient(next: WebChromeClient?): WebChromeClient {
                return DefaultFileChooserHandler(activity, lifecycleOwner, tag, eventReporter, next)
            }
        }
    }
}
