package cn.lmcw.bitwebc.download

import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.IDownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler
import kotlin.jvm.JvmStatic

/**
 * 默认下载实现工厂；创建 [IDownloadHandler] 供 [android.webkit.WebView.setDownloadListener] 使用。
 */
object BitwebcDownloadFactory {

    /**
     * 使用默认配置创建下载处理器（Core 插件或直接调用）。
     */
    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): IDownloadHandler {
        return BitwebcDownloadHandler(activity = activity, config = DownloadConfig(), eventReporter = eventReporter)
    }

    /**
     * 使用自定义配置创建下载处理器。
     */
    @JvmStatic
    fun create(
        activity: ComponentActivity,
        config: DownloadConfig,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): IDownloadHandler {
        return BitwebcDownloadHandler(activity = activity, config = config, eventReporter = eventReporter)
    }
}
