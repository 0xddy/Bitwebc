package cn.lmcw.bitwebc.download.storage

import android.content.Context
import java.io.File

/** 下载存储策略，创建 [FileSink] */
interface DownloadStorage {

    fun createSink(context: Context, fileName: String, mimeType: String): FileSink?

    fun setSubDirectory(subDir: String?) {}
}
