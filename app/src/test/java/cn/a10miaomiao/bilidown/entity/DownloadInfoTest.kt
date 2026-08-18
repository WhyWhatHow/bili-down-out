package cn.a10miaomiao.bilidown.entity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

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

    // ---- 扁平列表排序（UP 主页 applySort）边界 ----

    private fun flatVideo(
        dirPath: String,
        title: String,
        bytesList: List<Long> = listOf(0L),
    ) = video(dirPath, "UP甲").copy(
        title = title,
        items = mutableListOf<DownloadItemInfo>().apply {
            bytesList.forEachIndexed { i, b ->
                add(item("$dirPath/$i", title, b))
            }
        },
    )

    @Test
    fun `flat applySort DEFAULT keeps original order`() {
        val list = listOf(
            flatVideo("2", "bBBB"),
            flatVideo("1", "AAAA"),
            flatVideo("3", "cCc"),
        )
        // 不管标题是否乱序，DEFAULT 一律保持原顺序，且返回同一实例
        val fromEmpty = emptyList<DownloadInfo>().applySort(DownloadSortMode.DEFAULT, true)
        assertTrue(fromEmpty.isEmpty())
        assertEquals(listOf("2", "1", "3"), list.applySort(DownloadSortMode.DEFAULT, true).map { it.dir_path })
    }

    @Test
    fun `flat applySort NAME sorts by pinyin asc and desc`() {
        val list = listOf(
            flatVideo("1", "张三"),
            flatVideo("2", "阿波罗"),
            flatVideo("3", "李四"),
        )
        // 拼音升序：阿(a) < 李(l) < 张(z)
        val asc = list.applySort(DownloadSortMode.NAME, asc = true)
        assertEquals(listOf("2", "3", "1"), asc.map { it.dir_path })

        val desc = list.applySort(DownloadSortMode.NAME, asc = false)
        assertEquals(listOf("1", "3", "2"), desc.map { it.dir_path })
    }

    @Test
    fun `flat applySort SIZE sums all parts of a video`() {
        val list = listOf(
            flatVideo("a", "A", bytesList = listOf(100L, 100L)), // 200
            flatVideo("b", "B", bytesList = listOf(50L)),        // 50
            flatVideo("c", "C", bytesList = listOf(75L, 75L, 75L)), // 225
        )
        val asc = list.applySort(DownloadSortMode.SIZE, asc = true)
        assertEquals(listOf("b", "a", "c"), asc.map { it.dir_path })

        val desc = list.applySort(DownloadSortMode.SIZE, asc = false)
        assertEquals(listOf("c", "a", "b"), desc.map { it.dir_path })
    }

    @Test
    fun `flat applySort SIZE handles empty items as zero`() {
        val list = listOf(
            flatVideo("zero", "Z"),
            video("noitems", "UP甲"), // items 为空
        )
        val asc = list.applySort(DownloadSortMode.SIZE, asc = true)
        // noitems 与 zero 大小均为 0，稳定排序保持原顺序不崩溃
        assertTrue(asc.any { it.dir_path == "noitems" })
        assertTrue(asc.any { it.dir_path == "zero" })
    }

    @Test
    fun `flat applySort empty list returns empty for all modes`() {
        assertTrue(emptyList<DownloadInfo>().applySort(DownloadSortMode.NAME, true).isEmpty())
        assertTrue(emptyList<DownloadInfo>().applySort(DownloadSortMode.SIZE, false).isEmpty())
    }

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

    @Test
    fun `DownloadInfo json serialize round-trip preserves fields`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = DownloadInfo(
            dir_path = "/data/dir",
            media_type = 2,
            has_dash_audio = true,
            is_completed = false,
            total_bytes = 12345,
            downloaded_bytes = 6789,
            title = "视频标题",
            cover = "http://cover.jpg",
            id = 100L,
            cid = 200L,
            type = DownloadType.BANGUMI,
            author = "UP主",
            authorFace = "http://face.jpg",
            items = mutableListOf(
                DownloadItemInfo(
                    dir_path = "/data/dir/e1",
                    media_type = 2,
                    has_dash_audio = true,
                    is_completed = true,
                    total_bytes = 12345,
                    downloaded_bytes = 6789,
                    title = "分P标题",
                    cover = "http://cover.jpg",
                    id = 100L,
                    type = DownloadType.BANGUMI,
                    index_title = "第1话",
                    cid = 200L,
                    epid = 300L,
                    author = "UP主",
                )
            ),
        )

        val decoded = json.decodeFromString(
            DownloadInfo.serializer(),
            json.encodeToString(DownloadInfo.serializer(), original),
        )

        assertEquals(original.dir_path, decoded.dir_path)
        assertEquals(original.title, decoded.title)
        assertEquals(original.author, decoded.author)
        assertEquals(original.authorFace, decoded.authorFace)
        assertEquals(original.type, decoded.type)
        assertEquals(false, decoded.is_completed)
        assertEquals(original.items.size, decoded.items.size)
        assertEquals("第1话", decoded.items[0].index_title)
        assertEquals(300L, decoded.items[0].epid)
        assertEquals(12345L, decoded.total_bytes)
    }

    private fun entry(
        dirPath: String,
        avid: Long,
        title: String,
        author: String? = null,
        completed: Boolean = true,
    ) = BiliDownloadEntryAndPathInfo(
        pageDirPath = dirPath,
        entryDirPath = dirPath,
        entry = BiliDownloadEntryInfo(
            is_completed = completed,
            total_bytes = 10,
            downloaded_bytes = 10,
            title = title,
            cover = "",
            prefered_video_quality = 1,
            guessed_total_bytes = 0,
            total_time_milli = 0,
            danmaku_count = 0,
            avid = avid,
            owner = author?.let { BiliDownloadEntryInfo.OwnerInfo(name = it) },
            page_data = BiliDownloadEntryInfo.PageInfo(
                cid = avid,
                page = 1,
                has_alias = false,
                tid = 0,
                part = "P1",
            ),
        ),
    )

    @Test
    fun `buildDownloadInfoList merges same video parts and keeps author`() {
        val list = buildDownloadInfoList(
            listOf(
                entry("1", avid = 100, title = "视频A", author = "UP甲"),
                entry("2", avid = 100, title = "视频A", author = "UP甲"),
                entry("3", avid = 200, title = "视频B", author = "UP乙"),
            )
        )

        assertEquals(2, list.size)
        assertEquals(listOf("1", "2"), list[0].items.map { it.dir_path })
        assertEquals("UP甲", list[0].author)
        assertEquals("UP乙", list[1].author)
    }

    @Test
    fun `buildDownloadInfoList incomplete part marks whole video incomplete`() {
        val list = buildDownloadInfoList(
            listOf(
                entry("1", avid = 100, title = "A", completed = true),
                entry("2", avid = 100, title = "A", completed = false),
            )
        )

        assertEquals(1, list.size)
        assertEquals(false, list[0].is_completed)
    }

    @Test
    fun `buildDownloadInfoList bangumi entry maps to season id and BANGUMI type`() {
        val bangumi = BiliDownloadEntryAndPathInfo(
            pageDirPath = "s1",
            entryDirPath = "s1",
            entry = BiliDownloadEntryInfo(
                is_completed = true,
                total_bytes = 10,
                downloaded_bytes = 10,
                title = "番剧标题",
                cover = "",
                prefered_video_quality = 1,
                guessed_total_bytes = 0,
                total_time_milli = 0,
                danmaku_count = 0,
                season_id = "999",
                source = BiliDownloadEntryInfo.SourceInfo(av_id = 1, cid = 2),
                ep = BiliDownloadEntryInfo.EpInfo(
                    av_id = 1,
                    page = 1,
                    danmaku = 0,
                    cover = "",
                    episode_id = 5,
                    index = "1",
                    index_title = "第1话",
                    from = "",
                    season_type = 1,
                    width = 0,
                    height = 0,
                    rotate = 0,
                ),
            ),
        )

        val list = buildDownloadInfoList(listOf(bangumi))

        assertEquals(1, list.size)
        assertEquals(DownloadType.BANGUMI, list[0].type)
        assertEquals(999L, list[0].id)
        assertEquals("第1话", list[0].items[0].title)
    }
}