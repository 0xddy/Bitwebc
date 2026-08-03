package cn.lmcw.bitwebc.download

import cn.lmcw.bitwebc.core.dsl.DownloadFactory
import cn.lmcw.bitwebc.core.dsl.IntegrationsConfig
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.handler.BitwebcDownloadHandler

/** Installs the download module explicitly for this Session. */
fun IntegrationsConfig.downloads(
    config: DownloadConfig = DownloadConfig(),
    onController: (DownloadController) -> Unit = {}
) {
    downloads(DownloadFactory { context ->
        val controller = BitwebcDownloadFactory.createController(
            activity = context.activity,
            config = config,
            eventReporter = context.reportEvent
        )
        (controller as BitwebcDownloadHandler).whenSessionReady(onController)
        controller
    })
}
