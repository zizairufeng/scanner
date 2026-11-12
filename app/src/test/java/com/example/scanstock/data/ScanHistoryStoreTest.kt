package com.example.scanstock.data

import com.example.scanstock.domain.HistoryCodec
import com.example.scanstock.domain.HistoryQuery
import com.example.scanstock.domain.RecordKind
import com.example.scanstock.domain.RecordSource
import com.example.scanstock.domain.ScanRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

/**
 * [ScanHistoryStore] 的真实读写测试（纯 JVM，真临时目录）。
 *
 * 这个类最该被验证的不是「能搜到」，而是**数据到底有没有落盘**：
 * 每个断言都尽量走真实文件，不做任何 mock。
 */
class ScanHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var dir: File
    private val errors = Collections.synchronizedList(mutableListOf<String>())
    private val opened = mutableListOf<ScanHistoryStore>()

    /** 用「今天本地 01:00」做基准，避免用例正好跨零点时今天/昨天判断抖动 */
    private val base: Long
        get() = HistoryQuery.dayStartOf(System.currentTimeMillis()) + 3_600_000L

    @Before
    fun setUp() {
        dir = File(tmp.root, "history")
    }

    @After
    fun tearDown() {
        opened.forEach { runCatching { it.close() } }
        opened.clear()
    }

    private fun store(): ScanHistoryStore =
        ScanHistoryStore(dir, onError = { message, _ -> errors += message }).also { opened += it }

    private fun rec(
        value: String,
        at: Long,
        kind: RecordKind = RecordKind.NEW,
        session: String = "s1",
        source: RecordSource = RecordSource.CAMERA
    ) = ScanRecord(at, kind, 256, "EAN-13", value, session, source)

    @Test
    fun `追加后立刻能搜到，且新的在前`() {
        val store = store()
        store.append(listOf(rec("6900000000001", base), rec("6900000000002", base + 1000)))
        assertTrue("应能排空队列", store.flush(5_000))

        val result = store.search("", 0L, Long.MAX_VALUE)
        assertEquals(2, result.records.size)
        assertEquals("最新的排在最前面", "6900000000002", result.records[0].value)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `数据真的在磁盘上（换一个新实例也能读到）`() {
        val first = store()
        first.append(listOf(rec("6901111111111", base)))
        first.flush(5_000)
        first.close()

        // 模拟一次重启：全新实例、只读同一个目录
        val second = store()
        val result = second.search("", 0L, Long.MAX_VALUE)
        assertEquals(1, result.records.size)
        assertEquals("6901111111111", result.records[0].value)
    }

    @Test
    fun `分片文件带版本头且只写一次`() {
        val store = store()
        store.append(listOf(rec("A", base), rec("B", base + 1)))
        store.flush(5_000)
        store.append(listOf(rec("C", base + 2)))
        store.flush(5_000)

        val shard = dir.listFiles()!!.single { it.name.startsWith(ScanHistoryStore.PREFIX) }
        val lines = shard.readLines()
        assertEquals("头一行必须是版本头", HistoryCodec.HEADER, lines[0])
        assertEquals("版本头只能出现一次", 1, lines.count { it == HistoryCodec.HEADER })
        assertEquals("1 行头 + 3 条记录", 4, lines.size)
    }

    @Test
    fun `跨天写进不同分片，按时间范围能只读其中一天`() {
        val store = store()
        val dayMillis = 24L * 60 * 60 * 1000
        val today = HistoryQuery.dayStartOf(base)
        store.append(
            listOf(
                rec("YESTERDAY", today - dayMillis + 3_600_000L),
                rec("TODAY", today + 3_600_000L)
            )
        )
        store.flush(5_000)

        val shards = dir.listFiles()!!.count { it.name.startsWith(ScanHistoryStore.PREFIX) }
        assertEquals("两个自然日应该有两个分片", 2, shards)

        val onlyToday = store.search("", today, Long.MAX_VALUE)
        assertEquals(1, onlyToday.records.size)
        assertEquals("TODAY", onlyToday.records[0].value)
    }

    @Test
    fun `只看本次运行能过滤掉别的会话`() {
        val store = store()
        store.append(
            listOf(
                rec("MINE", base, session = "session-a"),
                rec("OTHER", base + 1, session = "session-b")
            )
        )
        store.flush(5_000)

        val mine = store.search("", 0L, Long.MAX_VALUE, session = "session-a")
        assertEquals(1, mine.records.size)
        assertEquals("MINE", mine.records[0].value)
    }

    @Test
    fun `关键词与上限同时生效，超限标记为截断`() {
        val store = store()
        repeat(5) { index ->
            store.append(listOf(rec("PALLET-01-$index", base + index)))
        }
        store.flush(5_000)

        val all = store.search("pallet", 0L, Long.MAX_VALUE, limit = 3)
        assertEquals(3, all.records.size)
        assertTrue("超过上限应标记截断", all.truncated)

        val enough = store.search("PALLET-01-4", 0L, Long.MAX_VALUE, limit = 3)
        assertEquals(1, enough.records.size)
        assertFalse(enough.truncated)
    }

    @Test
    fun `统计口径与清空`() {
        val store = store()
        store.append(listOf(rec("A", base), rec("B", base + 1)))
        store.flush(5_000)

        val stats = store.stats()
        assertEquals(2L, stats.records)
        assertEquals(1, stats.days)
        assertTrue("应记下了占用字节数", stats.bytes > 0L)
        assertEquals(base, stats.firstMillis)

        assertTrue(store.clear())
        val after = store.stats()
        assertEquals(0L, after.records)
        assertEquals(0, after.days)
        assertEquals("清空后目录里不能再有分片", 0, dir.listFiles()?.count { it.name.startsWith(ScanHistoryStore.PREFIX) } ?: 0)
        assertTrue(store.search("", 0L, Long.MAX_VALUE).records.isEmpty())
    }

    @Test
    fun `含制表符与换行的码值不会把文件读坏`() {
        val store = store()
        val nasty = "A\tB\nC\\D"
        store.append(listOf(rec(nasty, base), rec("AFTER", base + 1)))
        store.flush(5_000)

        val result = store.search("", 0L, Long.MAX_VALUE)
        assertEquals(2, result.records.size)
        assertEquals("AFTER", result.records[0].value)
        assertEquals(nasty, result.records[1].value)
    }

    @Test
    fun `不同事件类型都能原样读回`() {
        val store = store()
        store.append(
            listOf(
                rec("X", base, kind = RecordKind.NEW),
                rec("X", base + 1, kind = RecordKind.INC),
                rec("X", base + 2, kind = RecordKind.DUP, source = RecordSource.GALLERY)
            )
        )
        store.flush(5_000)

        val result = store.search("X", 0L, Long.MAX_VALUE)
        assertEquals(3, result.records.size)
        // 新的在前：DUP → INC → NEW
        assertEquals(RecordKind.DUP, result.records[0].kind)
        assertEquals(RecordSource.GALLERY, result.records[0].source)
        assertEquals(RecordKind.INC, result.records[1].kind)
        assertEquals(RecordKind.NEW, result.records[2].kind)
    }
}
