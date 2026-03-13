package cn.lmcw.bitwebc.download.storage

import android.net.Uri
import java.io.OutputStream

/** 下载目标，写入完成后调用 onFinish */
data class FileSink(
    val uri: Uri,
    val outputStream: OutputStream,
    val onFinish: () -> Unit
) {
    fun close() {
        outputStream.close()
        onFinish()
    }
}
