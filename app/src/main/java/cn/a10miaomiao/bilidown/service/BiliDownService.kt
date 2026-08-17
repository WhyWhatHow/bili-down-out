package cn.a10miaomiao.bilidown.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.BiliEntryJsonParser
import cn.a10miaomiao.bilidown.common.CommandUtil
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.db.AppDatabase
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.shizuku.util.RemoteServiceUtil
import cn.a10miaomiao.bilidown.state.AppState
import cn.a10miaomiao.bilidown.state.TaskStatus
import io.microshow.rxffmpeg.RxFFmpegCommandList
import io.microshow.rxffmpeg.RxFFmpegInvoke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.CoroutineContext

class BiliDownService :
    Service(),
    CoroutineScope {

    companion object {
        private const val TAG = "DownloadService"
        /** deleteSourceVideo 返回值：源缓存目录已不存在 */
        const val SOURCE_NOT_FOUND = "source_not_found"
        private val channel = Channel<BiliDownService>()
        private var _instance: BiliDownService? = null

//        val instance get() = _instance

        suspend fun getService(context: Context): BiliDownService {
            _instance?.let { return it }
            startService(context)
            return channel.receive().also {
                _instance = it
            }
        }

        fun startService(context: Context) {
            val intent = Intent(context, BiliDownService::class.java)
            context.startService(intent)
        }
    }

    private lateinit var appDatabase: AppDatabase
    private lateinit var appState: AppState

    private var job: Job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private val myProgressCallback by lazy {
        MyProgressCallback(this@BiliDownService, appState)
    }

    override fun onCreate() {
        super.onCreate()
        val biliDownApp = application as BiliDownApp
        appDatabase = biliDownApp.database
        appState = biliDownApp.state
        job = Job()
        launch {
            channel.send(this@BiliDownService)
            appState.taskStatus.collect {
                if (it is TaskStatus.InIdle) {
                    // 空闲状态，进行下一个任务
                    val waitRecords = appDatabase.outRecordDao()
                        .getAllByStatus(OutRecord.STATUS_WAIT)
                    if (waitRecords.isNotEmpty()) {
                        startTask(waitRecords.first())
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
        _instance = null
    }

    suspend fun exportBiliVideo(
        entryDirPath: String,
        outFile: File,
        deleteSource: Boolean = false,
    ): Boolean {
        val taskStatus = appState.taskStatus.value
        val shizukuState = appState.shizukuState.value
        if (taskStatus != TaskStatus.InIdle && taskStatus !is TaskStatus.Error) {
            toast("有视频正在导出中，请稍后再试")
            return false
        }

        val t = appDatabase.outRecordDao().findByPath(entryDirPath)
        if (t != null && t.status == OutRecord.STATUS_SUCCESS) {
            toast("此视频已导出")
            return false
        }
        // 使用Shizuku
        if (shizukuState.isEnabled) {
            val shizukuUserService = try {
                RemoteServiceUtil.getUserService()
            } catch (e: TimeoutCancellationException) {
                e.printStackTrace()
                toast("Shizuku连接超时！")
                return false
            }
            val errorMessage = shizukuUserService.exportBiliVideo(
                entryDirPath,
                outFile.path,
                MyProgressCallback(this, appState, deleteSource),
            )
            if (errorMessage != null) {
                toast(errorMessage)
                return false
            }
            return true
        }

        // 使用DocumentFile
        if (entryDirPath.startsWith("content:")) {
            return copyAndExportBiliVideo(entryDirPath, outFile, deleteSource)
        }

        // 使用Java File API正常导出
        val entryDirFile = File(entryDirPath)
        val entryJsonFile = File(entryDirPath, "entry.json")
        val entry = BiliEntryJsonParser.parseOrNull(entryJsonFile.readText())
            ?: run {
                toast("entry.json 文件为空或已损坏")
                return false
            }

        val videoDirPath = entryDirPath + "/" + entry.videoDirName
        val videoDir = File(videoDirPath)
        if (!videoDir.exists() || !videoDir.isDirectory) {
            val videoFile = entryDirFile.listFiles().find {
                it.isFile && it.name.startsWith(entry.videoDirName)
                        && it.name.endsWith(".mp4")
            }
            if (videoFile == null) {
                toast("找不到视频文件夹：${entry.videoDirName}")
                return false
            }
            // 直接复制mp4文件
            appState.putTaskStatus(
                TaskStatus.Copying(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                copyFile(videoFile, outFile, deleteSource)
            }
            return true
        }
//        val videoIndexJsonFile = File(videoDirPath, "index.json")
//        if (!videoIndexJsonFile.exists()) {
//            toast("缓存文件丢失，请重新缓存")
//            return false
//        }
//        val videoIndexJson = videoIndexJsonFile.readText()
        if (entry.media_type == 1) {
            // 多段blv(flv)视频
            val blvFiles = mutableListOf<File>()
            var blvIndex = 0
            while (true) {
                val file = File(videoDir, "/${blvIndex}.blv")
                if (file.exists()) {
                    blvFiles.add(file)
                    blvIndex++
                } else {
                    break
                }
            }
            if (blvFiles.isEmpty()) {
                toast("找不到视频文件：0.blv")
                return false
            }
            appState.putTaskStatus(
                TaskStatus.InProgress(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f
                )
            )
            mergerVideos(
                blvFiles,
                outFile,
                deleteSource,
            )
            return true
        } else {
            val videoFile = File(videoDir, "video.m4s")
            val audioFile = File(videoDir, "audio.m4s")
            if (!videoFile.exists()) {
                toast("找不到视频文件")
                return false
            }
            if (!audioFile.exists()) {
                appState.putTaskStatus(
                    TaskStatus.Copying(
                        name = entry.name,
                        entryDirPath = entryDirPath,
                        cover = entry.cover,
                        progress = 0f,
                    )
                )
                launch {
                    copyFile(videoFile, outFile, deleteSource)
                }
                return true
            }
            appState.putTaskStatus(
                TaskStatus.InProgress(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            mergerVideoAndAudio(videoFile, audioFile, outFile, deleteSource)
            return true
        }
    }

    /**
     * 复制并导出
     */
    suspend fun copyAndExportBiliVideo(
        entryDirPath: String,
        outFile: File,
        deleteSource: Boolean = false,
    ): Boolean {
        val entryDirFile = DocumentFile.fromTreeUri(this, Uri.parse(entryDirPath))!!
        val entryJsonFile = MiaoDocumentFile(this, entryDirFile, "/entry.json")
        val entry = BiliEntryJsonParser.parseOrNull(entryJsonFile.readText())
            ?: run {
                toast("entry.json 文件为空或已损坏")
                return false
            }
        val videoDir = MiaoDocumentFile(this, entryDirFile, "/${entry.videoDirName}")

        if (!videoDir.exists() || !videoDir.isDirectory) {
            val videoFile = entryDirFile.listFiles().find {
                it.isFile && it.name?.startsWith(entry.videoDirName) == true
                        && it.name?.endsWith(".mp4") == true
            }
            if (videoFile == null) {
                toast("找不到视频文件夹：${entry.videoDirName}")
                return false
            }
            // 直接复制mp4文件
            appState.putTaskStatus(
                TaskStatus.Copying(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                copyFile(MiaoDocumentFile(this@BiliDownService, videoFile), outFile, deleteSource)
            }
            return true
        }
//        val videoIndexJsonFile = File(videoDirPath, "index.json")
//        if (!videoIndexJsonFile.exists()) {
//            toast("缓存文件丢失，请重新缓存")
//            return false
//        }
//        val videoIndexJson = videoIndexJsonFile.readText()
        if (entry.media_type == 1) {
            // 多段blv(flv)视频
            val blvFiles = mutableListOf<MiaoDocumentFile>()
            var blvIndex = 0
            while (true) {
                val file = MiaoDocumentFile(this, videoDir.documentFile, "/${blvIndex}.blv")
                if (file.exists()) {
                    blvFiles.add(file)
                    blvIndex++
                } else {
                    break
                }
            }
            if (blvFiles.isEmpty()) {
                toast("找不到视频文件：0.blv")
                return false
            }
            appState.putTaskStatus(
                TaskStatus.CopyingToTemp(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
                )
            )
            launch {
                try {
                    val tempFiles = blvFiles.map {
                        val tempF = File(getTempPath(), it.name)
                        it.copyToTemp(tempF)
                        tempF
                    }
                    appState.putTaskStatus(
                        TaskStatus.InProgress(
                            name = entry.name,
                            entryDirPath = entryDirPath,
                            cover = entry.cover,
                            progress = 0f
                        )
                    )
                    mergerVideos(
                        tempFiles,
                        outFile,
                        deleteSource,
                    )
                } catch (e: Exception) {
                    appState.putTaskStatus(
                        TaskStatus.Error(
                            appState.taskStatus.value,
                            e.message ?: e.toString(),
                        )
                    )
                    e.printStackTrace()
                }
            }
            return true
        } else {
            // 音视频分离视频
            val videoFile = MiaoDocumentFile(this, videoDir.documentFile, "/video.m4s")
            val audioFile = MiaoDocumentFile(this, videoDir.documentFile, "/audio.m4s")
            if (!videoFile.exists()) {
                toast("找不到视频文件：video.m4s")
                return false
            }
            if (!audioFile.exists()) {
                // 直接复制
                appState.putTaskStatus(
                    TaskStatus.Copying(
                        name = entry.name,
                        entryDirPath = entryDirPath,
                        cover = entry.cover,
                        progress = 0f,
                    )
                )
                launch {
                    copyFile(videoFile, outFile, deleteSource)
                }
                return false
            }
            appState.putTaskStatus(
                TaskStatus.CopyingToTemp(
                    name = entry.name,
                    entryDirPath = entryDirPath,
                    cover = entry.cover,
                    progress = 0f,
    //                audioFile = audioFile,
    //                videoFile = videoFile,
                )
            )
            launch {
                try {
                    val tempVideoFile = File(getTempPath(), "video.m4s")
                    val tempAudioFile = File(getTempPath(), "audio.m4s")
                    videoFile.copyToTemp(tempVideoFile)
                    audioFile.copyToTemp(tempAudioFile)
                    appState.putTaskStatus(
                        TaskStatus.InProgress(
                            name = entry.name,
                            entryDirPath = entryDirPath,
                            cover = entry.cover,
                            progress = 0f
                        )
                    )
                    mergerVideoAndAudio(
                        tempVideoFile,
                        tempAudioFile,
                        outFile,
                        deleteSource,
                    )
                } catch (e: Exception) {
                    appState.putTaskStatus(
                        TaskStatus.Error(
                            appState.taskStatus.value,
                            e.message ?: e.toString(),
                        )
                    )
                    e.printStackTrace()
                }
            }
            return true
        }
    }

    private suspend fun copyFile(
        inputFile: File,
        outFile: File,
        deleteSource: Boolean = false,
    ) {

        val fileInputStream = FileInputStream(inputFile)
        val fileOutputStream = FileOutputStream(outFile)
        val buffer = ByteArray(1024)
        var byteRead: Int
        while (-1 != fileInputStream.read(buffer).also { byteRead = it }) {
            fileOutputStream.write(buffer, 0, byteRead)
        }
        fileInputStream.close()
        fileOutputStream.flush()
        fileOutputStream.close()

        val currentStatus = appState.taskStatus.value
        putOutRecord(
            currentStatus.entryDirPath,
            outFile.path,
            outFile.name,
            currentStatus.cover,
            status = OutRecord.STATUS_SUCCESS,
            deleteSource = deleteSource,
        )
        appState.putTaskStatus(TaskStatus.InIdle)
    }

    private suspend fun copyFile(
        inputFile: MiaoDocumentFile,
        outFile: File,
        deleteSource: Boolean = false,
    ) {
        inputFile.copyToTemp(outFile)
        val currentStatus = appState.taskStatus.value
        putOutRecord(
            currentStatus.entryDirPath,
            outFile.path,
            outFile.name,
            currentStatus.cover,
            status = OutRecord.STATUS_SUCCESS,
            deleteSource = deleteSource,
        )
        appState.putTaskStatus(TaskStatus.InIdle)
    }

    private fun mergerVideoAndAudio(
        videoFile: File,
        audioFile: File,
        outFile: File,
        deleteSource: Boolean = false,
    ) {
        if (!outFile.parentFile!!.exists()) {
            outFile.parentFile!!.mkdir()
        }
        val commands = RxFFmpegCommandList().apply {
            append("-i")
            append(videoFile.absolutePath)
            append("-i")
            append(audioFile.absolutePath)
            append("-c:v")
            append("copy")
            append("-strict")
            append("experimental")
            append(outFile.absolutePath)
        }.build()
        //开始执行FFmpeg命令
        val myRxFFmpegSubscriber = object : MyRxFFmpegSubscriber(
            appState,
//            getTempPath()
        ) {
            override fun onFinish() {
                val currentStatus = appState.taskStatus.value
                launch {
                    putOutRecord(
                        currentStatus.entryDirPath,
                        outFile.path,
                        outFile.name,
                        currentStatus.cover,
                        status = OutRecord.STATUS_SUCCESS,
                        deleteSource = deleteSource,
                    )
                }
                val tempPath = getTempPath()
                File(tempPath).deleteRecursively()
                super.onFinish()
            }
        }
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(myRxFFmpegSubscriber)
    }

    private fun mergerVideos(
        videoFiles: List<File>,
        outFile: File,
        deleteSource: Boolean = false,
    ) {
        if (!outFile.parentFile!!.exists()) {
            outFile.parentFile!!.mkdir()
        }
        val ffTxtFile = File(getTempPath(), ".ff.txt")
        if (ffTxtFile.exists() && ffTxtFile.isDirectory) {
            ffTxtFile.deleteRecursively()
            ffTxtFile.delete()
        }
        val ffTxtContent = videoFiles.map {
                file -> "file ${CommandUtil.filePath(file)}"
        }.joinToString("\n")
        ffTxtFile.writeText(ffTxtContent)
        val commands = RxFFmpegCommandList().apply {
            append("-f")
            append("concat")
            append("-safe")
            append("0")
            append("-i")
            append(ffTxtFile.absolutePath)
            append("-c")
            append("copy")
            append(outFile.absolutePath)
        }.build()
        //开始执行FFmpeg命令
        val myRxFFmpegSubscriber = object : MyRxFFmpegSubscriber(appState) {
            override fun onFinish() {
                val currentStatus = appState.taskStatus.value
                launch {
                    putOutRecord(
                        currentStatus.entryDirPath,
                        outFile.path,
                        outFile.name,
                        currentStatus.cover,
                        status = OutRecord.STATUS_SUCCESS,
                        deleteSource = deleteSource,
                    )
                }
                val tempPath = getTempPath()
                File(tempPath).deleteRecursively()
                super.onFinish()
            }
        }
        RxFFmpegInvoke.getInstance()
            .runCommandRxJava(commands)
            .subscribe(myRxFFmpegSubscriber)
    }

    private suspend fun putOutRecord(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        status: Int,
        message: String? = null,
        deleteSource: Boolean = false,
    ) {
        val outRecordDao = appDatabase.outRecordDao()
        val record = outRecordDao.findByPath(entryDirPath)
        val currentTime = System.currentTimeMillis()
        val shouldDeleteSource = deleteSource || record?.deleteSource == true
        if (record == null) {
            val newRecord = OutRecord(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = status,
                type = 1,
                deleteSource = shouldDeleteSource,
                createTime = currentTime,
                updateTime = currentTime,
                message = message,
            )
            outRecordDao.insertAll(newRecord)
        } else {
            val newRecord = record.copy(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = status,
                deleteSource = shouldDeleteSource,
                message = message,
                updateTime = currentTime,
            )
            outRecordDao.update(newRecord)
        }
        if (status == OutRecord.STATUS_SUCCESS && shouldDeleteSource) {
            deleteSourceDir(entryDirPath)
        }
    }

    /**
     * 删除哔哩哔哩缓存源目录（三通道：Shizuku / SAF content / Java File）。
     * @return null=删除成功；[SOURCE_NOT_FOUND]=源已不存在；其他字符串=失败原因
     */
    suspend fun deleteSourceVideo(entryDirPath: String): String? {
        return try {
            if (appState.shizukuState.value.isEnabled) {
                when (val error = RemoteServiceUtil.getUserService().deleteBiliVideo(entryDirPath)) {
                    null -> null
                    "源文件不存在" -> SOURCE_NOT_FOUND
                    else -> error
                }
            } else if (entryDirPath.startsWith("content:")) {
                val uri = Uri.parse(entryDirPath)
                if (DocumentsContract.deleteDocument(contentResolver, uri)) {
                    null
                } else {
                    "删除失败：$entryDirPath"
                }
            } else {
                val dir = File(entryDirPath)
                when {
                    !dir.exists() -> SOURCE_NOT_FOUND
                    dir.deleteRecursively() -> null
                    else -> "删除失败：$entryDirPath"
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            "删除失败：${e.message}"
        }
    }

    /** 检查哔哩哔哩缓存源目录是否仍存在（三通道）。检查异常时保守返回 true，交由删除动作给出准确反馈 */
    suspend fun sourceVideoExists(entryDirPath: String): Boolean {
        return try {
            when {
                appState.shizukuState.value.isEnabled ->
                    RemoteServiceUtil.getUserService().fileExists(entryDirPath)
                entryDirPath.startsWith("content:") ->
                    DocumentFile.fromSingleUri(this, Uri.parse(entryDirPath))?.exists() == true
                else -> File(entryDirPath).exists()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            true
        }
    }

    private suspend fun deleteSourceDir(entryDirPath: String) {
        when (val error = deleteSourceVideo(entryDirPath)) {
            null -> MiaoLog.debug { "已删除源文件：$entryDirPath" }
            SOURCE_NOT_FOUND -> Unit // 源已不在，静默处理
            else -> toast("删除源文件失败：$error")
        }
    }

    fun tryAddOutRecord(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        deleteSource: Boolean = false,
    ) {
        launch {
            putOutRecord(
                entryDirPath, outFilePath, title, cover,
                status = OutRecord.STATUS_SUCCESS,
                deleteSource = deleteSource,
            )
        }
    }

    suspend fun getRecordList(): List<OutRecord> {
        return appDatabase.outRecordDao().getAll()
    }

    suspend fun getRecordList(status: Int): List<OutRecord> {
        return appDatabase.outRecordDao().getAllByStatus(status)
    }

    /**
     * 扫描导出目录 Download/BiliDownOut/，将文件存在但数据库无记录的 .mp4 补回记录。
     * 补回记录的 entryDirPath 为空（源未知），不可参与"删除原视频"操作。
     * 用于卸载重装/清缓存后恢复已导出文件的管理入口。
     */
    suspend fun reconcileExportedFiles(): Int {
        val outFolder = File(BiliDownOutFile.getOutFolderPath())
        if (!outFolder.exists()) return 0
        // 已有记录的所有导出文件路径集合（小写比较，兼容路径大小写差异）
        val existingPaths = appDatabase.outRecordDao().getAll()
            .map { it.outFilePath.lowercase() }
            .toSet()
        var inserted = 0
        val currentTime = System.currentTimeMillis()
        // 遍历 UP 主子目录 + 根目录下的 .mp4 文件
        val authorDirs = outFolder.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
        for (authorDir in authorDirs) {
            val mp4Files = authorDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
                ?: continue
            for (mp4File in mp4Files) {
                if (mp4File.path.lowercase() !in existingPaths) {
                    val record = OutRecord(
                        entryDirPath = "", // 源未知：无法反查 B 站缓存路径
                        outFilePath = mp4File.path,
                        title = mp4File.nameWithoutExtension,
                        cover = "",
                        status = OutRecord.STATUS_SUCCESS,
                        type = 1,
                        deleteSource = false,
                        createTime = currentTime,
                        updateTime = currentTime,
                    )
                    appDatabase.outRecordDao().insertAll(record)
                    inserted++
                }
            }
        }
        // 也检查根目录下（无 UP 主子目录）的 mp4
        val rootMp4Files: Array<File> = outFolder.listFiles { f -> f.isFile && f.name.endsWith(".mp4") }
            ?: emptyArray()
        for (mp4File in rootMp4Files) {
            if (mp4File.path.lowercase() !in existingPaths) {
                val record = OutRecord(
                    entryDirPath = "",
                    outFilePath = mp4File.path,
                    title = mp4File.nameWithoutExtension,
                    cover = "",
                    status = OutRecord.STATUS_SUCCESS,
                    type = 1,
                    deleteSource = false,
                    createTime = currentTime,
                    updateTime = currentTime,
                )
                appDatabase.outRecordDao().insertAll(record)
                inserted++
            }
        }
        return inserted
    }

    suspend fun getRecordList(paths: Array<String>): List<OutRecord> {
        return appDatabase.outRecordDao().getAllByEntryDirPaths(paths)
    }

    suspend fun startTask(
        task: OutRecord,
    ) {
        val isSuccess = exportBiliVideo(
            task.entryDirPath,
            File(task.outFilePath),
            task.deleteSource,
        )
        if (isSuccess) {
            putOutRecord(
                task.entryDirPath,
                task.outFilePath,
                task.title,
                task.cover,
                status = OutRecord.STATUS_IN_PROGRESS
            )
        }
    }

    suspend fun addTask(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        deleteSource: Boolean = false,
    ) {
        when (tryAddTask(entryDirPath, outFilePath, title, cover, deleteSource)) {
            1 -> toast("成功创建任务：$title")
            2 -> toast("该视频已导出：$title")
            3 -> toast("该视频已在队列中：$title")
        }
    }

    /** @return 1=已添加 2=已导出 3=已在队列 */
    suspend fun tryAddTask(
        entryDirPath: String,
        outFilePath: String,
        title: String,
        cover: String,
        deleteSource: Boolean,
    ): Int {
        val outRecordDao = appDatabase.outRecordDao()
        val record = outRecordDao.findByPath(entryDirPath)
        val currentTime = System.currentTimeMillis()
        if (record == null) {
            val newRecord = OutRecord(
                entryDirPath = entryDirPath,
                outFilePath = outFilePath,
                title = title,
                cover = cover,
                status = OutRecord.STATUS_WAIT,
                type = 1,
                deleteSource = deleteSource,
                createTime = currentTime,
                updateTime = currentTime,
            )
            outRecordDao.insertAll(newRecord)
            // 当前空闲时主动触发队列（taskStatus 未变化时 StateFlow 不会重新发射）
            kickQueue()
            return 1
        }
        return if (record.status == OutRecord.STATUS_SUCCESS) 2 else 3
    }

    private val queueMutex = Mutex()

    /**
     * 若当前无任务进行中，启动队列中最早的一条等待任务。
     * 入队后立即调用，避免依赖 taskStatus 变化触发。
     */
    private fun kickQueue() {
        launch {
            queueMutex.withLock {
                val status = appState.taskStatus.value
                if (status is TaskStatus.InIdle || status is TaskStatus.Error) {
                    val waitRecords = appDatabase.outRecordDao()
                        .getAllByStatus(OutRecord.STATUS_WAIT)
                    if (waitRecords.isNotEmpty()) {
                        startTask(waitRecords.first())
                    }
                }
            }
        }
    }

    suspend fun delTask(
        task: OutRecord,
        isDeleteFile: Boolean,
    ) {
        if (isDeleteFile) {
            val outFile = File(task.outFilePath)
            if (outFile.exists()) {
                outFile.delete()
            }
            appDatabase.outRecordDao().delete(task)
            withContext(Dispatchers.Main) {
                toast("已删除记录及文件${task.title}")
            }
        } else {
            appDatabase.outRecordDao().delete(task)
            withContext(Dispatchers.Main) {
                toast("已删除记录${task.title}")
            }
        }
    }

    private suspend fun toast(message: String) {
        val duration = if (message.length > 10) {
            Toast.LENGTH_LONG
        } else {
            Toast.LENGTH_SHORT
        }
        withContext(Dispatchers.Main) {
            Toast.makeText(this@BiliDownService, message, duration)
                .show()
        }
    }

    private fun getTempPath(): String {
        var file = File(getExternalFilesDir(null), "../temp")
        if (!file.exists()) {
            file.mkdir()
        }
        return file.canonicalPath
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

}