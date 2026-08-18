package cn.a10miaomiao.bilidown.common

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File

class BiliDownOutFile(
    name: String,
    author: String = "",
) {

    companion object {
        const val DIR_NAME = "BiliDownOut"
        val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

        fun getOutFolderUri(): Uri {
            return Uri.parse("content://com.android.externalstorage.documents/document/primary:Download%2f${DIR_NAME}")
        }

        fun getOutFolderPath(): String {
            return downloadDir.path + File.separator + DIR_NAME
        }

        fun sanitizeFileName(name: String): String {
            return name.replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
        }
    }

    private val outDir = if (author.isBlank()) {
        File(downloadDir, DIR_NAME)
    } else {
        File(File(downloadDir, DIR_NAME), sanitizeFileName(author))
    }

    init {
        if (!outDir.exists()) {
            outDir.mkdirs()
        }
    }

    val file = File(outDir, name)

    val path get() = file.path
    val name get() = file.name

    fun exists() = file.exists()
}