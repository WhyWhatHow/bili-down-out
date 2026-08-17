package cn.a10miaomiao.bilidown.ui.page

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.BiliDownFile
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.rememberDataStorePreferencesFlow
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.entity.BANGUMI_GROUP_LABEL
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadItemInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.entity.buildDownloadInfoList
import cn.a10miaomiao.bilidown.entity.formatFileSize
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.shizuku.localShizukuPermission
import cn.a10miaomiao.bilidown.ui.BiliDownScreen
import cn.a10miaomiao.bilidown.ui.components.DownloadListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

data class AuthorPageState(
    val list: List<DownloadInfo>,
    val face: String?,
    val loading: Boolean,
    /** 是否已完成一次成功加载（含结果为空）。防止空结果 + Shizuku 状态变化触发无限重扫 */
    val loaded: Boolean,
    val failMessage: String,
)

sealed class AuthorPageAction {
    class GetList(
        val packageName: String,
        val enabledShizuku: Boolean,
    ) : AuthorPageAction()

    class ExportBatch(
        val items: List<DownloadItemInfo>,
        val deleteSource: Boolean,
    ) : AuthorPageAction()
}

/** 与列表页分组逻辑保持一致：author 为空时番剧归入"番剧·影视"组 */
private fun DownloadInfo.authorGroupKey(): String {
    return when {
        author.isNotBlank() -> author
        type == DownloadType.BANGUMI -> BANGUMI_GROUP_LABEL
        else -> ""
    }
}

@Composable
fun AuthorPagePresenter(
    context: Context,
    author: String,
    action: Flow<AuthorPageAction>,
): AuthorPageState {
    var list by remember { mutableStateOf(emptyList<DownloadInfo>()) }
    var face by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loaded by remember { mutableStateOf(false) }
    var failMessage by remember { mutableStateOf("") }

    suspend fun getList(
        packageName: String,
        enabledShizuku: Boolean,
    ) {
        try {
            val appState = (context.applicationContext as BiliDownApp).state
            // 缓存优先：列表页已加载过则直接复用，秒开且零磁盘 IO。
            // 全量遍历 /Android/data 在 SAF 下每个文件操作都是一次 IPC，非常慢，
            // 之前每次进入本页都重复执行导致明显卡顿。
            val cached = appState.downloadListCache[packageName]
            if (cached != null) {
                list = cached.filter { it.authorGroupKey() == author }
                face = list.firstNotNullOfOrNull { it.authorFace }
                loaded = true
                return
            }
            // 兜底：进程被杀恢复等场景缓存缺失，才走磁盘读取
            MiaoLog.debug { "AuthorPage.getList(author:$author) cache miss" }
            val biliDownFile = BiliDownFile(context, packageName, enabledShizuku)
            if (!biliDownFile.canRead()) {
                failMessage = "无权限读取哔哩哔哩下载目录"
                return
            }
            loading = true
            failMessage = ""
            val entryList = biliDownFile.readDownloadList()
            val allList = buildDownloadInfoList(entryList)
            // 兜底路径只查本地磁盘缓存补全 UP 主（毫秒级），
            // 不等网络：已知缓存的立即显示，查不到的归"未知UP主/番剧·影视"组
            fillMissingAuthors(entryList, allList, fetchRemote = false) { }
            // 写回内存缓存：下次进入本页或列表页恢复时直接命中，不再重扫
            appState.downloadListCache[packageName] = allList.toList()
            fun filterByAuthor() = allList.filter { it.authorGroupKey() == author }
            list = filterByAuthor()
            face = list.firstNotNullOfOrNull { it.authorFace }
            loaded = true
        } catch (e: TimeoutCancellationException) {
            e.printStackTrace()
            failMessage = if (enabledShizuku) {
                "连接Shizuku服务超时，建议您尝试停止并重新激活Shizuku！"
            } else {
                "读取缓存列表超时！"
            }
        } catch (e: Exception) {
            e.printStackTrace()
            failMessage = "读取列表异常：" + (e.message ?: e.toString())
        } finally {
            loading = false
        }
    }

    action.collectAction {
        when (it) {
            is AuthorPageAction.GetList -> {
                withContext(Dispatchers.IO) {
                    getList(it.packageName, it.enabledShizuku)
                }
            }

            is AuthorPageAction.ExportBatch -> {
                val biliDownService = BiliDownService.getService(context)
                var addedCount = 0
                var skipCount = 0
                withContext(Dispatchers.IO) {
                    it.items.forEach { item ->
                        val baseName = BiliDownOutFile.sanitizeFileName(
                            if (item.index_title.isBlank()) {
                                item.title
                            } else {
                                "${item.title}_${item.index_title}"
                            }
                        ).ifBlank { "未命名" }
                        var index = 1
                        var outFile: BiliDownOutFile
                        do {
                            val name = if (index == 1) {
                                baseName
                            } else {
                                "${baseName}_$index"
                            }
                            outFile = BiliDownOutFile(name + ".mp4", item.author)
                            index++
                        } while (outFile.exists())
                        val result = biliDownService.tryAddTask(
                            item.dir_path,
                            outFile.path,
                            outFile.name,
                            item.cover,
                            it.deleteSource,
                        )
                        if (result == 1) {
                            addedCount++
                        } else {
                            skipCount++
                        }
                    }
                }
                val message = if (skipCount > 0) {
                    "已添加 $addedCount 个导出任务，跳过 $skipCount 个（已导出或已在队列）"
                } else {
                    "已添加 $addedCount 个导出任务"
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG)
                        .show()
                }
            }
        }
    }
    return AuthorPageState(
        list = list,
        face = face,
        loading = loading,
        loaded = loaded,
        failMessage = failMessage,
    )
}

/**
 * UP 主视频列表页：从列表页分组头点击 UP 主名称进入，
 * 展示该 UP 主的全部缓存视频，支持多选批量导出。
 */
@Composable
fun AuthorPage(
    navController: NavHostController,
    packageName: String,
    author: String,
) {
    val context = LocalContext.current
    val shizukuPermission = localShizukuPermission()
    val shizukuPermissionState by shizukuPermission.collectState()
    val (state, channel) = rememberPresenter(listOf(packageName, author)) {
        AuthorPagePresenter(context, author, it)
    }

    var selectMode by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateOf(setOf<String>()) }
    val deleteSourceEnabled by rememberDataStorePreferencesFlow(
        context = context,
        key = DataStoreKeys.exportDeleteSource,
        initial = false,
    ).collectAsState(false)

    val selectedVideos = state.list.filter { selectedKeys.value.contains(it.dir_path) }
    val selectedItems = selectedVideos.flatMap { it.items }
    val totalSize = state.list.sumOf { it.items.sumOf { item -> item.total_bytes } }

    LaunchedEffect(packageName, author, shizukuPermissionState.isEnabled) {
        // loaded 标记：空结果也算加载完成，避免 Shizuku 状态变化触发无限重扫
        if (!state.loaded && state.list.isEmpty() && state.failMessage.isBlank()) {
            channel.trySend(
                AuthorPageAction.GetList(
                    packageName = packageName,
                    enabledShizuku = shizukuPermissionState.isEnabled,
                )
            )
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (state.list.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (state.loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(40.dp),
                        strokeWidth = 4.dp,
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "正在读取列表",
                        color = MaterialTheme.colorScheme.outline,
                    )
                } else if (state.failMessage.isNotBlank()) {
                    Text(
                        text = state.failMessage,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(20.dp)
                    )
                } else {
                    Text(
                        "该UP主下暂无缓存视频",
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                // UP 主信息头：头像 + 名称 + 视频数/总大小
                item(key = "author_header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AuthorAvatar(
                            face = state.face,
                            name = author,
                            size = 56.dp,
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = author.ifBlank { "未知UP主" },
                                style = MaterialTheme.typography.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${state.list.size}个视频 · ${formatFileSize(totalSize)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.outline,
                                maxLines = 1,
                            )
                        }
                    }
                }
                items(state.list, { it.dir_path }) { item ->
                    DownloadListItem(
                        item = item,
                        selectMode = selectMode,
                        selected = selectedKeys.value.contains(item.dir_path),
                        onClick = {
                            if (selectMode) {
                                selectedKeys.value =
                                    if (selectedKeys.value.contains(item.dir_path)) {
                                        selectedKeys.value - item.dir_path
                                    } else {
                                        selectedKeys.value + item.dir_path
                                    }
                            } else {
                                val dirPath = Uri.encode(item.dir_path)
                                navController.navigate(
                                    BiliDownScreen.Detail.route + "?packageName=${packageName}&dirPath=${dirPath}"
                                )
                            }
                        },
                        onLongClick = {
                            selectMode = true
                            selectedKeys.value = selectedKeys.value + item.dir_path
                        },
                    )
                }
            }
        }

        AnimatedVisibility(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp),
            visible = selectMode,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
        ) {
            Surface(
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "已选 ${selectedVideos.size} 个视频",
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            selectedKeys.value =
                                if (selectedVideos.size == state.list.size) {
                                    emptySet()
                                } else {
                                    state.list.map { it.dir_path }.toSet()
                                }
                        },
                    ) {
                        Text("全选")
                    }
                    TextButton(
                        enabled = selectedItems.isNotEmpty(),
                        onClick = {
                            // 直接按全局设置导出，不再弹确认框（删除源文件仅在导出成功后执行）
                            channel.trySend(
                                AuthorPageAction.ExportBatch(selectedItems, deleteSourceEnabled)
                            )
                            selectMode = false
                            selectedKeys.value = emptySet()
                        },
                    ) {
                        Text("导出选中")
                    }
                    TextButton(
                        onClick = {
                            selectMode = false
                            selectedKeys.value = emptySet()
                        },
                    ) {
                        Text("退出")
                    }
                }
            }
        }
    }
}
