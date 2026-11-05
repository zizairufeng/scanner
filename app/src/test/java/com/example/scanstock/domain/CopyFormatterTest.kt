package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CopyFormatter 单元测试。
 *
 * 只通过公开 API（format / formatSingle）验证，不直接碰 internal 的 csvEscape，
 * 这样测试与实现细节解耦，改内部函数签名不会连累测试。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 */
class CopyFormatterTest {

    private fun item(
        seq: Int,
        value: String,
        formatName: String = "EAN-13",
        timeText: String = "10:00:00.000",
        qty: Int = 1
    ) = ScanItem(
        seq = seq,
        formatId = 0,
        formatName = formatName,
        value = value,
        timeMillis = 0L,
        timeText = timeText,
        qty = qty
    )

    private val twoItems = listOf(
        item(1, "6901234567892"),
        item(2, "6923450657713")
    )

    // ───────────────────── 序号 + 制表符 ─────────────────────

    @Test
    fun `序号制表符格式为每行序号和内容`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.NUMBERED_TAB)
        assertEquals("1\t6901234567892\n2\t6923450657713", text)
    }

    @Test
    fun `行分隔符是LF而不是CRLF`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.NUMBERED_TAB)
        assertTrue("不能出现 \\r，否则某些 WMS 会判定为脏数据", !text.contains('\r'))
    }

    @Test
    fun `序号使用记录自身的seq而不是列表下标`() {
        // 删除过中间项的真实情形：seq 为 1、3、7，导出必须保持原样
        val list = listOf(item(1, "AAA"), item(3, "BBB"), item(7, "CCC"))
        val text = CopyFormatter.format(list, CopyFormat.NUMBERED_TAB)
        assertEquals("1\tAAA\n3\tBBB\n7\tCCC", text)
    }

    // ───────────────────── 纯文本 ─────────────────────

    @Test
    fun `纯文本格式只输出码值`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.PLAIN)
        assertEquals("6901234567892\n6923450657713", text)
    }

    @Test
    fun `纯文本格式保留重复码值`() {
        val list = listOf(item(1, "SAME"), item(2, "SAME"))
        assertEquals("SAME\nSAME", CopyFormatter.format(list, CopyFormat.PLAIN))
    }

    // ───────────────────── 计数聚合（v1.3 新增）─────────────────────

    @Test
    fun `纯文本按数量展开_行数等于实际件数`() {
        val list = listOf(item(1, "BOX", qty = 3), item(2, "CUP", qty = 1))
        assertEquals("BOX\nBOX\nBOX\nCUP", CopyFormatter.format(list, CopyFormat.PLAIN))
    }

    @Test
    fun `序号制表符格式按数量展开且序号不变`() {
        // 同一行的多次展开必须共用同一个 seq：它就是同一条记录
        val list = listOf(item(7, "BOX", qty = 2))
        assertEquals("7\tBOX\n7\tBOX", CopyFormatter.format(list, CopyFormat.NUMBERED_TAB))
    }

    @Test
    fun `数量异常小也不产生空输出`() {
        // qty 理论上不会 <1（SessionStore 读取时会 coerce），但防御一下总没错
        val list = listOf(item(1, "X", qty = 0))
        assertEquals("X", CopyFormatter.format(list, CopyFormat.PLAIN))
    }

    // ───────────────────── CSV ─────────────────────

    @Test
    fun `CSV 带表头且列顺序固定`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.CSV)
        val lines = text.split("\n")
        assertEquals("序号,码制,内容,数量,时间", lines[0])
        assertEquals("1,EAN-13,6901234567892,1,10:00:00.000", lines[1])
        assertEquals("2,EAN-13,6923450657713,1,10:00:00.000", lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun `CSV 有数量列且不展开行`() {
        // 与纯文本相反：CSV 有专门的数量列，一行就是一条记录，不能重复展开
        val list = listOf(item(1, "BOX", qty = 3))
        val lines = CopyFormatter.format(list, CopyFormat.CSV).split("\n")
        assertEquals(2, lines.size)
        assertEquals("1,EAN-13,BOX,3,10:00:00.000", lines[1])
    }

    @Test
    fun `CSV 结尾不残留多余换行`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.CSV)
        assertTrue("以换行结尾会在粘贴后多出一个空行", !text.endsWith("\n"))
    }

    @Test
    fun `CSV 对含逗号的字段加引号`() {
        val list = listOf(item(1, "{\"a\":1,\"b\":2}", formatName = "QR Code"))
        val text = CopyFormatter.format(list, CopyFormat.CSV)
        val row = text.split("\n")[1]
        assertTrue("含逗号的字段必须被双引号包裹，实际: $row", row.contains("\"{\"\"a\"\":1,\"\"b\"\":2}\""))
        // 5 列结构不能被内部逗号破坏：数一下顶层逗号
        assertEquals("顶层分隔符应只有 4 个逗号", 4, countTopLevelCommas(row))
    }

    @Test
    fun `CSV 对含引号的字段做双写转义`() {
        val list = listOf(item(1, "AB\"CD", formatName = "Code-128"))
        val text = CopyFormatter.format(list, CopyFormat.CSV)
        val row = text.split("\n")[1]
        assertTrue("内部双引号必须翻倍，实际: $row", row.contains("\"AB\"\"CD\""))
    }

    @Test
    fun `CSV 对含换行的字段整体加引号`() {
        val list = listOf(item(1, "LINE1\nLINE2", formatName = "QR Code"))
        val text = CopyFormatter.format(list, CopyFormat.CSV)
        assertTrue("含换行的字段必须整体被双引号包裹，实际: $text", text.contains("\"LINE1\nLINE2\""))
    }

    @Test
    fun `CSV 对普通字段不加多余引号`() {
        val text = CopyFormatter.format(twoItems, CopyFormat.CSV)
        assertTrue("普通字段不该被加引号", !text.contains("\"6901234567892\""))
    }

    // ───────────────────── 边界 ─────────────────────

    @Test
    fun `空列表返回空串`() {
        assertEquals("", CopyFormatter.format(emptyList(), CopyFormat.NUMBERED_TAB))
        assertEquals("", CopyFormatter.format(emptyList(), CopyFormat.PLAIN))
        assertEquals("", CopyFormatter.format(emptyList(), CopyFormat.CSV))
    }

    @Test
    fun `单条复制始终只给码值`() {
        assertEquals("6901234567892", CopyFormatter.formatSingle(twoItems[0]))
    }

    @Test
    fun `三种格式都覆盖到_枚举无遗漏`() {
        val items = listOf(item(1, "X"))
        CopyFormat.entries.forEach { f ->
            assertTrue("格式 ${f.label} 未产生输出", CopyFormatter.format(items, f).isNotEmpty())
        }
        assertEquals(3, CopyFormat.entries.size)
    }

    /** 数一行 CSV 里「不在引号内」的逗号个数 */
    private fun countTopLevelCommas(row: String): Int {
        var inQuote = false
        var count = 0
        var i = 0
        while (i < row.length) {
            val c = row[i]
            if (c == '"') {
                if (inQuote && i + 1 < row.length && row[i + 1] == '"') {
                    i++          // 转义的双引号，跳过
                } else {
                    inQuote = !inQuote
                }
            } else if (c == ',' && !inQuote) {
                count++
            }
            i++
        }
        return count
    }
}
