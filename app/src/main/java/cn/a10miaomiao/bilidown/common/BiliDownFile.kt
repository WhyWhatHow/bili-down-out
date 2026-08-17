package cn.a10miaomiao.bilidown.common

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.common.file.MiaoFile
import cn.a10miaomiao.bilidown.common.file.MiaoJavaFile
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.shizuku.util.RemoteServiceUtil
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.jvm.Throws


class BiliDownFile(
    val context: Context,
    val packageName: String,
    val enabledShizuku: Boolean,
) {

    private val TAG = "BiliDownFile"
    private val externalStorage = Environment.getExternalStorageDirectory()
    private val DIR_DOWNLOAD = "download"

    companion object {
        /** Shizuku binder 读取超时：大库扫描留足余量，同时避免服务卡死无限转圈 */
        internal const val SHIZUKU_CALL_TIMEOUT_MS: Long = 60_000L
    }

    var path = ""
    var list = emptyList<String>()

    fun canRead(): Boolean {
        if (enabledShizuku) {
            return true
        }
        val downloadDir = createMiaoFile(DIR_DOWNLOAD)
        return downloadDir.canRead()
    }

    @Throws(TimeoutCancellationException::class)
    suspend fun readDownloadList(
        onScanned: (scanned: Int) -> Unit = {},
    ): List<BiliDownloadEntryAndPathInfo> {
        val downloadDir = createMiaoFile(DIR_DOWNLOAD)
        val list = mutableListOf<BiliDownloadEntryAndPathInfo>()
        MiaoLog.debug { enabledShizuku.toString() }
        if (enabledShizuku) {
            MiaoLog.debug { downloadDir.path }
            val userService = RemoteServiceUtil.getUserService()
            // binder 调用不响应协程取消，服务进程卡死时会无限阻塞：
            // 整体包一层超时，超时抛 TimeoutCancellationException 由页面提示用户
            list.addAll(withTimeout(SHIZUKU_CALL_TIMEOUT_MS) {
                userService.readDownloadList(downloadDir.path)
            })
            onScanned(list.size)
        } else {
            var scanned = 0
            downloadDir.listFiles()
                .filter { it.isDirectory }
                .forEach {
                    Log.d(TAG, it.path)
                    val sub = readDownloadDirectory(it) { scanned = it; onScanned(scanned) }
                    scanned += sub.size
                    onScanned(scanned)
                }
        }
        return list.reversed()
    }

    suspend fun readDownloadDirectory(
        dir: MiaoFile,
        onScanned: (scanned: Int) -> Unit = {},
    ): List<BiliDownloadEntryAndPathInfo> {
        if (enabledShizuku) {
            val userService = RemoteServiceUtil.getUserService()
            val result = withTimeout(SHIZUKU_CALL_TIMEOUT_MS) {
                userService.readDownloadDirectory(dir.path)
            }
            onScanned(result.size)
            return result
        }
        if (!dir.exists() || !dir.isDirectory) {
            return emptyList()
        }
        val result = mutableListOf<BiliDownloadEntryAndPathInfo>()
        var scanned = 0
        dir.listFiles()
            .filter { pageDir -> pageDir.isDirectory }
            .forEach {
                val entryFile = if (it is MiaoDocumentFile) {
                    MiaoDocumentFile(context, it.documentFile, "/entry.json")
                } else {
                    MiaoJavaFile(it.path + "/entry.json")
                }
                if (!entryFile.exists()) {
                    return@forEach
                }
                val entryJson = entryFile.readText()
                val entry = BiliEntryJsonParser.parseOrNull(entryJson)
                    ?: return@forEach
                result.add(
                    BiliDownloadEntryAndPathInfo(
                        entry = entry,
                        entryDirPath = it.path,
                        pageDirPath = dir.path
                    )
                )
                scanned++
                onScanned(scanned)
            }
        return result
    }

    //获取指定目录的权限
    fun startFor(REQUEST_CODE_FOR_DIR: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MiaoDocumentFile.requestFolderPermission(
                context as Activity,
                REQUEST_CODE_FOR_DIR,
                getDocumentFileId()
            )
        }
    }

    private fun createMiaoFile(
        dirName: String,
    ): MiaoFile {
        if (!enabledShizuku && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {  // 10以上
            return MiaoDocumentFile(
                context,
                getDocumentFileId(),
                File.separator + dirName
            )
        }
        var file = File(getExternalDir(), dirName)
        if (!enabledShizuku && !file.exists()) {
            file.mkdir()
        }
        return MiaoJavaFile(file)
    }

    private fun getExternalDir(): String {
        var externalStorage = Environment.getExternalStorageDirectory()
        var path = externalStorage.absolutePath + "/Android/data/" + packageName
        return path
    }

    private fun getDocumentFileId(): String {
        var path = "primary:Android/data/$packageName"
        return path
    }




}