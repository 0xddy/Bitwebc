package cn.lmcw.bitwebc.download

import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler

object BitwebcDownloadFactory {
    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadHandler {
        return BitwebcDownloadHandler(activity = activity, config = DownloadConfig(), eventReporter = eventReporter)
    }
    @JvmStatic
    fun create(
        activity: ComponentActivity,
        config: DownloadConfig,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadHandler {
        return BitwebcDownloadHandler(activity = activity, config = config, eventReporter = eventReporter)
    }
}
