package cn.lmcw.bitwebc.download

import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.DownloadHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler

object BitwebcDownloadFactory {
    /** Creates a controller with the default download configuration. */
    @JvmStatic
    fun createDefaultController(
        activity: ComponentActivity,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadController {
        return BitwebcDownloadHandler(activity = activity, config = DownloadConfig(), eventReporter = eventReporter)
    }

    /** Creates a controller with an explicit download configuration. */
    @JvmStatic
    fun createController(
        activity: ComponentActivity,
        config: DownloadConfig,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadController {
        return BitwebcDownloadHandler(activity = activity, config = config, eventReporter = eventReporter)
    }

    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadHandler {
        return createDefaultController(activity = activity, eventReporter = eventReporter)
    }

    @JvmStatic
    fun create(
        activity: ComponentActivity,
        config: DownloadConfig,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): DownloadHandler {
        return createController(activity = activity, config = config, eventReporter = eventReporter)
    }
}
