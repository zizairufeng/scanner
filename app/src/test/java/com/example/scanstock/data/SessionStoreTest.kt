package com.example.scanstock.data

import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.ScanItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 会话快照存储单测（F4 会话防丢）。
 *
 * SessionStore 只吃一个 [File] 目录、不依赖 Context，所以能在纯 JVM 上跑**真实的文件读写**，
 * 不需要 Robolectric。测的重点是「往返不失真」和「坏文件自愈」——
 * 这两件事一旦坏了，用户看到的就是「恢复出来的数据不对」或者「每次启动都弹恢复」。
 */
class SessionStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun store() = SessionStore(temp.root)

    private fun item(seq: Int, value: String, qty: Int = 1) = ScanItem(
        seq = seq,
        formatId = 1,
        formatName = "EAN-13",
        value = value,
        timeMillis = 1_700_000_000_000L + seq,
        timeText = "10:00:0$seq.000",
        qty = qty
    )

    @Test
    fun `保存后能原样读回`() {
        val s = store()
        val items = listOf(item(1, "6901234567892"), item(2, "6923450657713"))
        s.save(items, nextSeq = 3, mode = DedupeMode.STRICT, rule = CodeRule())

        val snap = s.load()
        checkNotNull(snap) { "应当能读回快照" }
        assertEquals(2, snap.items.size)
        assertEquals("6901234567892", snap.items[0].value)
        assertEquals("6923450657713", snap.items[1].value)
        assertEquals(3, snap.nextSeq)
        assertEquals(DedupeMode.STRICT, snap.mode)
        assertTrue(snap.savedAtMillis > 0)
    }

    @Test
    fun `数量字段能往返`() {
        val s = store()
        s.save(listOf(item(1, "BOX", qty = 7)), nextSeq = 2, mode = DedupeMode.COUNT, rule = CodeRule())
        val snap = s.load()
        checkNotNull(snap)
        assertEquals(7, snap.items[0].qty)
        assertEquals(DedupeMode.COUNT, snap.mode)
    }

    @Test
    fun `规则能往返`() {
        val s = store()
        val rule = CodeRule(enabled = true, prefix = "69", length = 13, regex = "^69\\d{11}$")
        s.save(listOf(item(1, "6901234567892")), nextSeq = 2, mode = DedupeMode.COUNT, rule = rule)

        val snap = s.load()
        checkNotNull(snap)
        assertTrue(snap.rule.enabled)
        assertEquals("69", snap.rule.prefix)
        assertEquals(13, snap.rule.length)
        assertEquals("^69\\d{11}$", snap.rule.regex)
    }

    @Test
    fun `条码值含分隔符和换行时不破坏文件结构`() {
        // 二维码里塞换行/制表符是真实存在的：不转义的话一行会裂成多行，整个文件就读不出来了
        val s = store()
        val tricky = "A\tB\nC\\D"
        s.save(listOf(item(1, tricky)), nextSeq = 2, mode = DedupeMode.STRICT, rule = CodeRule())
        assertEquals(tricky, s.load()!!.items[0].value)
    }

    @Test
    fun `恢复的序号不会与已有条目撞号`() {
        val s = store()
        // 故意写一个偏小的 nextSeq（比如旧版本留下的）：必须被抬到 maxSeq+1
        s.save(listOf(item(4, "A"), item(9, "B")), nextSeq = 5, mode = DedupeMode.STRICT, rule = CodeRule())
        assertEquals(10, s.load()!!.nextSeq)
    }

    @Test
    fun `空条目视为无快照`() {
        val s = store()
        s.save(emptyList(), nextSeq = 1, mode = DedupeMode.STRICT, rule = CodeRule())
        assertNull("空列表不该被当作可恢复的会话", s.load())
    }

    @Test
    fun `损坏的文件返回null并被删除`() {
        val dir = temp.root
        val s = store()
        s.save(listOf(item(1, "A")), nextSeq = 2, mode = DedupeMode.STRICT, rule = CodeRule())

        // 手工写坏：头一行不对
        File(dir, "session.tsv").writeText("GARBAGE\nxxx", Charsets.UTF_8)
        assertNull(s.load())
        assertFalse("坏文件必须被删掉，否则每次启动都走一遍异常路径", s.exists())

        // 再写一次，确认删除后还能正常工作
        s.save(listOf(item(1, "B")), nextSeq = 2, mode = DedupeMode.STRICT, rule = CodeRule())
        assertEquals("B", s.load()!!.items[0].value)
    }

    @Test
    fun `clear删除快照`() {
        val s = store()
        s.save(listOf(item(1, "A")), nextSeq = 2, mode = DedupeMode.STRICT, rule = CodeRule())
        assertTrue(s.exists())
        s.clear()
        assertTrue(!s.exists())
        assertNull(s.load())
    }

    @Test
    fun `反复保存不会残留临时文件`() {
        val s = store()
        repeat(3) {
            s.save(listOf(item(1, "A$it")), nextSeq = 2, mode = DedupeMode.STRICT, rule = CodeRule())
        }
        val leftovers = temp.root.listFiles()?.map { it.name } ?: emptyList()
        assertEquals("只应留下 session.tsv 一个文件，实际: $leftovers", listOf("session.tsv"), leftovers)
    }
}
