package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 序号重排的不变量测试（v1.6）。
 *
 * 这里要守住的核心不变量只有一条：**列表里第 i 个条目的 seq 恒等于 i + 1**。
 * 它一旦被破坏，轻则屏幕上出现「1 3 4」这种断号，重则 LazyColumn 的 key 重复直接崩溃。
 */
class SequenceRenumberTest {

    private fun item(seq: Int, value: String = "V$seq") = ScanItem(
        seq = seq,
        formatId = 0,
        formatName = "EAN-13",
        value = value,
        timeMillis = 0L,
        timeText = "00:00:00.000"
    )

    @Test
    fun `已经连续时原样返回同一个实例`() {
        val items = listOf(item(1), item(2), item(3))
        // 同一实例：识别回调每帧都会调一次，不能白拷列表
        assertSame(items, SequenceRenumber.renumber(items))
    }

    @Test
    fun `中间断号时补齐`() {
        val items = listOf(item(1), item(3), item(4))
        val result = SequenceRenumber.renumber(items)
        assertEquals(listOf(1, 2, 3), result.map { it.seq })
    }

    @Test
    fun `重排保留原顺序与内容`() {
        val items = listOf(item(5, "A"), item(9, "B"), item(7, "C"))
        val result = SequenceRenumber.renumber(items)
        assertEquals(listOf("A", "B", "C"), result.map { it.value })
        assertEquals(listOf(1, 2, 3), result.map { it.seq })
    }

    @Test
    fun `重排保留数量字段`() {
        val items = listOf(
            item(3).copy(qty = 7),
            item(8).copy(qty = 2)
        )
        val result = SequenceRenumber.renumber(items)
        assertEquals(listOf(7, 2), result.map { it.qty })
    }

    @Test
    fun `空列表安全`() {
        assertSame(emptyList<ScanItem>(), SequenceRenumber.renumber(emptyList()))
    }

    @Test
    fun `下一个序号等于size加一`() {
        assertEquals(1, SequenceRenumber.nextSeqOf(emptyList()))
        assertEquals(4, SequenceRenumber.nextSeqOf(listOf(item(1), item(2), item(3))))
    }

    @Test
    fun `删除任意一条后序号仍连续`() {
        // 模拟「扫了 5 条 → 删掉第 3 条 → 再删掉现在的第 1 条」
        var items = (1..5).map { item(it) }
        items = SequenceRenumber.renumber(items.filterIndexed { i, _ -> i != 2 })
        assertEquals(listOf(1, 2, 3, 4), items.map { it.seq })
        assertEquals(listOf("V1", "V2", "V4", "V5"), items.map { it.value })

        items = SequenceRenumber.renumber(items.filterIndexed { i, _ -> i != 0 })
        assertEquals(listOf(1, 2, 3), items.map { it.seq })
        assertEquals(listOf("V2", "V4", "V5"), items.map { it.value })
    }
}
