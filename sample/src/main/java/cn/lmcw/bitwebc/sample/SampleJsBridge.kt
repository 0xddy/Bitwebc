package cn.lmcw.bitwebc.sample

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.os.Handler
import android.os.Looper

class SampleJsBridge(
    private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(context, "JS->Native: $message", Toast.LENGTH_SHORT).show()
        }
    }
}
