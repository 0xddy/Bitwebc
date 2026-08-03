package cn.lmcw.bitwebc.filechooser

import androidx.core.content.FileProvider

/** Module-specific provider so optional integrations do not collide during manifest merge. */
class BitwebcFileChooserFileProvider : FileProvider()
