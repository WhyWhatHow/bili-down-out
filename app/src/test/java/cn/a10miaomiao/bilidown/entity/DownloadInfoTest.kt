package cn.a10miaomiao.bilidown.entity

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadInfoTest {

    private fun video(
        dirPath: String,
        author: String,
    ) = DownloadInfo(
        dir_path = dirPath,
        media_type = 2,
        has_dash_audio = true,
        is_completed = true,
        total_bytes = 0L,
        downloaded_bytes = 0L,
        title = "标题$dirPath",
        cover = "",
        id = 0L,
        cid = 0L,
        type = DownloadType.VIDEO,
        author = author,
        items = mutableListOf(),
    )

    @Test
    fun `groupByAuthor groups videos by uploader keeping order`() {
        val list = listOf(
            video("1", "UP甲"),
            video("2", "UP乙"),
            video("3", "UP甲"),
            video("4", ""),
        )
        val groups = list.groupByAuthor()

        assertEquals(3, groups.size)
        assertEquals("UP甲", groups[0].author)
        assertEquals(listOf("1", "3"), groups[0].videos.map { it.dir_path })
        assertEquals("UP乙", groups[1].author)
        assertEquals("", groups[2].author)
    }

    @Test
    fun `groupByAuthor empty list returns empty`() {
        assertEquals(0, emptyList<DownloadInfo>().groupByAuthor().size)
    }

    @Test
    fun `groupByAuthor blank bangumi goes to bangumi label group`() {
        val bangumi = video("1", "").copy(type = DownloadType.BANGUMI)
        val bangumi2 = video("2", "").copy(type = DownloadType.BANGUMI)
        val normal = video("3", "UP甲")

        val groups = listOf(bangumi, bangumi2, normal).groupByAuthor()

        // 未知番剧归入统一标签，不再显示为"未知UP主"（拉丁字母按拼音排序在中文前）
        assertEquals(2, groups.size)
        assertEquals("UP甲", groups[0].author)
        assertEquals(BANGUMI_GROUP_LABEL, groups[1].author)
        assertEquals(listOf("1", "2"), groups[1].videos.map { it.dir_path })
    }

    @Test
    fun `groupByAuthor aggregates face from videos`() {
        val withFace = video("1", "UP甲").copy(authorFace = "http://a.jpg")
        val withoutFace = video("2", "UP甲")

        val groups = listOf(withoutFace, withFace).groupByAuthor()

        assertEquals("http://a.jpg", groups[0].face)
    }

    private fun item(
        dirPath: String,
        title: String,
        totalBytes: Long,
    ) = DownloadItemInfo(
        dir_path = dirPath,
        media_type = 2,
        has_dash_audio = true,
        is_completed = true,
        total_bytes = totalBytes,
        downloaded_bytes = totalBytes,
        title = title,
        cover = "",
        id = 0L,
        type = DownloadType.VIDEO,
        index_title = "",
        cid = 0L,
        epid = 0L,
    )

    @Test
    fun `applySort NAME sorts videos by title in group`() {
        val group = DownloadGroup(
            author = "UP甲",
            videos = listOf(
                video("1", "UP甲").copy(title = "b标题", items = mutableListOf(item("1", "b标题", 10))),
                video("2", "UP甲").copy(title = "a标题", items = mutableListOf(item("2", "a标题", 20))),
            ),
        )
        val sorted = listOf(group).applySort(DownloadSortMode.NAME, asc = true)
        assertEquals(listOf("a标题", "b标题"), sorted[0].videos.map { it.title })

        val desc = listOf(group).applySort(DownloadSortMode.NAME, asc = false)
        assertEquals(listOf("b标题", "a标题"), desc[0].videos.map { it.title })
    }

    @Test
    fun `applySort SIZE sorts videos and groups by total bytes`() {
        val small = video("1", "UP甲").copy(items = mutableListOf(item("1", "小", 10)))
        val big = video("2", "UP甲").copy(items = mutableListOf(item("2", "大", 100)))
        val groups = listOf(
            DownloadGroup(author = "UP甲", videos = listOf(small, big)),
            DownloadGroup(
                author = "UP乙",
                videos = listOf(video("3", "UP乙").copy(items = mutableListOf(item("3", "中", 50)))),
            ),
        )
        val asc = groups.applySort(DownloadSortMode.SIZE, asc = true)
        // 组按总大小升序：UP乙(50) 在前，UP甲(110) 在后；UP甲 组内 10 在前
        assertEquals(listOf("UP乙", "UP甲"), asc.map { it.author })
        assertEquals(listOf(10L, 100L), asc[1].videos.map { it.items[0].total_bytes })

        val desc = groups.applySort(DownloadSortMode.SIZE, asc = false)
        assertEquals(listOf("UP甲", "UP乙"), desc.map { it.author })
        assertEquals(listOf(100L, 10L), desc[0].videos.map { it.items[0].total_bytes })
    }

    @Test
    fun `formatFileSize formats common units`() {
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.0 KB", formatFileSize(1024))
        assertEquals("1.5 MB", formatFileSize((1.5 * 1024 * 1024).toLong()))
        assertEquals("1.00 GB", formatFileSize(1024L * 1024 * 1024))
    }
}