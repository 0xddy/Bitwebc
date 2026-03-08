package cn.lmcw.bitwebc.core.api

import android.webkit.DownloadListener

/**
 * 下载行为抽象。实现此接口以提供自定义下载逻辑（如内置下载、跳转浏览器等）。
 * 默认实现见 bitwebc-download 模块。
 */
interface IDownloadHandler : DownloadListener
