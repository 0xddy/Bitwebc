package cn.lmcw.bitwebc.filechooser

import cn.lmcw.bitwebc.core.dsl.FileChooserFactory
import cn.lmcw.bitwebc.core.dsl.IntegrationsConfig

/** Installs the default HTML file chooser explicitly for this Session. */
fun IntegrationsConfig.fileChooser() {
    fileChooser(FileChooserFactory { context ->
        BitwebcFileChooserFactory.createDefault(
            activity = context.activity,
            lifecycleOwner = context.lifecycleOwner,
            tag = context.activityResultKey,
            eventReporter = context.reportEvent
        )
    })
}
