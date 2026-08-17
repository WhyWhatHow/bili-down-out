package cn.a10miaomiao.bilidown.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BiliAuthorRepositoryTest {

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
}
