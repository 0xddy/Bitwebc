package cn.lmcw.bitwebc.core.dsl

import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.IFileChooserHandler
import cn.lmcw.bitwebc.core.api.IDownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent

/**
 * 可选模块（如 bitwebc-filechooser、bitwebc-download）在此注册默认实现。
 * 调用方只需 [BitwebcBuilder.autoFileChooserHandler]/[BitwebcBuilder.autoDownload]，无需关心 reporter。
 */
object BitwebcPlugins {

    internal var defaultFileChooserFactory: ((ComponentActivity, (BitwebcEvent) -> Unit) -> IFileChooserHandler)? = null
        private set
    internal var defaultDownloadFactory: ((ComponentActivity, (BitwebcEvent) -> Unit) -> IDownloadHandler)? = null
        private set

    /**
     * 由 bitwebc-filechooser 等模块在初始化时调用；reporter 由 Core 注入。
     */
    fun registerDefaultFileChooser(factory: (ComponentActivity, (BitwebcEvent) -> Unit) -> IFileChooserHandler) {
        defaultFileChooserFactory = factory
    }

    /**
     * 由 bitwebc-download 等模块在初始化时调用；reporter 由 Core 在创建时注入。
     */
    fun registerDefaultDownload(factory: (ComponentActivity, (BitwebcEvent) -> Unit) -> IDownloadHandler) {
        defaultDownloadFactory = factory
    }
}
