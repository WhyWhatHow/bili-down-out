package cn.a10miaomiao.bilidown.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import cn.a10miaomiao.bilidown.common.UrlUtil
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.entity.formatFileSize
import coil.compose.AsyncImage

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadListItem(
    item: DownloadInfo,
    onClick: () -> Unit,
    selectMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier.padding(5.dp),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shadowElevation = 1.dp,
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column() {
                Row(
                    modifier = Modifier
                        .combinedClickable(
                            onClick = onClick,
                            onLongClick = onLongClick,
                        )
                        .padding(10.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selectMode) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { onClick() },
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }

                    AsyncImage(
                        model = UrlUtil.autoHttps(item.cover) + "@672w_378h_1c_",
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(width = 120.dp, height = 80.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .height(80.dp)
                            .padding(horizontal = 10.dp),
                    ) {
                        Text(
                            text = item.title,
                            maxLines = 2,
                            modifier = Modifier.weight(1f),
                            overflow = TextOverflow.Ellipsis,
                        )
                        val status = if (item.is_completed) {
                            "已完成"
                        } else {
                            "暂停中"
                        }
                        // 多P才显示分P数量，单P无需冗余信息
                        val partText = if (item.items.size > 1) "${item.items.size}P · " else ""
                        val totalSize = item.items.sumOf { it.total_bytes }
                        Text(
                            text = "$partText$status · ${formatFileSize(totalSize)}",
                            maxLines = 1,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Preview
@Composable
fun DownloadListItemPreview() {
    DownloadListItem(
        DownloadInfo("", 1,
            has_dash_audio = true,
            is_completed = true,
            total_bytes = 0L,
            downloaded_bytes = 0L,
            title = "标题",
            cover = "",
            id = 0L,
            cid = 0L,
            type = DownloadType.VIDEO,
            items = mutableListOf()
        ),
        {}
    )
}