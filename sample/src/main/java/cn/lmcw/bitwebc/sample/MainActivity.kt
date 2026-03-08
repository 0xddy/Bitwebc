package cn.lmcw.bitwebc.sample

import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cn.lmcw.bitwebc.core.bridge.evaluateJavascriptSafe
import cn.lmcw.bitwebc.core.dsl.Bitwebc
import cn.lmcw.bitwebc.core.dsl.BitwebcSession
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var session: BitwebcSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = findViewById<FrameLayout>(R.id.web_host)
        val sampleJsBridge = SampleJsBridge(this)

        session = Bitwebc.with(this) {
            attachTo(host)
            loadUrl("https://asmrby.com")

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
            }
            indicator {
                color("#FF3B30")
                heightDp(3)
            }
            autoFileChooserHandler(true)
            autoDownload(true)
            jsBridge("BitwebcApp", sampleJsBridge)
        }
 
        lifecycleScope.launch {
            Bitwebc.events(this@MainActivity).collect { event ->
                if (event is BitwebcEvent.DownloadFailed) {
                    Log.e("BitwebcSample", "下载失败: ${event.reason}")
                } else {
                    Log.d("BitwebcSample", "event=$event")
                }
            }
        }

        // 常规 Native -> JS 注入：页面 ready 后主动推送一段数据给前端。
        session?.webView?.postDelayed({
            session?.webView?.evaluateJavascriptSafe(
                "window.onNativeReady && window.onNativeReady('Bitwebc ready from native');"
            )
        }, 600L)
    }

    override fun onDestroy() {
        super.onDestroy()
        session?.release()
        session = null
    }
}
