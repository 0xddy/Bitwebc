package cn.lmcw.bitwebc.filechooser

import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import cn.lmcw.bitwebc.core.api.IFileChooserHandler
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlin.jvm.JvmStatic

/**
 * 默认文件选择实现工厂；创建 [IFileChooserHandler] 供处理 input type="file"。
 */
object BitwebcFileChooserFactory {

    /**
     * 使用默认行为创建文件选择处理器（Core 插件或直接调用）。
     */
    @JvmStatic
    fun createDefault(
        activity: ComponentActivity,
        eventReporter: ((BitwebcEvent) -> Unit)? = null
    ): IFileChooserHandler {
        return object : IFileChooserHandler {
            override fun createWebChromeClient(next: WebChromeClient?): WebChromeClient {
                return DefaultFileChooserHandler(activity, eventReporter, next)
            }
        }
    }
}
