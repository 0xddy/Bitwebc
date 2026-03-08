package cn.lmcw.bitwebc.core.api

import android.webkit.WebChromeClient

/**
 * 文件选择行为抽象。实现此接口以提供处理 &lt;input type="file"&gt; 的 [WebChromeClient]（相册、相机、文档等）。
 * 默认实现见 bitwebc-filechooser 模块。
 */
interface IFileChooserHandler {
    /**
     * 创建用于链式调用的 [WebChromeClient]，[next] 为下一段。
     */
    fun createWebChromeClient(next: WebChromeClient?): WebChromeClient
}
