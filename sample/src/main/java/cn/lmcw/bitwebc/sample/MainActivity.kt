package cn.lmcw.bitwebc.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebSettings
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.bridge.evaluateJavascriptSafe
import cn.lmcw.bitwebc.core.dsl.Bitwebc
import cn.lmcw.bitwebc.core.dsl.BitwebcSession
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewFeature
class MainActivity : AppCompatActivity() {

    private var session: BitwebcSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = findViewById<FrameLayout>(R.id.web_host)
        val sampleJsBridge = SampleJsBridge(this)

        // 使用离线包：通过静态资源拦截引擎加载 assets/offline_pkg/ 下的页面（不请求网络）
        val useOfflinePkg = true
        val offlineOrigin = "https://app.bitwebc.demo"
        val loadUrl = if (useOfflinePkg) {
            "$offlineOrigin/index.html"
        } else {
            "https://asmrby.com"
        }

        session = Bitwebc.with(this) {
            attachTo(host)
            loadUrl(loadUrl)

            settings {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                supportZoom = false
                builtInZoomControls = false
                displayZoomControls = false
                allowFileAccess = false
                userAgentSuffix = "BitwebcSample/1.0"
                // 缓存模式：LOAD_DEFAULT | LOAD_CACHE_ELSE_NETWORK | LOAD_NO_CACHE | LOAD_CACHE_ONLY
                cacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK)
                // 离线包 / 静态资源拦截：将 URL 前缀映射到 assets 目录，请求时从 assets 读文件返回
                if (useOfflinePkg) {
                    interceptors {
                        assetsRoute("$offlineOrigin/", "offline_pkg")
                    }
                }
            }

            indicator {
                color("#FF3B30")
                heightDp(3)
            }

            autoFileChooserHandler(true)
            autoDownload(true)
            jsBridge("BitwebcApp", sampleJsBridge)

            // WebMessage：Native 仅接收前端消息。注意：sendPort 已通过 postWebMessage 传给前端，
            // 在 Chromium 中该 port 视为“已转移”，Native 端不能再对其 postMessage，否则会抛 Port is already closed or transferred。
            // Native → JS 通知请用 evaluateJavascript（如 onNativeReady）。
            messagePorts { _, receivePort, _ ->
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK)) {
                    receivePort.setWebMessageCallback(Handler(Looper.getMainLooper()), object : WebMessagePortCompat.WebMessageCallbackCompat() {
                        override fun onMessage(port: WebMessagePortCompat, message: WebMessageCompat?) {
                            val data = message?.data ?: ""
                            Log.d(TAG, "WebMessage 收到前端: $data")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "WebMessage: $data", Toast.LENGTH_SHORT).show()
                            }
                        }
                    })
                }
            }
        }

        // 通过 Flow 收集 Bitwebc 事件（页面、下载、文件选择、全屏等）
        lifecycleScope.launch {
            Bitwebc.events(this@MainActivity)
                .catch { e -> Log.e(TAG, "events error", e) }
                .collect { event ->
                    when (event) {
                        is BitwebcEvent.PageStarted ->
                            Log.d(TAG, "页面开始加载: ${event.url}")
                        is BitwebcEvent.PageFinished ->
                            Log.d(TAG, "页面加载完成: ${event.url}")
                        is BitwebcEvent.PageError ->
                            Log.e(TAG, "页面错误: ${event.url} | ${event.message}")
                        is BitwebcEvent.SslError ->
                            Log.e(TAG, "SSL 错误: ${event.url} | ${event.message}")
                        is BitwebcEvent.DownloadQueued ->
                            Log.d(TAG, "下载已加入队列: ${event.taskId} | ${event.url}")
                        is BitwebcEvent.DownloadProgress ->
                            Log.d(TAG, "下载进度: ${event.fileName} ${event.progress}%")
                        is BitwebcEvent.DownloadSuccess ->
                            Log.d(TAG, "下载成功: ${event.fileName}")
                        is BitwebcEvent.DownloadFailed ->
                            Log.e(TAG, "下载失败: ${event.reason}")
                        is BitwebcEvent.DownloadPermissionDenied ->
                            Log.w(TAG, "下载权限被拒绝")
                        is BitwebcEvent.FileChooserPermissionDenied ->
                            Log.w(TAG, "文件选择权限被拒绝: ${event.reason}")
                        is BitwebcEvent.FileChooserCancelled ->
                            Log.d(TAG, "文件选择已取消: ${event.reason}")
                        is BitwebcEvent.FileChooserFailed ->
                            Log.e(TAG, "文件选择失败: ${event.reason}")
                        is BitwebcEvent.FullscreenChanged ->
                            Log.d(TAG, "全屏状态: ${event.fullscreen}")
                        is BitwebcEvent.SchemeFallback ->
                            Log.d(TAG, "Scheme 回退: ${event.rawUrl} | ${event.reason}")
                        is BitwebcEvent.RenderProcessGone ->
                            Log.e(TAG, "渲染进程退出: crash=${event.didCrash}")
                    }
                }
        }

        // Native → JS：页面加载后通知前端（demo 页需实现 window.onNativeReady）
        session?.webView?.postDelayed({
            session?.webView?.evaluateJavascriptSafe(
                "typeof window.onNativeReady === 'function' && window.onNativeReady('Bitwebc 已就绪');"
            )
        }, 800L)
    }

    override fun onDestroy() {
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BitwebcSample"
    }
}
