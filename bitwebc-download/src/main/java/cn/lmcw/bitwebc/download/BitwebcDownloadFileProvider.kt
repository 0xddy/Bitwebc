package cn.lmcw.bitwebc.download

import androidx.core.content.FileProvider

/** Module-specific provider so optional integrations do not collide during manifest merge. */
class BitwebcDownloadFileProvider : FileProvider()
