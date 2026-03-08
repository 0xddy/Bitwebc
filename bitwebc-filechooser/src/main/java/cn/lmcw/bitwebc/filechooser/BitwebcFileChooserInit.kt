package cn.lmcw.bitwebc.filechooser

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import cn.lmcw.bitwebc.core.dsl.BitwebcPlugins

/**
 * 由系统自动加载，向 Core 注册默认文件选择实现；调用方无需关心 reporter。
 */
class BitwebcFileChooserInit : ContentProvider() {
    override fun onCreate(): Boolean {
        BitwebcPlugins.registerDefaultFileChooser { activity, reporter ->
            BitwebcFileChooserFactory.createDefault(activity, reporter)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
