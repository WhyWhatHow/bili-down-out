package cn.a10miaomiao.bilidown.ui.page

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.documentfile.provider.DocumentFile
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.BiliAuthorRepository
import cn.a10miaomiao.bilidown.common.BiliDownFile
import cn.a10miaomiao.bilidown.common.BiliDownOutFile
import cn.a10miaomiao.bilidown.common.datastore.DataStoreKeys
import cn.a10miaomiao.bilidown.common.datastore.rememberDataStorePreferencesFlow
import cn.a10miaomiao.bilidown.common.file.MiaoDocumentFile
import cn.a10miaomiao.bilidown.common.file.MiaoJavaFile
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadItemInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.shizuku.localShizukuPermission
import cn.a10miaomiao.bilidown.state.TaskStatus
import cn.a10miaomiao.bilidown.ui.BiliDownScreen
import cn.a10miaomiao.bilidown.ui.components.DownloadDetailItem
import cn.a10miaomiao.bilidown.ui.components.DownloadListItem
import cn.a10miaomiao.bilidown.ui.components.FileNameInputDialog
import kotlinx.coroutines.flow.Flow
import java.io.File


data class DownloadDetailPageState(
    val detailInfo: DownloadInfo?,
    val outRecordMap: Map<String, OutRecord>,
)

sealed class DownloadDetailPageAction {
    class Export(
        val entryDirPath: String,
        val outFile: BiliDownOutFile,
        val deleteSource: Boolean = false,
    ): DownloadDetailPageAction()

    class AddTask(
        val entryDirPath: String,
        val outFilePath: String,
        val title: String,
        val cover: String,
        val deleteSource: Boolean = false,
    ): DownloadDetailPageAction()
}

@Composable
fun DownloadDetailPagePresenter(
    context: Context,
    packageName: String,
    dirPath: String,
    navController: NavHostController,
    action: Flow<DownloadDetailPageAction>,
): DownloadDetailPageState {
    var detailInfo by remember {
        mutableStateOf<DownloadInfo?>(null)
    }
    var path by remember {
        mutableStateOf("")
    }
    val outRecordMap = remember {
        mutableStateMapOf<String, OutRecord>()
    }
    LaunchedEffect(packageName, dirPath) {
        val biliDownService = BiliDownService.getService(context)
            ?: return@LaunchedEffect
        val appState = (context.applicationContext as BiliDownApp).state
        val shizukuState = appState.shizukuState.value
        val biliDownFile = BiliDownFile(context, packageName, shizukuState.isEnabled)
//        val list = mutableListOf<DownloadInfo>()
        val dirFile = if (dirPath.startsWith("content://")) {
            MiaoDocumentFile(
                context,
                DocumentFile.fromTreeUri(
                    context,
                    Uri.parse(dirPath)
                )!!
            )
        } else {
            MiaoJavaFile(File(dirPath))
        }
        val rawList = biliDownFile.readDownloadDirectory(dirFile)
        // entry.json 无 owner 时，通过 B 站公开 API 在线补全 UP 主名称
        val list = rawList.map { item ->
            if (item.entry.author.isBlank()) {
                val author = BiliAuthorRepository.getAuthor(
                    avid = item.entry.avid,
                    bvid = item.entry.bvid,
                )
                if (author == null) {
                    item
                } else {
                    item.copy(
                        entry = item.entry.copy(
                            owner = BiliDownloadEntryInfo.OwnerInfo(name = author.name)
                        )
                    )
                }
            } else {
                item
            }
        }
        // 补全结果（含负缓存）统一落盘
        BiliAuthorRepository.persist()
        val items = mutableListOf<DownloadItemInfo>()
        var isCompleted = true
        list.forEach {
            val biliEntry = it.entry
            var indexTitle = ""
            var itemTitle = ""
            var id = it.entry.avid ?: 0L
            var cid = 0L
            var epid = 0L
            var type = DownloadType.VIDEO
            val page = biliEntry.page_data
            if (page != null) {
                id = biliEntry.avid ?: id
                indexTitle = page.download_title ?: page.part ?: "${page.page}P"
                cid = page.cid
                type = DownloadType.VIDEO
                itemTitle = biliEntry.title
            }
            val ep = biliEntry.ep
            val source = biliEntry.source
            if (ep != null && source != null) {
                id = biliEntry.season_id?.toLongOrNull() ?: id
                indexTitle = ep.index_title
                epid = ep.episode_id
                cid = source.cid
                type = DownloadType.BANGUMI
                itemTitle = ep.index + ep.index_title
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
            items.add(item)
            if (!item.is_completed) {
                isCompleted = false
            }
        }
        if (items.isNotEmpty()) {
            val paths = items.map {
                it.dir_path
            }.toTypedArray()
            val records = biliDownService.getRecordList(paths)
            outRecordMap.clear()
            records.forEach {
                outRecordMap[it.entryDirPath] = it
            }
        }
        if (list.isEmpty()) {
            detailInfo = null
        } else {
            val biliEntry = list[0].entry
            val item = items[0]
            detailInfo = DownloadInfo(
                dir_path = list[0].pageDirPath,
                media_type = biliEntry.media_type,
                has_dash_audio = biliEntry.has_dash_audio,
                is_completed = isCompleted,
                total_bytes = biliEntry.total_bytes,
                downloaded_bytes = biliEntry.downloaded_bytes,
                title = biliEntry.title,
                cover = biliEntry.cover,
                cid = item.cid,
                id = item.id,
                type = item.type,
                author = biliEntry.author,
                items = items
            )
        }
    }
    action.collectAction {
        when (it) {
            is DownloadDetailPageAction.Export -> {
                val biliDownService = BiliDownService.getService(context)
                    ?: return@collectAction
                val isSuccess = biliDownService.exportBiliVideo(
                    it.entryDirPath,
                    it.outFile.file,
                    it.deleteSource,
                )
                if (isSuccess) {
                    navController.navigate(BiliDownScreen.Progress.route) {
                        popUpTo(navController.graph.findStartDestination().id) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }

            is DownloadDetailPageAction.AddTask -> {
                val biliDownService = BiliDownService.getService(context)
                    ?: return@collectAction
                biliDownService.addTask(
                    it.entryDirPath,
                    it.outFilePath,
                    it.title,
                    it.cover,
                    it.deleteSource,
                )
            }
        }
    }
    return DownloadDetailPageState(
        detailInfo,
        outRecordMap,
    )
}

@Composable
fun DownloadDetailPage(
    navController: NavHostController,
    packageName: String,
    dirPath: String,
) {
    val context = LocalContext.current
    val appState = (context.applicationContext as BiliDownApp).state
    val taskStatus by appState.taskStatus.collectAsState()
    val (state, channel) = rememberPresenter(listOf(packageName, dirPath)) {
        DownloadDetailPagePresenter(context, packageName, dirPath, navController, it)
    }
    var selectedItem by remember {
        mutableStateOf<DownloadItemInfo?>(null)
    }
    val deleteSourceEnabled by rememberDataStorePreferencesFlow(
        context = context,
        key = DataStoreKeys.exportDeleteSource,
        initial = false,
    ).collectAsState(false)

    fun sendExportAction(
        item: DownloadItemInfo,
        outFile: BiliDownOutFile,
    ) {
        if (taskStatus is TaskStatus.InIdle) {
            channel.trySend(DownloadDetailPageAction.Export(
                entryDirPath = item.dir_path,
                outFile = outFile,
                deleteSource = deleteSourceEnabled,
            ))
        } else {
            channel.trySend(DownloadDetailPageAction.AddTask(
                entryDirPath = item.dir_path,
                outFilePath = outFile.path,
                title = outFile.name,
                cover = item.cover,
                deleteSource = deleteSourceEnabled,
            ))
        }
    }

    FileNameInputDialog(
        showInputDialog = selectedItem != null,
        fileName = selectedItem?.title ?: "",
        author = selectedItem?.author ?: "",
        onDismiss = {
            selectedItem = null
        },
        confirmText = if (taskStatus is TaskStatus.InIdle) {
            "确定导出"
        } else { "添加到队列" },
        onConfirm = { outFile ->
            selectedItem?.let { item ->
                sendExportAction(item, outFile)
            }
            selectedItem = null
        }
    )

    LazyColumn {
        val detailInfo = state.detailInfo
        if (detailInfo == null) {

        } else {
            item {
                DownloadListItem(
                    item = detailInfo,
                    onClick = { },
                )
            }
            items(detailInfo.items, { it.dir_path }) {
                DownloadDetailItem(
                    item = it,
                    isOut = state.outRecordMap.containsKey(it.dir_path),
                    onClick = {
                    },
                    onStartClick = {
                    },
                    onPauseClick = {
                    },
                    onExportClick = {
                        selectedItem = it
                    },
                )
            }
        }
    }
}