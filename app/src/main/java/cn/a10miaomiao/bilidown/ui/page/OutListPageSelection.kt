package cn.a10miaomiao.bilidown.ui.page

import cn.a10miaomiao.bilidown.db.dao.OutRecord

/**
 * 判断一条已导出记录对应的源缓存是否可被勾选删除（多选删除原视频）。
 * 必须同时满足：
 *  1. 记录已导出成功（status == SUCCESS）；
 *  2. 记录有 id（Room 主键非空）；
 *  3. 源缓存目录此刻确实仍存在（sourceExistsMap 命中且为 true）。
 * 任一条件不满足则不可删除，避免对未导出/源已不存在的记录误操作。
 */
fun isCleanableRecord(
    record: OutRecord,
    sourceExistsMap: Map<Long, Boolean>,
): Boolean {
    if (record.status != OutRecord.STATUS_SUCCESS) return false
    val id = record.id ?: return false
    return sourceExistsMap[id] == true
}

/**
 * 从记录列表中筛出所有可删除原视频的记录。
 * 空值 / 无缓存记录等 null id 会被安全剔除。
 */
fun cleanableRecords(
    recordList: List<OutRecord>,
    sourceExistsMap: Map<Long, Boolean>,
): List<OutRecord> {
    return recordList.filter { isCleanableRecord(it, sourceExistsMap) }
}

/**
 * 计算"全选"后应选中的 id 集合：
 *  - 已选中全部可删除项 → 返回空集（全不选，即取消全选）；
 *  - 否则 → 选中所有可删除项的 id。
 * 不可删除的记录（未导出 / 源已不存在）永远不会被全选选中。
 */
fun toggleSelectAll(
    cleanableIds: Set<Long>,
    currentSelectedIds: Set<Long>,
): Set<Long> {
    if (cleanableIds.isEmpty()) {
        return currentSelectedIds
    }
    return if (currentSelectedIds.containsAll(cleanableIds)) {
        emptySet()
    } else {
        cleanableIds
    }
}