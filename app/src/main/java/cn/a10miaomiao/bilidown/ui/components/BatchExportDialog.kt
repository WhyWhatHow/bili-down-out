package cn.a10miaomiao.bilidown.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 批量导出确认对话框。
 *
 * 「导出后删除源文件」行为由设置页统一控制（默认关闭），此处仅展示状态，
 * 避免多个对话框叠加导致按钮事件丢失。
 */
@Composable
fun BatchExportDialog(
    videoCount: Int,
    partCount: Int,
    deleteSourceEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "批量导出")
        },
        text = {
            Column {
                Text("将导出 $videoCount 个视频（共 $partCount 个分P）")
                Text("导出到 Download/BiliDownOut/<UP主>/ 文件夹")
                Spacer(modifier = Modifier.height(12.dp))
                if (deleteSourceEnabled) {
                    Text(
                        color = MaterialTheme.colorScheme.error,
                        text = "导出成功后将删除源文件（哔哩缓存，不可恢复）！",
                    )
                    Text(
                        color = MaterialTheme.colorScheme.outline,
                        text = "可在 设置 中关闭该行为。",
                    )
                } else {
                    Text(
                        color = MaterialTheme.colorScheme.outline,
                        text = "导出后保留源文件（可在 设置 中开启删除）。",
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(if (deleteSourceEnabled) "导出并删除源文件" else "开始导出")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
            ) {
                Text("取消")
            }
        },
    )
}