package com.example.scanstock.export

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 极小 xlsx 写入器：手写 zip + XML，**零第三方依赖**（F11）。
 *
 * 为什么不用 Apache POI：POI 全套打进来要好几 MB，正好抵消上一轮把包从 22.5MB
 * 砍到 10.87MB 的成果。而 xlsx 本质上就是「一个 zip 包 + 几个 XML 文件」，
 * 我们只要「表头 + 若干行数据」这一丁点能力，手写完全够用。
 *
 * 实现取舍：
 * - 单元格用 **inlineStr**（`<c t="inlineStr"><is><t>…</t></is></c>`），
 *   这样不必生成 sharedStrings.xml，包体再小一圈。
 * - 数字列（序号、数量）写成数值型 `<c><v>3</v></c>`，Excel 里能直接求和。
 * - 不写 styles.xml：Excel / WPS / Google Sheets 都能打开无样式的最小工作簿。
 *
 * 输出顺序按 OOXML 惯例：先 [Content_Types].xml，再关系文件，最后具体部件。
 */
object XlsxWriter {

    /**
     * @param sheetName 工作表名（Excel 限制 ≤31 字符，且不含 \ / ? * [ ] :）
     * @param rows      二维表，第一行应当是表头
     * @return 完整的 .xlsx 字节流
     */
    fun write(sheetName: String, rows: List<List<Cell>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(zipEntry("[Content_Types].xml"))
            zip.write(contentTypesXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(zipEntry("_rels/.rels"))
            zip.write(rootRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(zipEntry("xl/workbook.xml"))
            zip.write(workbookXml(sanitizeSheetName(sheetName)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(zipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(workbookRelsXml().toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(zipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sheetXml(rows).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    /** 单元格：文本或数字。xlsx 里必须显式区分，否则 Excel 会把数字当文本 */
    sealed class Cell {
        data class Text(val value: String) : Cell()
        data class Number(val value: Long) : Cell()

        companion object {
            fun of(value: String): Cell = Text(value)
            fun of(value: Int): Cell = Number(value.toLong())
        }
    }

    // ───────────────────────── XML 片段 ─────────────────────────

    private fun contentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun rootRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbookXml(sheet: String): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="${escapeXml(sheet)}" sheetId="1" r:id="rId1"/></sheets>
</workbook>"""

    private fun workbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""

    private fun sheetXml(rows: List<List<Cell>>): String {
        val sb = StringBuilder(rows.size * 96 + 256)
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        sb.append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { rowIdx, row ->
            val r = rowIdx + 1
            sb.append("<row r=\"").append(r).append("\">")
            row.forEachIndexed { colIdx, cell ->
                sb.append("<c r=\"").append(columnName(colIdx)).append(r).append('"')
                when (cell) {
                    is Cell.Text -> {
                        sb.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                        sb.append(escapeXml(cell.value))
                        sb.append("</t></is></c>")
                    }
                    is Cell.Number -> {
                        sb.append("><v>").append(cell.value).append("</v></c>")
                    }
                }
            }
            sb.append("</row>")
        }
        sb.append("</sheetData></worksheet>")
        return sb.toString()
    }

    // ───────────────────────── 工具 ─────────────────────────

    /** 0→A, 25→Z, 26→AA。盘点导出最多几列，但写成通用形式避免以后加列时踩坑 */
    internal fun columnName(index: Int): String {
        require(index >= 0) { "列号不能为负" }
        var n = index
        val sb = StringBuilder(3)
        while (true) {
            sb.insert(0, ('A'.code + (n % 26)).toChar())
            n = n / 26 - 1
            if (n < 0) break
        }
        return sb.toString()
    }

    /** OOXML 里只有这 5 个字符必须转义；其余交给 UTF-8 原样写入 */
    private fun escapeXml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                // 控制字符（条码里若混入 \u0000 之类）会让 Excel 判定文件损坏
                else -> if (ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') {
                    sb.append(' ')
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    private fun sanitizeSheetName(raw: String): String {
        val cleaned = raw.replace("[\\\\/\\[\\]?*:]".toRegex(), "_").trim()
        return when {
            cleaned.isEmpty() -> "Sheet1"
            cleaned.length > 31 -> cleaned.substring(0, 31)
            else -> cleaned
        }
    }

    /**
     * 统一用 DEFLATED。
     *
     * 不显式设 CRC / size 也能写（ZipOutputStream 对 DEFLATED 会自动算），
     * 但如果误设成 STORED 又不给 size，写出来的 zip 打不开——这里固定 DEFLATED 避免踩这个坑。
     */
    private fun zipEntry(name: String): ZipEntry = ZipEntry(name).apply {
        method = ZipEntry.DEFLATED
    }
}
