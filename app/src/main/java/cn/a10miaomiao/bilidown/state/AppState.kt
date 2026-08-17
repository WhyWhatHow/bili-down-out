package cn.a10miaomiao.bilidown.state

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.shizuku.permission.ShizukuPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppState {

    private val _taskStatus = MutableStateFlow<TaskStatus>(TaskStatus.InIdle)
    val taskStatus: StateFlow<TaskStatus> = _taskStatus

    private val _shizukuState = MutableStateFlow(
        ShizukuPermission.ShizukuPermissionState()
    )
    val shizukuState: StateFlow<ShizukuPermission.ShizukuPermissionState> = _shizukuState

    /**
     * 各 B 站包名已加载的缓存列表（进程内共享）。
     * 列表页加载/刷新时写入；UP 主页直接读缓存秒开，
     * 避免每次进入都全量遍历 /Android/data 目录（SAF 下每个文件操作都是一次 IPC，非常慢）。
     */
    val downloadListCache = mutableStateMapOf<String, List<DownloadInfo>>()

    fun init(context: Context) {

    }

    fun putTaskStatus(taskStatus: TaskStatus) {
        _taskStatus.value = taskStatus
    }

    fun putShizukuState(state: ShizukuPermission.ShizukuPermissionState) {
        _shizukuState.value = state
    }

}