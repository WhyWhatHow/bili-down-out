package cn.a10miaomiao.bilidown.ui.page

import cn.a10miaomiao.bilidown.db.dao.OutRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutListPageSelectionTest {

    private fun record(
        id: Long?,
        status: Int = OutRecord.STATUS_SUCCESS,
    ) = OutRecord(
        id = id,
        entryDirPath = "dir/$id",
        outFilePath = "out/$id",
        title = "视频$id",
        cover = "",
        status = status,
        type = 1,
        createTime = 0L,
        updateTime = 0L,
    )

    // ---- 源未知记录过滤（filterOutSourceUnknown）边界 ----

    @Test
    fun `filter out source unknown records`() {
        val result = filterOutSourceUnknown(
            listOf(
                record(1L),                       // entryDirPath 正常，保留
                record(2L).copy(entryDirPath = ""), // 源未知，剔除
                record(3L).copy(entryDirPath = "   "), // 空白路径，视为源未知，剔除
                record(4L, OutRecord.STATUS_WAIT), // 保留（只过滤源未知，不影响状态）
            )
        )
        assertEquals(listOf(1L, 4L), result.map { it.id })
    }

    @Test
    fun `filter out source unknown empty list`() {
        assertTrue(filterOutSourceUnknown(emptyList()).isEmpty())
        assertTrue(filterOutSourceUnknown(listOf(record(1L).copy(entryDirPath = ""))).isEmpty())
    }

    // ---- isCleanableRecord 边界 ----

    @Test
    fun `成功且源存在可删除`() {
        assertTrue(
            isCleanableRecord(record(1L), mapOf(1L to true))
        )
    }

    @Test
    fun `未导出记录不可删除`() {
        assertFalse(
            isCleanableRecord(record(1L, OutRecord.STATUS_WAIT), mapOf(1L to true))
        )
        assertFalse(
            isCleanableRecord(record(1L, OutRecord.STATUS_FAIL), mapOf(1L to true))
        )
    }

    @Test
    fun `源已不存在不可删除`() {
        assertFalse(
            isCleanableRecord(record(1L), mapOf(1L to false))
        )
        // 完全不在映射中（检查失败/未知）也不可删除
        assertFalse(
            isCleanableRecord(record(1L), emptyMap())
        )
    }

    @Test
    fun `id为空不可删除`() {
        // id 为 null（异常入库场景）不可删除，避免空指针
        assertFalse(
            isCleanableRecord(record(null), emptyMap())
        )
        assertFalse(
            isCleanableRecord(record(null), mapOf(1L to true))
        )
    }

    // ---- cleanableRecords 过滤 ----

    @Test
    fun `过滤只保留成功且有源可删记录`() {
        val list = listOf(
            record(1L),                                     // 可删
            record(2L, OutRecord.STATUS_FAIL),              // 未导出，排除
            record(3L),                                     // 源不存在，排除
            record(null),                                   // id空，排除
        )
        val result = cleanableRecords(
            list,
            mapOf(1L to true, 3L to false),
        )
        assertEquals(listOf(1L), result.map { it.id })
    }

    @Test
    fun `列表为空返回空`() {
        assertTrue(cleanableRecords(emptyList(), emptyMap()).isEmpty())
        assertTrue(cleanableRecords(emptyList(), mapOf(1L to true)).isEmpty())
    }

    @Test
    fun `全部不可删时返回空`() {
        val list = listOf(record(1L, OutRecord.STATUS_WAIT), record(2L))
        assertTrue(cleanableRecords(list, mapOf(2L to false)).isEmpty())
    }

    // ---- toggleSelectAll 边界 ----

    @Test
    fun `全选时选择所有可删项`() {
        assertEquals(
            setOf(1L, 2L),
            toggleSelectAll(setOf(1L, 2L), emptySet<Long>()),
        )
    }

    @Test
    fun `已全选时再次全选变全不选`() {
        assertEquals(
            emptySet<Long>(),
            toggleSelectAll(setOf(1L, 2L), setOf(1L, 2L)),
        )
    }

    @Test
    fun `部分选中时全选补齐为全部`() {
        assertEquals(
            setOf(1L, 2L),
            toggleSelectAll(setOf(1L, 2L), setOf(1L)),
        )
    }

    @Test
    fun `无可删项时全选保持原选择`() {
        // 无可删项时不应被清空（保留用户当前的勾选）
        assertEquals(setOf(9L), toggleSelectAll(emptySet(), setOf(9L)))
        assertEquals(emptySet<Long>(), toggleSelectAll(emptySet(), emptySet<Long>()))
    }
}