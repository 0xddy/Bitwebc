package cn.lmcw.bitwebc.sample

import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewFeature
import cn.lmcw.bitwebc.core.bridge.evaluateJavascriptSafe
import cn.lmcw.bitwebc.core.dsl.BitwebcSession
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.ui.BitwebcView
import cn.lmcw.bitwebc.download.BitwebcDownloadFactory
import cn.lmcw.bitwebc.download.config.DownloadConfig
import cn.lmcw.bitwebc.download.ui.DownloadConfirmUi
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private var session: BitwebcSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        applySystemBarsAppearance()
        setContentView(R.layout.activity_main)

        val bitwebcView = findViewById<BitwebcView>(R.id.bitwebc_view)
        applySystemBarsInsets(bitwebcView)
        val sampleJsBridge = SampleJsBridge(this)

        var lastBackTime = 0L
        onBackPressedDispatcher.addCallback(this) {
            if (System.currentTimeMillis() - lastBackTime < 2000) {
                finish()
            } else {
                lastBackTime = System.currentTimeMillis()
                Toast.makeText(this@MainActivity, "再按一次退出", Toast.LENGTH_SHORT).show()
            }
        }

        val useOfflinePkg = false
        val offlineOrigin = "https://app.bitwebc.demo"
        val loadUrl = if (useOfflinePkg) {
            "$offlineOrigin/index.html"
        } else {
            "https://jd.com"
        }
        val customErrorView = LayoutInflater.from(this)
            .inflate(R.layout.view_web_error, bitwebcView, false)

        session = bitwebcView.setup(this) {
            loadUrl(loadUrl)
            errorPage(
                errorView = customErrorView,
                retryViewId = R.id.btn_retry,
                errorMessageViewId = R.id.tv_error_message
            )
            webViewInterceptor(object : WebViewClient() {

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val url = request.url?.toString().orEmpty()
                    if (url.startsWith("bitwebc://close")) {
                        finish()
                        return true
                    }
                    return false
                }
            })
            eventListener { event ->
                if (event is BitwebcEvent.SslError) {
                    val host = event.url?.toUri()?.host.orEmpty()
                    Toast.makeText(this@MainActivity, "SSL 证书异常，已拦截: $host", Toast.LENGTH_SHORT).show()
                }
            }
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
                cacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK)
                disableScrollBars()
                disableLongPressSelection()
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
            poolRecycleOptions {
                clearCacheOnRecycle(enable = true, includeDisk = false)
            }

            val customConfirmUi = DownloadConfirmUi { act, request, onDecision ->
                val fileName = request.url.substringAfterLast('/').ifBlank { "未知文件" }
                AlertDialog.Builder(act)
                    .setTitle("自定义下载确认")
                    .setMessage("确认下载文件：$fileName ?\nURL: ${request.url.take(80)}")
                    .setPositiveButton("下载") { _, _ -> onDecision(true) }
                    .setNegativeButton("取消") { _, _ -> onDecision(false) }
                    .setOnCancelListener { onDecision(false) }
                    .show()
            }

            registerDownloadHandler { activity ->
                BitwebcDownloadFactory.create(
                    activity,
                    DownloadConfig(
                        confirmBeforeDownload = true,
                        confirmUi = customConfirmUi
                    )
                )
            }


            jsBridge("BitwebcApp", sampleJsBridge)

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
        session?.let { activeSession ->
            lifecycleScope.launch {
                try {
                    activeSession.events.collect { event ->
                        when (event) {
                            is BitwebcEvent.PageStarted ->
                                Log.d(TAG, "页面开始加载: ${event.url}")
                            is BitwebcEvent.PageFinished ->
                                Log.d(TAG, "页面加载完成: ${event.url}")
                            is BitwebcEvent.PageError ->
                                Log.e(TAG, "页面错误: ${event.url} | ${event.message}")
                            is BitwebcEvent.HttpError ->
                                Log.w(TAG, "HTTP 状态: ${event.url} | ${event.statusCode}")
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
                } catch (e: Throwable) {
                    Log.e(TAG, "events error", e)
                }
            }
        }

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

    override fun onResume() {
        super.onResume()
        applySystemBarsAppearance()
    }

    private fun applySystemBarsInsets(target: View) {
        val initialLeft = target.paddingLeft
        val initialTop = target.paddingTop
        val initialRight = target.paddingRight
        val initialBottom = target.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(target) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                initialLeft + bars.left,
                initialTop + bars.top,
                initialRight + bars.right,
                initialBottom + bars.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    private fun applySystemBarsAppearance() {
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isNight = nightMode == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight
    }

    companion object {
        private const val TAG = "BitwebcSample"
    }
}
