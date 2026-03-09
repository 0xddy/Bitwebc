package cn.lmcw.bitwebc.core.dsl

import androidx.activity.ComponentActivity
import androidx.lifecycle.LifecycleOwner
import cn.lmcw.bitwebc.core.api.FileChooserHandler
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent

/** ? bitwebc-filechooser?bitwebc-download ?????????? */
object BitwebcPlugins {

    internal var defaultFileChooserFactory: ((ComponentActivity, LifecycleOwner, String, (BitwebcEvent) -> Unit) -> FileChooserHandler)? = null
        private set
    internal var defaultDownloadFactory: ((ComponentActivity, (BitwebcEvent) -> Unit) -> DownloadHandler)? = null
        private set

    /** ? bitwebc-filechooser ???????????reporter ? Core ??? */
    fun registerDefaultFileChooser(factory: (ComponentActivity, LifecycleOwner, String, (BitwebcEvent) -> Unit) -> FileChooserHandler) {
        defaultFileChooserFactory = factory
    }

    /** ? bitwebc-download ???????????reporter ? Core ??????? */
    fun registerDefaultDownload(factory: (ComponentActivity, (BitwebcEvent) -> Unit) -> DownloadHandler) {
        defaultDownloadFactory = factory
    }
}
