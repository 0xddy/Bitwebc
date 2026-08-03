package cn.lmcw.bitwebc.sample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebMessagePortCompat
import androidx.webkit.WebViewFeature
import cn.lmcw.bitwebc.compose.Bitwebc
import cn.lmcw.bitwebc.compose.rememberBitwebcState
import cn.lmcw.bitwebc.core.event.BitwebcEvent
import cn.lmcw.bitwebc.core.settings.DarkMode
import cn.lmcw.bitwebc.core.settings.WebResourceCachePolicy
import cn.lmcw.bitwebc.download.downloads
import cn.lmcw.bitwebc.filechooser.fileChooser

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                BitwebcSample()
            }
        }
    }
}

private const val TAG = "BitwebcSample"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BitwebcSample() {
    val context = LocalContext.current
    val offlineOrigin = "https://app.bitwebc.demo"
    val state = rememberBitwebcState("$offlineOrigin/index.html")
    val bridge = remember(context) { SampleJsBridge(context.applicationContext) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text(state.pageState.title ?: state.pageState.url ?: "Bitwebc") },
                actions = {
                    Row {
                        TextButton(
                            enabled = state.pageState.canGoBack,
                            onClick = state::goBack
                        ) { Text("后退") }
                        TextButton(
                            enabled = state.pageState.canGoForward,
                            onClick = state::goForward
                        ) { Text("前进") }
                        TextButton(onClick = state::reload) { Text("刷新") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                Bitwebc(
                    state = state,
                    modifier = Modifier.fillMaxSize(),
                    onEvent = { event ->
                        Log.d(TAG, event.toString())
                        if (event is BitwebcEvent.PageFinished) {
                            state.withSession { session ->
                                session.evaluateJavaScript(
                                    "typeof window.onNativeReady === 'function' && " +
                                        "window.onNativeReady('Bitwebc Compose 已就绪');"
                                )
                            }
                        }
                    }
                ) {
                    webSettings {
                        cache { policy = WebResourceCachePolicy.CacheFirst }
                        userAgent { append("BitwebcSample/1.0") }
                    }
                    display {
                        darkMode = DarkMode.Auto
                        longPressSelectionEnabled = false
                    }
                    resources {
                        assets {
                            route("$offlineOrigin/", "offline_pkg")
                        }
                    }
                    ui {
                        indicator {
                            color("#FF3B30")
                            heightDp(3)
                        }
                    }
                    integrations {
                        fileChooser()
                        downloads()
                    }
                    bridges {
                        javascript("BitwebcApp", bridge, setOf(offlineOrigin))
                        messagePorts(setOf(offlineOrigin)) { _, receivePort, _ ->
                            if (WebViewFeature.isFeatureSupported(
                                    WebViewFeature.WEB_MESSAGE_PORT_SET_MESSAGE_CALLBACK
                                )
                            ) {
                                receivePort.setWebMessageCallback(
                                    Handler(Looper.getMainLooper()),
                                    object : WebMessagePortCompat.WebMessageCallbackCompat() {
                                        override fun onMessage(
                                            port: WebMessagePortCompat,
                                            message: WebMessageCompat?
                                        ) {
                                            Log.d(TAG, "WebMessage: ${message?.data.orEmpty()}")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                if (state.pageState.isLoading) {
                    val progress = state.pageState.progress
                    if (progress in 1..99) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}
