package cn.a10miaomiao.bilidown.state

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.shizuku.permission.ShizukuPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

class AppState {

    private val _taskStatus = MutableStateFlow<TaskStatus>(TaskStatus.InIdle)
    val taskStatus: StateFlow<TaskStatus> = _taskStatus

    private val _shizukuState = MutableStateFlow(
        ShizukuPermission.ShizukuPermissionState()
    )
    val shizukuState: StateFlow<ShizukuPermission.ShizukuPermissionState> = _shizukuState

    private val json = Json { ignoreUnknownKeys = true }

    private var listCacheFile: File? = null

    /**
     * 各 B 站包名已加载的缓存列表（进程内共享）。
     * 列表页加载/刷新时写入并持久化到磁盘；UP 主页直接读缓存秒开，
     * 避免每次进入都全量遍历 /Android/data 目录（SAF 下每个文件操作都是一次 IPC，非常慢）。
     * 持久化让进程被杀后恢复时（UP 页先于列表页组合）仍能立即命中，不落回全量扫描。
     */
    val downloadListCache = mutableStateMapOf<String, List<DownloadInfo>>()

    fun init(context: Context) {
        listCacheFile = File(context.filesDir, LIST_CACHE_FILE)
        loadListCache()
    }

    private fun loadListCache() {
        val file = listCacheFile ?: return
        try {
            if (file.exists()) {
                val serializer = MapSerializer(String.serializer(), ListSerializer(DownloadInfo.serializer()))
                val map = json.decodeFromString(serializer, file.readText())
                downloadListCache.clear()
                downloadListCache.putAll(map)
            }
        } catch (e: Exception) {
            // 缓存损坏/格式演进时静默丢弃，下次写入重建
        }
    }

    /** 持久化列表缓存（调用方须在 IO 线程） */
    fun saveListCache() {
        val file = listCacheFile ?: return
        try {
            val serializer = MapSerializer(String.serializer(), ListSerializer(DownloadInfo.serializer()))
            val data = downloadListCache.toMap()
            // 原子写：先写 .tmp 再 rename，防止中途被杀丢整份缓存
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(serializer, data))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 写入失败不影响主流程
        }
    }

    fun putTaskStatus(taskStatus: TaskStatus) {
        _taskStatus.value = taskStatus
    }

    fun putShizukuState(state: ShizukuPermission.ShizukuPermissionState) {
        _shizukuState.value = state
    }

    companion object {
        private const val LIST_CACHE_FILE = "bili_list_cache.json"
    }

}