package cn.lmcw.bitwebc.sample

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast

class SampleJsBridge(
    private val context: Context
) {
    @JavascriptInterface
    fun showToast(message: String) {
        Toast.makeText(context, "JS->Native: $message", Toast.LENGTH_SHORT).show()
    }
}
