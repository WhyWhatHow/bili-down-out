package cn.a10miaomiao.bilidown.ui.page

import cn.a10miaomiao.bilidown.common.BiliAuthorRepository
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryAndPathInfo
import cn.a10miaomiao.bilidown.entity.BiliDownloadEntryInfo
import cn.a10miaomiao.bilidown.entity.DownloadInfo
import cn.a10miaomiao.bilidown.entity.DownloadType
import cn.a10miaomiao.bilidown.entity.buildDownloadInfoList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadListPageTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private fun entry(
        dirPath: String,
        avid: Long,
        bvid: String? = null,
        author: String? = null,
    ) = BiliDownloadEntryAndPathInfo(
        pageDirPath = dirPath,
        entryDirPath = dirPath,
        entry = BiliDownloadEntryInfo(
            is_completed = true,
            total_bytes = 10,
            downloaded_bytes = 10,
            title = "标题$dirPath",
            cover = "",
            prefered_video_quality = 1,
            guessed_total_bytes = 0,
            total_time_milli = 0,
            danmaku_count = 0,
            avid = avid,
            bvid = bvid,
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

    /** 向磁盘缓存写入已知的 UP 主映射并加载 */
    private fun primeDiskCache(vararg pairs: Pair<String, BiliAuthorRepository.Author>) {
        val file = tmpFolder.newFile("cache.json")
        BiliAuthorRepository.writeCache(
            file,
            BiliAuthorRepository.CacheData(authors = pairs.toMap()),
        )
        BiliAuthorRepository.init(file)
    }

    @Test
    fun `fillMissingAuthors local mode fills author from disk cache without network`() = runBlocking {
        primeDiskCache(
            "bv:BV1known" to BiliAuthorRepository.Author("缓存UP主", "http://face.jpg"),
        )
        val entryList = listOf(
            entry("1", avid = 100, bvid = "BV1known"), // 缓存命中
            entry("2", avid = 200, bvid = "BV2unknown"), // 缓存未命中（如会员视频）
            entry("3", avid = 300, author = "entry自带"), // entry.json 自带 owner
        )
        val newList = buildDownloadInfoList(entryList)

        var updated = false
        fillMissingAuthors(entryList, newList, fetchRemote = false, onUpdated = { updated = true })

        assertTrue(updated)
        assertEquals("缓存UP主", newList[0].author)
        assertEquals("http://face.jpg", newList[0].authorFace)
        // 未命中的保持空白（归"未知UP主"组），不发网络请求
        assertEquals("", newList[1].author)
        // 自带的不被覆盖
        assertEquals("entry自带", newList[2].author)
    }

    @Test
    fun `fillMissingAuthors local mode no cache hit leaves list untouched`() = runBlocking {
        // 空磁盘缓存：所有条目都查不到
        primeDiskCache()
        val entryList = listOf(
            entry("1", avid = 100, bvid = "BV1miss"),
        )
        val newList = buildDownloadInfoList(entryList)

        var updated = false
        fillMissingAuthors(entryList, newList, fetchRemote = false, onUpdated = { updated = true })

        assertFalse(updated)
        assertEquals("", newList[0].author)
    }

    @Test
    fun `fillMissingAuthors skips when all authors known`() = runBlocking {
        val entryList = listOf(
            entry("1", avid = 100, author = "UP甲"),
        )
        val newList = buildDownloadInfoList(entryList)

        var updated = false
        fillMissingAuthors(entryList, newList, fetchRemote = false, onUpdated = { updated = true })
        fillMissingAuthors(entryList, newList, fetchRemote = true, onUpdated = { updated = true })

        assertFalse(updated)
        assertEquals("UP甲", newList[0].author)
    }

    @Test
    fun `fillMissingAuthors local mode merges parts into single video author`() = runBlocking {
        // 同一视频两个分P：任一分P命中缓存即整视频归该UP主
        primeDiskCache(
            "av:100" to BiliAuthorRepository.Author("多P的UP"),
        )
        val entryList = listOf(
            entry("1", avid = 100), // 无 bvid，走 av key
            entry("2", avid = 100),
        )
        val newList = buildDownloadInfoList(entryList)
        assertEquals(1, newList.size) // 合并为一个视频

        fillMissingAuthors(entryList, newList, fetchRemote = false, onUpdated = { })
        assertEquals("多P的UP", newList[0].author)
        assertEquals(2, newList[0].items.count { it.author == "多P的UP" })
    }
}
