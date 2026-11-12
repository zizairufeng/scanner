package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HistoryQuery] 的匹配口径与时间范围口径 */
class HistoryQueryTest {

    private fun record(value: String) = ScanRecord(
        timeMillis = 1_762_912_912_345L,
        kind = RecordKind.NEW,
        formatId = 256,
        formatName = "EAN-13",
        value = value,
        session = "s",
        source = RecordSource.CAMERA
    )

    @Test
    fun `空输入表示不加码值条件`() {
        assertTrue(HistoryQuery.needles("").isEmpty())
        assertTrue(HistoryQuery.needles("   ").isEmpty())
        assertTrue(HistoryQuery.matches(record("6901234567892"), emptyList()))
    }

    @Test
    fun `子串匹配且忽略大小写`() {
        val needles = HistoryQuery.needles("abcd")
        assertTrue(HistoryQuery.matches(record("XX-ABCD-01"), needles))
        assertFalse(HistoryQuery.matches(record("XX-ABCE-01"), needles))
    }

    @Test
    fun `空格分隔的多个关键词必须全部包含`() {
        val needles = HistoryQuery.needles("6901  0215")
        assertTrue(HistoryQuery.matches(record("6901AA0215"), needles))
        assertFalse(HistoryQuery.matches(record("6901AA9999"), needles))
        assertFalse(HistoryQuery.matches(record("9999AA0215"), needles))
    }

    @Test
    fun `只在码值上匹配而不是码制名`() {
        // 用户搜 EAN 想找的是条码内容，不该把所有 EAN-13 都命中
        val needles = HistoryQuery.needles("EAN")
        assertFalse(HistoryQuery.matches(record("6901234567892"), needles))
        assertTrue(HistoryQuery.matches(record("ean-13-x"), needles))
    }

    @Test
    fun `今天从本地零点算起`() {
        val now = 1_762_912_912_345L
        val from = HistoryQuery.fromMillis(HistoryRange.TODAY, now)
        val dayStart = HistoryQuery.dayStartOf(now)
        assertEquals(dayStart, from)
        assertTrue("零点不能晚于当前时刻", from <= now)
    }

    @Test
    fun `7 天含今天共 7 个自然日`() {
        val now = 1_762_912_912_345L
        val today = HistoryQuery.dayStartOf(now)
        val weekAgo = HistoryQuery.fromMillis(HistoryRange.WEEK, now)
        // 相差 6 天（含今天就 7 个自然日）。用毫秒差比较，避开夏令时那一小时的偏差
        val diffDays = (today - weekAgo) / (24L * 60 * 60 * 1000)
        assertTrue("实际相差 ${diffDays} 天", diffDays in 5L..7L)
    }

    @Test
    fun `全部范围没有左边界`() {
        assertEquals(0L, HistoryQuery.fromMillis(HistoryRange.ALL, 1_762_912_912_345L))
    }

    @Test
    fun `时间戳文本带日期且精确到秒`() {
        val text = HistoryQuery.formatTimestamp(1_762_912_912_345L)
        // 形如 11-12 19:35:12
        assertTrue("实际：$text", Regex("""\d{2}-\d{2} \d{2}:\d{2}:\d{2}""").matches(text))
    }
}
