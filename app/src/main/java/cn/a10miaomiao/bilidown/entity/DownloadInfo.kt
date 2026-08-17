package cn.a10miaomiao.bilidown.entity

enum class DownloadType {
    VIDEO,
    BANGUMI
}
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

/** 文件大小格式化 */
fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format("%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format("%.1f MB", mb)
    return String.format("%.2f GB", mb / 1024.0)
}