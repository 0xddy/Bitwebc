package cn.lmcw.bitwebc.core.api

import android.webkit.WebView

/**
 * WebView 回收策略抽象。实现方负责�?WebView 放入池、或执行销毁等逻辑�? * 通过 [cn.lmcw.bitwebc.core.dsl.BitwebcBuilder] 注入，使 core 不依赖具体池实现�? * 便于替换�?LRU、定时清理等自定义策略�? */
fun interface WebViewRecycler {
    fun recycle(webView: WebView)
}
