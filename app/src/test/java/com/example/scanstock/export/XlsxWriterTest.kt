package com.example.scanstock.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * xlsx 写入器单测（F11）。
 *
 * 手写 OOXML 最大的风险是「写出来的文件 Excel 打不开」——而这在单测里没法直接用
 * Excel 验证。退而求其次，这里验证三件能验证的事：
 *   1. 它是一个合法的 zip，且包含 OOXML 必需的部件；
 *   2. sheet1.xml 里的行列引用与单元格类型正确（数值列必须是数值型，否则 Excel 里没法求和）；
 *   3. XML 转义正确，条码里的特殊字符不会把文件写坏。
 * 真正的「Excel 能否打开」由构建后的验收脚本用 Python openpyxl 复核。
 */
class XlsxWriterTest {

    @Test
    fun `列名换算正确`() {
        assertEquals("A", XlsxWriter.columnName(0))
        assertEquals("Z", XlsxWriter.columnName(25))
        assertEquals("AA", XlsxWriter.columnName(26))
        assertEquals("AB", XlsxWriter.columnName(27))
        assertEquals("AE", XlsxWriter.columnName(30))
    }

    @Test
    fun `产出的zip包含OOXML必需部件`() {
        val bytes = XlsxWriter.write("盘点", listOf(listOf(XlsxWriter.Cell.of("A"))))
        val names = zipEntries(bytes)
        listOf(
            "[Content_Types].xml",
            "_rels/.rels",
            "xl/workbook.xml",
            "xl/_rels/workbook.xml.rels",
            "xl/worksheets/sheet1.xml"
        ).forEach { required ->
            assertTrue("缺少部件 $required，实际: $names", required in names)
        }
    }

    @Test
    fun `数值单元格写成数值型_文本写成inlineStr`() {
        val bytes = XlsxWriter.write(
            "S",
            listOf(
                listOf(XlsxWriter.Cell.of("序号"), XlsxWriter.Cell.of("内容")),
                listOf(XlsxWriter.Cell.of(3), XlsxWriter.Cell.of("6901234567892"))
            )
        )
        val sheet = zipEntryText(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("数值单元格不该带 inlineStr，实际: $sheet", sheet.contains("<c r=\"A2\"><v>3</v></c>"))
        assertTrue("文本单元格必须是 inlineStr，实际: $sheet", sheet.contains("t=\"inlineStr\""))
        assertTrue(sheet.contains("6901234567892"))
    }

    @Test
    fun `行号从1开始且连续`() {
        val bytes = XlsxWriter.write(
            "S",
            listOf(
                listOf(XlsxWriter.Cell.of("h1")),
                listOf(XlsxWriter.Cell.of("r2")),
                listOf(XlsxWriter.Cell.of("r3"))
            )
        )
        val sheet = zipEntryText(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("<row r=\"1\">"))
        assertTrue(sheet.contains("<row r=\"2\">"))
        assertTrue(sheet.contains("<row r=\"3\">"))
    }

    @Test
    fun `特殊字符被正确转义`() {
        val bytes = XlsxWriter.write("S", listOf(listOf(XlsxWriter.Cell.of("A&B<C>D\"E'F"))))
        val sheet = zipEntryText(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("&amp;"))
        assertTrue(sheet.contains("&lt;"))
        assertTrue(sheet.contains("&gt;"))
        assertTrue("未转义的 < 会让 Excel 判定文件损坏", !sheet.contains("A&B<C>D"))
    }

    @Test
    fun `控制字符被替换掉`() {
        // 条码里若混入 \u0000，原样写进 XML 会让整份文件打不开
        val bytes = XlsxWriter.write("S", listOf(listOf(XlsxWriter.Cell.of("AB\u0000CD"))))
        val sheet = zipEntryText(bytes, "xl/worksheets/sheet1.xml")
        assertTrue("控制字符必须被替换", !sheet.contains('\u0000'))
    }

    @Test
    fun `非法工作表名被清洗`() {
        val bytes = XlsxWriter.write("a/b[c]d*e", listOf(listOf(XlsxWriter.Cell.of("x"))))
        val workbook = zipEntryText(bytes, "xl/workbook.xml")
        assertTrue("工作表名不能含 / [ ] *，实际: $workbook", !workbook.contains("a/b"))
        assertTrue(workbook.contains("a_b_c_d_e"))
    }

    // ───────────────────────── 辅助 ─────────────────────────

    private fun zipEntries(bytes: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                names += e.name
            }
        }
        return names
    }

    private fun zipEntryText(bytes: ByteArray, name: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val e: ZipEntry = zis.nextEntry ?: break
                if (e.name == name) return zis.readBytes().toString(Charsets.UTF_8)
            }
        }
        error("找不到部件 $name")
    }
}
