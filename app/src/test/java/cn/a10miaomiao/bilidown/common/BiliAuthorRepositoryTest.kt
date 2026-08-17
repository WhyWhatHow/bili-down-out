package cn.a10miaomiao.bilidown.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BiliAuthorRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    @Test
    fun `parseAuthor with valid response returns name and face`() {
        val body = """
            {
                "code": 0,
                "message": "0",
                "data": {
                    "aid": 123456,
                    "title": "测试视频",
                    "owner": {
                        "mid": 654321,
                        "name": "测试UP主",
                        "face": "http://example.com/face.jpg"
                    }
                }
            }
        """.trimIndent()

        val author = BiliAuthorRepository.parseAuthor(body)
        assertEquals("测试UP主", author?.name)
        assertEquals("http://example.com/face.jpg", author?.face)
    }

    @Test
    fun `parseAuthor with error code returns null`() {
        val body = """
            {
                "code": -404,
                "message": "啥都木有",
                "data": null
            }
        """.trimIndent()

        assertNull(BiliAuthorRepository.parseAuthor(body))
    }

    @Test
    fun `parseAuthor with blank name returns null`() {
        val body = """
            {
                "code": 0,
                "data": {
                    "owner": { "mid": 1, "name": "  " }
                }
            }
        """.trimIndent()

        assertNull(BiliAuthorRepository.parseAuthor(body))
    }

    @Test
    fun `parseAuthor without owner returns null`() {
        val body = """
            {
                "code": 0,
                "data": {
                    "aid": 1,
                    "title": "no owner"
                }
            }
        """.trimIndent()

        assertNull(BiliAuthorRepository.parseAuthor(body))
    }

    @Test
    fun `parseAuthor with malformed json returns null`() {
        assertNull(BiliAuthorRepository.parseAuthor("not json"))
        assertNull(BiliAuthorRepository.parseAuthor(""))
    }

    @Test
    fun `parseAuthor without face field returns name with null face`() {
        val body = """
            {
                "code": 0,
                "data": {
                    "owner": { "mid": 1, "name": "无头像UP主" }
                }
            }
        """.trimIndent()

        val author = BiliAuthorRepository.parseAuthor(body)
        assertEquals("无头像UP主", author?.name)
        assertNull(author?.face)
    }

    @Test
    fun `isNegativeCacheActive within ttl and expires after ttl`() {
        val failedAt = 1_000_000L
        // 有效期内
        assertTrue(
            BiliAuthorRepository.isNegativeCacheActive(
                failedAt,
                failedAt + BiliAuthorRepository.NEGATIVE_CACHE_TTL_MS - 1,
            )
        )
        // 边界：刚好到期
        assertFalse(
            BiliAuthorRepository.isNegativeCacheActive(
                failedAt,
                failedAt + BiliAuthorRepository.NEGATIVE_CACHE_TTL_MS,
            )
        )
        // 过期（含 now 早于 failedAt 的异常值）
        assertTrue(BiliAuthorRepository.isNegativeCacheActive(failedAt, failedAt + 1))
        assertFalse(
            BiliAuthorRepository.isNegativeCacheActive(
                failedAt,
                failedAt - BiliAuthorRepository.NEGATIVE_CACHE_TTL_MS,
            )
        )
    }

    @Test
    fun `writeCache writes atomically and init reads back new format`() {
        val file = tmpFolder.newFile("cache.json")
        val data = BiliAuthorRepository.CacheData(
            authors = mapOf(
                "bv:BV1xx" to BiliAuthorRepository.Author("UP甲", "http://face.jpg"),
            ),
            failed = mapOf("av:999" to 1234567890000L),
        )
        BiliAuthorRepository.writeCache(file, data)

        // 原子写：.tmp 已被 rename 消费，不残留
        assertFalse(java.io.File(file.parentFile, "cache.json.tmp").exists())

        BiliAuthorRepository.init(file)
        assertEquals(
            BiliAuthorRepository.Author("UP甲", "http://face.jpg"),
            BiliAuthorRepository.peekAuthor(bvid = "BV1xx"),
        )
        assertNull(BiliAuthorRepository.peekAuthor(avid = 999L))
    }

    @Test
    fun `init loads legacy map format cache`() {
        // 旧版本磁盘缓存格式：纯 Map<String, Author>
        val file = tmpFolder.newFile("legacy.json")
        file.writeText(
            """{"bv:BV1old": {"name": "老UP", "face": "http://old.jpg"}}"""
        )
        BiliAuthorRepository.init(file)

        assertEquals(
            BiliAuthorRepository.Author("老UP", "http://old.jpg"),
            BiliAuthorRepository.peekAuthor(bvid = "BV1old"),
        )
    }

    @Test
    fun `init with corrupted file silently drops cache`() {
        val file = tmpFolder.newFile("broken.json")
        file.writeText("{ not valid json !!")
        BiliAuthorRepository.init(file)
        assertNull(BiliAuthorRepository.peekAuthor(bvid = "BV1any"))
    }
}
