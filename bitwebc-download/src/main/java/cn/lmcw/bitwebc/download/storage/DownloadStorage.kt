package cn.lmcw.bitwebc.download.storage

import android.content.Context
import java.io.File

/**
 * 下载文件存储策略：根据系统版本与配置创建 [FileSink]。
 * 默认实现：Q+ 使用 MediaStore.Downloads，以下使用应用外部目录 + FileProvider。
 */
interface DownloadStorage {

    /**
     * 创建可写的目标，调用方负责写入后调用 [FileSink.close]。
     * @param fileName 建议文件名（可由实现做安全处理）
     * @param mimeType 建议 MIME 类型
     * @return 非空表示成功
     */
    fun createSink(context: Context, fileName: String, mimeType: String): FileSink?

    /**
     * 可选：指定子目录（仅对基于目录的实现有效，如 pre-Q 的 getExternalFilesDir）。
     */
    fun setSubDirectory(subDir: String?) {}
}
