package cn.a10miaomiao.bilidown.ui.page

import android.Manifest
import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.R
import cn.a10miaomiao.bilidown.common.BiliAuthorRepository
import cn.a10miaomiao.bilidown.common.BiliDownFile
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.BiliDownUtils
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.UrlUtil
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.rememberDataStorePreferencesFlow
import cn.a10miaomiao.bilidown.common.lifecycle.LaunchedLifecycleObserver
import cn.a10miaomiao.bilidown.common.localStoragePermission
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadItemInfo
import cn.a10miaomiao.bilidown.entity.DownloadSortMode
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.entity.applySort
import cn.a10miaomiao.bilidown.entity.formatFileSize
import cn.a10miaomiao.bilidown.entity.groupByAuthor
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.shizuku.localShizukuPermission
import coil.compose.AsyncImage
import cn.a10miaomiao.bilidown.ui.BiliDownScreen
import cn.a10miaomiao.bilidown.ui.components.BatchExportDialog
import cn.a10miaomiao.bilidown.ui.components.DownloadListItem
import cn.a10miaomiao.bilidown.ui.components.PermissionDialog
import cn.a10miaomiao.bilidown.ui.components.SwipeToRefresh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.ShizukuProvider
import java.util.concurrent.ConcurrentHashMap


/**
 * 对 entry.json 缺失 owner 的条目并发查询 B 站 API 补全 UP 主名称与头像。
 *
 * key 兜底顺序：bvid -> ep.bvid -> source.av_id -> avid，
 * 覆盖普通视频与番剧（番剧 entry 常缺顶层 bvid/avid）。
 * 返回 key 为 bvid 或 "av{avid}"；总超时 20 秒，超时返回已查到的部分。
 */
private suspend fun fetchAuthors(
    entries: List<BiliDownloadEntryInfo>,
): Map<String, BiliAuthorRepository.Author> {
    val needFetch = entries.filter { it.author.isBlank() }
        .distinctBy { it.keyForAuthorFetch() }
    if (needFetch.isEmpty()) {
        return emptyMap()
    }
    return withTimeoutOrNull(20_000L) {
        val fetched = ConcurrentHashMap<String, BiliAuthorRepository.Author>()
        coroutineScope {
            // 并发 4 路，降低触发 B 站风控(-412)的概率
            val semaphore = Semaphore(4)
            needFetch.forEach { entry ->
                launch {
                    semaphore.withPermit {
                        // 依次尝试各 key 来源，任一成功即停止
                        entry.authorKeyCandidates().forEach { (avid, bvid) ->
                            val author = BiliAuthorRepository.getAuthor(
                                avid = avid,
                                bvid = bvid,
                            )
                            if (author != null) {
                                fetched[entry.keyForAuthorFetch()] = author
                                return@withPermit
                            }
                        }
                    }
                }
            }
        }
        fetched.toMap()
    } ?: emptyMap()
}

/** 分组去重用的稳定 key */
private fun BiliDownloadEntryInfo.keyForAuthorFetch(): String {
    return bvid ?: ep?.bvid?.takeIf { it.isNotBlank() }
    ?: source?.av_id?.let { "av$it" }
    ?: avid?.let { "av$it" }
    ?: "unknown"
}

/** 查询 UP 主时的 (avid, bvid) 候选列表，按优先级排列 */
private fun BiliDownloadEntryInfo.authorKeyCandidates(): List<Pair<Long?, String?>> {
    val list = mutableListOf<Pair<Long?, String?>>()
    if (!bvid.isNullOrBlank()) list.add(null to bvid)
    if (!ep?.bvid.isNullOrBlank()) list.add(null to ep!!.bvid)
    if (source?.av_id != null && source.av_id > 0) list.add(source.av_id to null)
    if (avid != null && avid > 0) list.add(avid to null)
    return list
}

/**
 * 列表已渲染后，异步补全缺失的 UP 主名称与头像并触发重组。
 * 仓库内部有内存+磁盘缓存，重启后已识别的 UP 主可立即返回。
 */
private suspend fun fillMissingAuthors(
    entryList: List<BiliDownloadEntryAndPathInfo>,
    newList: MutableList<DownloadInfo>,
    onUpdated: () -> Unit,
) {
    val hasMissing = newList.any { it.author.isBlank() }
    if (!hasMissing) {
        return
    }
    val authorMap = fetchAuthors(entryList.map { it.entry })
    if (authorMap.isEmpty()) {
        return
    }
    val dirToAuthor = entryList.mapNotNull { info ->
        authorMap[info.entry.keyForAuthorFetch()]
            ?.let { info.entryDirPath to it }
    }.toMap()
    if (dirToAuthor.isEmpty()) {
        return
    }
    newList.forEach { downloadInfo ->
        // 组内任一分P命中即视为该视频的 UP 主
        val author = downloadInfo.items
            .firstNotNullOfOrNull { dirToAuthor[it.dir_path] }
            ?: return@forEach
        downloadInfo.author = author.name
        downloadInfo.authorFace = author.face
        downloadInfo.items.forEach { it.author = author.name }
    }
    onUpdated()
}

data class DownloadListPageState(
    val list: List<DownloadInfo>,
    val path: String,
    val canRead: Boolean,
    val loading: Boolean,
    val refreshing: Boolean,
    val failMessage: String,
)

sealed class DownloadListPageAction {
    class GetList(
        val packageName: String,
        val enabledShizuku: Boolean,
    ) : DownloadListPageAction()

    class RefreshList(
        val packageName: String,
        val enabledShizuku: Boolean,
    ) : DownloadListPageAction()

    class ExportBatch(
        val items: List<DownloadItemInfo>,
        val deleteSource: Boolean,
    ) : DownloadListPageAction()
}

@Composable
fun DownloadListPagePresenter(
    context: Context,
    action: Flow<DownloadListPageAction>,
): DownloadListPageState {
    var list by remember {
        mutableStateOf(emptyList<DownloadInfo>())
    }
    var path by remember {
        mutableStateOf("")
    }
    var canRead by remember {
        mutableStateOf(true)
    }
    var failMessage by remember {
        mutableStateOf("")
    }
    var loading by remember {
        mutableStateOf(true)
    }
    var refreshing by remember {
        mutableStateOf(false)
    }

    suspend fun getList(
        packageName: String,
        enabledShizuku: Boolean,
    ) {
        try {
            MiaoLog.debug { "getList(packageName:$packageName, enabledShizuku: $enabledShizuku)" }
            val biliDownFile = BiliDownFile(context, packageName, enabledShizuku)
            canRead = biliDownFile.canRead()
            if (!canRead) {
                return
            }
            loading = true
            failMessage = ""
            val entryList = biliDownFile.readDownloadList()
            val newList = mutableListOf<DownloadInfo>()
            entryList.forEach {
                val biliEntry = it.entry
                var indexTitle = ""
                var itemTitle = ""
                var id = biliEntry.avid ?: 0L
                var cid = 0L
                var epid = 0L
                var type = DownloadType.VIDEO
                val page = biliEntry.page_data
                if (page != null) {
                    id = biliEntry.avid!!
                    indexTitle = page.download_title ?: page.part ?: "${page.page}P"
                    cid = page.cid
                    type = DownloadType.VIDEO
                    itemTitle = biliEntry.title
                }
                val ep = biliEntry.ep
                val source = biliEntry.source
                if (ep != null && source != null) {
                    id = biliEntry.season_id!!.toLong()
                    indexTitle = ep.index_title
                    epid = ep.episode_id
                    cid = source.cid
                    type = DownloadType.BANGUMI
                    itemTitle = if (ep.index_title.isNotBlank()) {
                        ep.index_title
                    } else {
                        ep.index
                    }
                }
                val item = DownloadItemInfo(
                    dir_path = it.entryDirPath,
                    media_type = biliEntry.media_type,
                    has_dash_audio = biliEntry.has_dash_audio,
                    is_completed = biliEntry.is_completed,
                    total_bytes = biliEntry.total_bytes,
                    downloaded_bytes = biliEntry.downloaded_bytes,
                    title = itemTitle,
                    cover = biliEntry.cover,
                    id = id,
                    type = type,
                    cid = cid,
                    epid = epid,
                    index_title = indexTitle,
                    author = biliEntry.author,
                )
                val last = newList.lastOrNull()
                if (last != null
                    && last.type == item.type
                    && last.id == item.id
                ) {
                    if (last.is_completed && !item.is_completed) {
                        last.is_completed = false
                    }
                    last.items.add(item)
                } else {
                    newList.add(
                        DownloadInfo(
                            dir_path = it.pageDirPath,
                            media_type = biliEntry.media_type,
                            has_dash_audio = biliEntry.has_dash_audio,
                            is_completed = biliEntry.is_completed,
                            total_bytes = biliEntry.total_bytes,
                            downloaded_bytes = biliEntry.downloaded_bytes,
                            title = biliEntry.title,
                            cover = biliEntry.cover,
                            cid = cid,
                            id = id,
                            type = type,
                            author = item.author,
                            items = mutableListOf(item)
                        )
                    )
                }
            }
            list = newList.toList()
            // 先渲染列表，再异步补全缺失的 UP 主名称，避免网络请求阻塞列表展示
            fillMissingAuthors(entryList, newList) {
                list = newList.toList()
            }
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
            is DownloadListPageAction.RefreshList -> {
                refreshing = true
                withContext(Dispatchers.IO) {
                    getList(it.packageName, it.enabledShizuku)
                }
                refreshing = false
            }

            is DownloadListPageAction.GetList -> {
                withContext(Dispatchers.IO) {
                    getList(it.packageName, it.enabledShizuku)
                }
            }

            is DownloadListPageAction.ExportBatch -> {
                val biliDownService = BiliDownService.getService(context)
                var addedCount = 0
                var skipCount = 0
                // 文件存在性检查与入库均为 IO 操作，移到 IO 线程避免阻塞 UI
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
    return DownloadListPageState(
        list,
        path,
        canRead,
        loading,
        refreshing,
        failMessage,
    )
}

@Composable
fun DownloadListPage(
    navController: NavHostController,
    packageName: String,
) {
    val context = LocalContext.current
    var showPermissionDialog by remember { mutableStateOf(false) }
    val storagePermission = localStoragePermission()
    val permissionState = storagePermission.collectState()
    val shizukuPermission = localShizukuPermission()
    val shizukuPermissionState by shizukuPermission.collectState()

    val (state, channel) = rememberPresenter(listOf(packageName, permissionState)) {
        DownloadListPagePresenter(context, it)
    }

    var selectMode by remember { mutableStateOf(false) }
    val selectedKeys = remember { mutableStateOf(setOf<String>()) }
    var showBatchExportDialog by remember { mutableStateOf(false) }
    val deleteSourceEnabled by rememberDataStorePreferencesFlow(
        context = context,
        key = DataStoreKeys.exportDeleteSource,
        initial = false,
    ).collectAsState(false)

    // 排序方式与升降序
    var sortMode by remember { mutableStateOf(DownloadSortMode.DEFAULT) }
    var sortAsc by remember { mutableStateOf(true) }
    // 默认全部收起，点击 UP 主分组头展开
    val expandedGroups = remember { mutableStateOf(setOf<String>()) }

    val groups = remember(state.list, sortMode, sortAsc) {
        state.list.groupByAuthor().applySort(sortMode, sortAsc)
    }
    val selectedVideos = state.list.filter { selectedKeys.value.contains(it.dir_path) }
    val selectedItems = selectedVideos.flatMap { it.items }

    LaunchedEffect(
        packageName,
        permissionState.isGranted,
        permissionState.isExternalStorage,
        shizukuPermissionState.isRunning,
        shizukuPermissionState.isEnabled,
    ) {
        if (state.list.isEmpty()
            && permissionState.isGranted
            && permissionState.isExternalStorage
        ) {
            channel.send(
                DownloadListPageAction.GetList(
                    packageName = packageName,
                    shizukuPermissionState.isEnabled,
                )
            )
        }
    }

    LaunchedLifecycleObserver(
        onResume = {
            if (state.list.isEmpty()) {
                channel.trySend(
                    DownloadListPageAction.GetList(
                        packageName = packageName,
                        shizukuPermissionState.isEnabled,
                    )
                )
            }
        }
    )

    fun resultCallBack() {
        if (!permissionState.isGranted || !permissionState.isExternalStorage) {
            showPermissionDialog = true
        }
    }

    fun openShizuku() {
        try {
            val packageManager = context.packageManager
            val intent = packageManager.getLaunchIntentForPackage(ShizukuProvider.MANAGER_APPLICATION_ID)
            if (intent == null) {
                Toast.makeText(context, "未找到Shizuku", Toast.LENGTH_LONG)
                    .show()
            } else {
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Shizuku启动失败", Toast.LENGTH_LONG)
                .show()
            e.printStackTrace()
        }
    }

    PermissionDialog(
        showPermissionDialog = showPermissionDialog,
        isGranted = permissionState.isGranted,
        onDismiss = { showPermissionDialog = false }
    )

    if (showBatchExportDialog) {
        BatchExportDialog(
            videoCount = selectedVideos.size,
            partCount = selectedItems.size,
            deleteSourceEnabled = deleteSourceEnabled,
            onDismiss = { showBatchExportDialog = false },
            onConfirm = {
                showBatchExportDialog = false
                channel.trySend(
                    DownloadListPageAction.ExportBatch(selectedItems, deleteSourceEnabled)
                )
                selectMode = false
                selectedKeys.value = emptySet()
            },
        )
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
    SwipeToRefresh(
        refreshing = state.refreshing,
        onRefresh = {
            channel.trySend(
                DownloadListPageAction.RefreshList(
                    packageName = packageName,
                    enabledShizuku = shizukuPermissionState.isEnabled,
                )
            )
        },
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
                } else if (!permissionState.isGranted || !permissionState.isExternalStorage) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (permissionState.isGranted) {
                            Text(text = "请授予所有文件的存储权限")
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    storagePermission.requestPermissions(::resultCallBack)
                                }
                            ) {
                                Text(text = "授予所有文件的权限")
                            }
                        } else {
                            Text(text = "请授予存储权限")
                            Spacer(modifier = Modifier.height(20.dp))
                            Button(
                                onClick = {
                                    storagePermission.requestPermissions(::resultCallBack)
                                }
                            ) {
                                Text(text = "授予权限")
                            }
                        }
                    }
                } else if (!state.canRead) {
                    Text(text = "请授予文件夹权限")
                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = {
                            val biliDownFile = BiliDownFile(context, packageName, shizukuPermissionState.isEnabled)
                            biliDownFile.startFor(2)
                        }
                    ) {
                        Text(text = "授予权限")
                    }
                    TextButton(
                        onClick = {
                            navController.navigate(BiliDownScreen.More.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    ) {
                        Text(text = "或使用Shizuku")
                    }
                } else if (state.failMessage.isNotBlank()) {
                    Text(
                        text = state.failMessage,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(20.dp)
                    )
                    if ("Shizuku" in state.failMessage) {
                        TextButton(
                            onClick = ::openShizuku,
                        ) {
                            Text(text = "跳转Shizuku")
                        }
                    }
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.ic_movie_pay_area_limit),
                        contentDescription = "空空如也",
                        modifier = Modifier.size(200.dp, 200.dp)
                    )
                    Text(
                        modifier = Modifier.padding(vertical = 8.dp),
                        text = "空空如也",
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 80.dp),
            ) {
                // 紧凑排序栏：默认 / 文件名 / 大小 + 升降序切换
                item(key = "sort_bar") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "排序",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilterChip(
                            selected = sortMode == DownloadSortMode.DEFAULT,
                            onClick = { sortMode = DownloadSortMode.DEFAULT },
                            label = { Text("默认") },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = sortMode == DownloadSortMode.NAME,
                            onClick = { sortMode = DownloadSortMode.NAME },
                            label = { Text("文件名") },
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FilterChip(
                            selected = sortMode == DownloadSortMode.SIZE,
                            onClick = { sortMode = DownloadSortMode.SIZE },
                            label = { Text("大小") },
                        )
                        if (sortMode != DownloadSortMode.DEFAULT) {
                            IconButton(
                                onClick = { sortAsc = !sortAsc },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = if (sortAsc) {
                                        Icons.Filled.KeyboardArrowUp
                                    } else {
                                        Icons.Filled.KeyboardArrowDown
                                    },
                                    contentDescription = if (sortAsc) "升序" else "降序",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                groups.forEach { group ->
                    val groupKey = group.author
                    val expanded = expandedGroups.value.contains(groupKey)
                    item(key = "header_$groupKey") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedGroups.value =
                                        if (expanded) {
                                            expandedGroups.value - groupKey
                                        } else {
                                            expandedGroups.value + groupKey
                                        }
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AuthorAvatar(
                                face = group.face,
                                name = group.author,
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    text = group.author.ifBlank { "未知UP主" },
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = "${group.videos.size}个视频 · ${formatFileSize(group.totalSizeBytes)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.outline,
                                    maxLines = 1,
                                )
                            }
                            if (selectMode) {
                                TextButton(
                                    onClick = {
                                        val groupKeys = group.videos.map { it.dir_path }
                                        val allSelected = groupKeys.all {
                                            selectedKeys.value.contains(it)
                                        }
                                        selectedKeys.value = if (allSelected) {
                                            selectedKeys.value - groupKeys.toSet()
                                        } else {
                                            selectedKeys.value + groupKeys
                                        }
                                    },
                                ) {
                                    Text("全选")
                                }
                            } else {
                                Icon(
                                    imageVector = if (expanded) {
                                        Icons.Filled.KeyboardArrowDown
                                    } else {
                                        Icons.Filled.KeyboardArrowRight
                                    },
                                    contentDescription = if (expanded) "收起" else "展开",
                                    tint = MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                    }
                    if (expanded) {
                        items(group.videos, { it.dir_path }) { item ->
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
                                    selectedKeys.value =
                                        selectedKeys.value + item.dir_path
                                },
                            )
                        }
                    }
                }
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
                        onClick = { showBatchExportDialog = true },
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

/**
 * UP 主头像：有头像 URL 时加载圆形网络头像，
 * 否则用名称首字符生成字母头像（番剧组等无头像场景）。
 */
@Composable
private fun AuthorAvatar(
    face: String?,
    name: String,
    size: Dp = 40.dp,
) {
    if (!face.isNullOrBlank()) {
        AsyncImage(
            model = UrlUtil.autoHttps(face) + "@100w_100h_1c_",
            contentDescription = name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(CircleShape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = name.takeIf { it.isNotBlank() }?.take(1) ?: "?",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}