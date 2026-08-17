package cn.a10miaomiao.bilidown.entity

import kotlinx.serialization.Serializable

enum class DownloadType {
    VIDEO,
    BANGUMI
}
@Serializable
data class DownloadInfo(
    val dir_path: String,
    val media_type: Int,
    val has_dash_audio: Boolean,
    var is_completed: Boolean,
    val total_bytes: Long,
    val downloaded_bytes: Long,
    val title: String,
    val cover: String,
    val id: Long,
    val cid: Long,
    val type: DownloadType,
    var author: String = "",
    var authorFace: String? = null,
    val items: MutableList<DownloadItemInfo>,
//    val owner_id: Long,
)

@Serializable
data class DownloadItemInfo(
    val dir_path: String,
    val media_type: Int,
    val has_dash_audio: Boolean,
    val is_completed: Boolean,
    val total_bytes: Long,
    val downloaded_bytes: Long,
    val title: String,
    val cover: String,
    val id: Long,
    val type: DownloadType,
    val index_title: String,
    val cid: Long,
    val epid: Long,
    var author: String = "",
)

data class DownloadGroup(
    val author: String,
    val face: String? = null,
    val videos: List<DownloadInfo>,
) {
    /** 组内所有分P文件大小合计 */
    val totalSizeBytes: Long
        get() = videos.sumOf { it.items.sumOf { item -> item.total_bytes } }
}

/** 列表排序方式 */
enum class DownloadSortMode {
    /** 默认（按 UP 主拼音分组，组内保持原始顺序） */
    DEFAULT,
    /** 按文件名排序 */
    NAME,
    /** 按文件大小排序 */
    SIZE,
}

/** 番剧/影视类内容没有 UP 主，统一归入该分组 */
const val BANGUMI_GROUP_LABEL = "番剧·影视"

fun List<DownloadInfo>.groupByAuthor(): List<DownloadGroup> {
    // 按 UP 主名称拼音排序（中文 Collator），未知名的排在最后
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return groupBy { info ->
        when {
            info.author.isNotBlank() -> info.author
            // 番剧没有 UP 主概念，避免误归入"未知UP主"
            info.type == DownloadType.BANGUMI -> BANGUMI_GROUP_LABEL
            else -> ""
        }
    }.map { (author, videos) ->
        DownloadGroup(
            author = author,
            face = videos.firstNotNullOfOrNull { it.authorFace },
            videos = videos,
        )
    }.sortedWith(compareBy({ it.author.isBlank() }, { collator.getCollationKey(it.author) }))
}

/**
 * 对分组列表应用排序：
 * - NAME：组内视频按标题排序（组仍按 UP 主拼音）
 * - SIZE：组内视频按大小排序，组间也按总大小排序
 */
fun List<DownloadGroup>.applySort(
    mode: DownloadSortMode,
    asc: Boolean,
): List<DownloadGroup> {
    val collator = java.text.Collator.getInstance(java.util.Locale.CHINA)
    return when (mode) {
        DownloadSortMode.DEFAULT -> this
        DownloadSortMode.NAME -> map { group ->
            group.copy(
                videos = group.videos.sortedWith(
                    if (asc) compareBy { collator.getCollationKey(it.title) }
                    else compareByDescending { collator.getCollationKey(it.title) }
                )
            )
        }
        DownloadSortMode.SIZE -> {
            val groups = map { group ->
                group.copy(
                    videos = group.videos.sortedWith(
                        if (asc) compareBy { it.items.sumOf { item -> item.total_bytes } }
                        else compareByDescending { it.items.sumOf { item -> item.total_bytes } }
                    )
                )
            }
            groups.sortedWith(
                if (asc) compareBy { it.totalSizeBytes }
                else compareByDescending { it.totalSizeBytes }
            )
        }
    }
}

/**
 * 将 entry.json 读取结果映射为列表展示用的 DownloadInfo 列表。
 * 同一视频（type + id 相同）的多个分P 会合并到同一个 DownloadInfo 的 items 中。
 * 列表页与 UP 主详情页共用该逻辑，保持分组行为一致。
 */
fun buildDownloadInfoList(
    entryList: List<BiliDownloadEntryAndPathInfo>,
): MutableList<DownloadInfo> {
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
            // avid 可能缺失（AGENTS：entry 字段可为 null），缺失时兜底 0
            id = biliEntry.avid ?: id
            indexTitle = page.download_title ?: page.part ?: "${page.page}P"
            cid = page.cid
            type = DownloadType.VIDEO
            itemTitle = biliEntry.title
        }
        val ep = biliEntry.ep
        val source = biliEntry.source
        if (ep != null && source != null) {
            // season_id 为 String?，缺失/非数字时兜底 0，避免 NumberFormat 崩溃
            id = biliEntry.season_id?.toLongOrNull() ?: id
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
    return newList
}

/** 文件大小格式化 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}