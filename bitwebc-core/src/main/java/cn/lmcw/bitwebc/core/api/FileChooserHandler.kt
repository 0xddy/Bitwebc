package cn.lmcw.bitwebc.core.api

import android.webkit.WebChromeClient

/**
 * 文件选择行为抽象。实现此接口以提供处�?&lt;input type="file"&gt; �?[WebChromeClient]（相册、相机、文档等）�? * 默认实现�?bitwebc-filechooser 模块�? */
interface FileChooserHandler {
    /**
     * 创建用于链式调用�?[WebChromeClient]，[next] 为下一段�?     */
    fun createWebChromeClient(next: WebChromeClient?): WebChromeClient
}
