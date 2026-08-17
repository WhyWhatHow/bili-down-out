package cn.a10miaomiao.bilidown.ui.page

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavHostController
import cn.a10miaomiao.bilidown.BiliDownApp
import cn.a10miaomiao.bilidown.common.MiaoLog
import cn.a10miaomiao.bilidown.common.molecule.collectAction
import cn.a10miaomiao.bilidown.common.molecule.rememberPresenter
import cn.a10miaomiao.bilidown.db.dao.OutRecord
import cn.a10miaomiao.bilidown.service.BiliDownService
import cn.a10miaomiao.bilidown.state.TaskStatus
import cn.a10miaomiao.bilidown.ui.components.OutFolderDialog
import cn.a10miaomiao.bilidown.ui.components.RecordItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

data class OutListPageState(
    val status: TaskStatus,
    val recordList: List<OutRecord>,
    /** 成功记录的源缓存目录是否仍存在（record.id -> exists），决定是否显示"删除原视频" */
    val sourceExistsMap: Map<Long, Boolean> = emptyMap(),
)


sealed class OutListPageAction {
    data object GetRecordList : OutListPageAction()

    class OpenVideo(
        val record: OutRecord
    ) : OutListPageAction()

    class DeleteRecord(
        val record: OutRecord,
        val isDeleteFile: Boolean,
    ): OutListPageAction()

    /** 删除该记录对应的哔哩哔哩缓存源目录（不影响已导出文件与记录本身） */
    class DeleteSourceVideo(
        val record: OutRecord,
    ): OutListPageAction()
}

@Composable
fun OutListPagePresenter(
    context: Context,
    action: Flow<OutListPageAction>,
): OutListPageState {
    val appState = remember(context) {
        (context.applicationContext as BiliDownApp).state
    }
    val taskStatus by appState.taskStatus.collectAsState()

    var recordList by remember {
        mutableStateOf(emptyList<OutRecord>())
    }
    var sourceExistsMap by remember {
        mutableStateOf(emptyMap<Long, Boolean>())
    }

    suspend fun getRecordList(
        biliDownService: BiliDownService
    ) {
        recordList = biliDownService.getRecordList(OutRecord.STATUS_SUCCESS)
        withContext(Dispatchers.IO) {
            recordList = recordList.map { record ->
                if (record.status == OutRecord.STATUS_SUCCESS) {
                    val exists = File(record.outFilePath).exists()
                    record.copy(
                        status = if (exists) 1 else -1,
                    )
                } else {
                    record
                }
            }
            // 预检查成功记录的源缓存是否仍存在，决定是否显示"删除原视频"菜单项
            sourceExistsMap = recordList.mapNotNull { record ->
                val id = record.id ?: return@mapNotNull null
                id to biliDownService.sourceVideoExists(record.entryDirPath)
            }.toMap()
        }
    }

    action.collectAction {
        when (it) {
            OutListPageAction.GetRecordList -> {
                val biliDownService = BiliDownService.getService(context)
                getRecordList(biliDownService)
            }
            is OutListPageAction.OpenVideo -> {
                val videoFile = File(it.record.outFilePath)
                if (videoFile.exists()) {
                    val intent = Intent(Intent.ACTION_VIEW)
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        intent.flags =
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                        FileProvider.getUriForFile(
                            context,
                            "cn.a10miaomiao.bilidown.fileprovider",
                            videoFile
                        )
                    } else {
                        Uri.fromFile(videoFile)
                    }
                    intent.setDataAndType(uri, "video/*")
                    context.startActivity(intent)
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "视频文件不存在", Toast.LENGTH_SHORT)
                            .show()
                    }
                }
            }
            is OutListPageAction.DeleteRecord -> {
                val biliDownService = BiliDownService.getService(context)
                biliDownService.delTask(it.record, it.isDeleteFile)
                getRecordList(biliDownService)
            }
            is OutListPageAction.DeleteSourceVideo -> {
                val biliDownService = BiliDownService.getService(context)
                val message = when (biliDownService.deleteSourceVideo(it.record.entryDirPath)) {
                    null -> "已删除原视频：${it.record.title}"
                    BiliDownService.SOURCE_NOT_FOUND -> "原视频已不存在：${it.record.title}"
                    else -> "删除原视频失败：${it.record.title}"
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                getRecordList(biliDownService)
            }
        }
    }

    return OutListPageState(
        status = taskStatus,
        recordList = recordList,
        sourceExistsMap = sourceExistsMap,
    )
}

@Composable
internal fun ReconfirmDeleteDialog(
    channel: Channel<OutListPageAction>,
    action: OutListPageAction.DeleteRecord?,
    onDismiss: () -> Unit,
) {
    if (action != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                if (action.isDeleteFile) {
                    Text(text = "确认删除该条记录，并删除文件？")
                } else {
                    Text(text = "确认删除该条记录？")
                }
            },
            text = {
                Column {
                    Text("删除：" + action.record.title)
                    if (action.isDeleteFile) {
                        Text(
                            color = Color.Red,
                            text = "删除记录，并同时删除导出文件",
                        )
                    } else {
                        Text("仅删除记录，不删除文件")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        channel.trySend(action)
                        onDismiss()
                    },
                ) {
                    Text("确认删除")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismiss,
                ) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
internal fun ReconfirmDeleteSourceDialog(
    record: OutRecord?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (record != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = "确认删除原视频？") },
            text = {
                Column {
                    Text("删除：${record.title}")
                    Text(
                        color = Color.Red,
                        text = "将删除哔哩哔哩缓存中的源视频（不可恢复），不影响已导出文件。",
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onConfirm()
                    onDismiss()
                }) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
fun OutListPage(
    navController: NavHostController,
) {
    val context = LocalContext.current
    val (state, channel) = rememberPresenter {
        OutListPagePresenter(context, it)
    }
    LaunchedEffect(
        channel, state.status,
    ) {
        channel.send(OutListPageAction.GetRecordList)
    }

    var showOutFolderDialog by remember {
        mutableStateOf(false)
    }
    OutFolderDialog(
        showOutFolderDialog = showOutFolderDialog,
        onDismiss = {
            showOutFolderDialog = false
        },
    )

    var reconfirmDeleteDialogAction by remember {
        mutableStateOf<OutListPageAction.DeleteRecord?>(null)
    }
    ReconfirmDeleteDialog(
        channel = channel,
        action = reconfirmDeleteDialogAction,
        onDismiss = {
            reconfirmDeleteDialogAction = null
        },
    )

    var reconfirmDeleteSourceRecord by remember {
        mutableStateOf<OutRecord?>(null)
    }
    ReconfirmDeleteSourceDialog(
        record = reconfirmDeleteSourceRecord,
        onDismiss = { reconfirmDeleteSourceRecord = null },
        onConfirm = {
            reconfirmDeleteSourceRecord?.let {
                channel.trySend(OutListPageAction.DeleteSourceVideo(it))
            }
        },
    )

    LazyColumn(
        contentPadding = PaddingValues(bottom = 80.dp),
    ) {
        if (state.recordList.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(
                        painter = painterResource(id = cn.a10miaomiao.bilidown.R.drawable.ic_movie_pay_area_limit),
                        contentDescription = "空空如也",
                        modifier = Modifier.size(150.dp, 150.dp)
                    )
                    Text(
                        modifier = Modifier.padding(vertical = 8.dp),
                        text = "空空如也",
                    )
                }
            }
        } else {
            items(state.recordList, { it.id!! }) { item ->
                RecordItem(
                    title = item.title,
                    cover = item.cover,
                    status = item.status,
                    onClick = {
                        channel.trySend(
                            OutListPageAction.OpenVideo(item)
                        )
                    },
                    onDeleteClick = {
                        reconfirmDeleteDialogAction = OutListPageAction.DeleteRecord(
                            record = item, isDeleteFile = it
                        )
                    },
                    sourceExists = item.id?.let { state.sourceExistsMap[it] } == true,
                    onDeleteSourceClick = {
                        reconfirmDeleteSourceRecord = item
                    },
                )
            }
        }
        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TextButton(
                    onClick = { showOutFolderDialog = true }
                ) {
                    Text(text = "导出文件夹在哪？")
                }
            }
        }
    }
}