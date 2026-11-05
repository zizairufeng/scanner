package com.example.scanstock.domain

/**
 * 列表序号的连续性维护（v1.6）。
 *
 * ## 口径变更说明（重要）
 *
 * v1.5 及以前，[ScanItem.seq] 是**稳定递增**的：删除中间项后**不重排**，
 * 理由见当时 [ScanItem] 的注释——「保证已复制/已导出的数据与屏幕上的序号一一对应」。
 *
 * v1.6 按用户要求改成**删除后自动重排**：屏幕上的序号永远等于它在列表里的行号（1..N）。
 * 理由是盘点现场的实际用法：删掉误扫的那条之后，用户是**拿屏幕上的序号去核对实物**
 * 的，中间空一个号会让人怀疑「是不是漏了一条」。至于「已复制/已导出与序号对应」，
 * 那是**导出那一刻**的快照语义——导出文件里已经固化了当时的编号，重排不会追溯修改它。
 *
 * 这条不变量（`seq == index + 1`）现在由本类统一维护，所有会改动列表长度的入口
 * （删除、撤销、恢复会话、新增）都走这里，不允许各自算各自的。
 *
 * 拆成纯函数的目的：它可以在 JVM 单测里被穷举验证，而不需要跑 Android 仪器测试。
 */
object SequenceRenumber {

    /**
     * 把序号重排成 `1..N`（按列表当前顺序）。
     *
     * 已经连续时**原样返回同一个 list 实例**：识别回调每帧都会走到这里一次，
     * 不能每帧都白拷一遍列表。只有真的错位时才 map 一次。
     */
    fun renumber(items: List<ScanItem>): List<ScanItem> {
        if (items.isEmpty()) return items
        for (index in items.indices) {
            if (items[index].seq != index + 1) {
                return items.mapIndexed { i, item -> item.copy(seq = i + 1) }
            }
        }
        return items
    }

    /**
     * 下一个可用序号。
     *
     * 重排之后「下一个」必然是 `size + 1`，不再需要单独维护一个自增计数器——
     * 自增计数器与列表是两份状态，只要有一处漏改（撤销、恢复会话）就会撞号，
     * 而撞号的表现是 [androidx.compose.foundation.lazy.LazyColumn] 直接抛
     * `IllegalArgumentException: Key ... was already used`。派生出来就没有这个风险。
     */
    fun nextSeqOf(items: List<ScanItem>): Int = items.size + 1
}
