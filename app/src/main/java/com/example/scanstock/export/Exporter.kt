package com.example.scanstock.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.scanstock.domain.CopyFormat
import com.example.scanstock.domain.CopyFormatter
import com.example.scanstock.domain.ScanItem
import com.example.scanstock.domain.ScanRecord
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把结果列表写成文件并给出可分享的 content:// Uri（F3 导出分享 + F11 xlsx）。
 *
 * 为什么走「写文件 + 系统分享」而不是继续只用剪贴板：几百条结果粘进微信/QQ 时
 * 经常掉格式、超长度，也没法归档或发给别人。写成文件后用
 * `ACTION_SEND` 交给系统分享面板，微信、QQ、邮件、网盘、蓝牙都能收，
 * 而且**不申请任何权限、不联网**——FileProvider 只授权这一次分享的单个 Uri。
 *
 * 文件放在 `cacheDir/exports/`：系统存储紧张时可自动回收，也不会污染用户的下载目录。
 * 每次导出前清空该目录，避免反复导出堆积一堆旧文件。
 */
object Exporter {

    /** 支持的导出类型 */
    enum class Kind(val ext: String, val mime: String, val label: String) {
        TXT("txt", "text/plain", "纯文本"),
        CSV("csv", "text/csv", "CSV"),
        XLSX(
            "xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "Excel"
        )
    }

    /** 导出结果 */
    data class ExportFile(
        val uri: Uri,
        val fileName: String,
        val mime: String,
        val sizeBytes: Long,
        val kind: Kind
    )

    /**
     * 导出。
     *
     * @throws java.io.IOException 写文件失败（磁盘满等），调用方需提示用户
     */
    fun export(context: Context, items: List<ScanItem>, kind: Kind): ExportFile {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // 清掉上一次的导出物：cacheDir 理论上可被系统回收，但自己先清更可控
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "ScanStock_$stamp.${kind.ext}")

        when (kind) {
            Kind.TXT -> file.writeText(CopyFormatter.format(items, CopyFormat.PLAIN), Charsets.UTF_8)
            Kind.CSV -> {
                // 与剪贴板不同：这里是**文件**，给 Excel 双击打开的场景加 UTF-8 BOM，
                // 否则中文列（码制、时间串里的中文）会显示成乱码。
                // 剪贴板不能加 BOM —— 粘进记事本/聊天窗口会多出一个乱码字符。
                file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    CopyFormatter.format(items, CopyFormat.CSV).toByteArray(Charsets.UTF_8))
            }
            Kind.XLSX -> file.writeBytes(XlsxWriter.write(SHEET_NAME, buildRows(items)))
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ExportFile(
            uri = uri,
            fileName = file.name,
            mime = kind.mime,
            sizeBytes = file.length(),
            kind = kind
        )
    }

    /** xlsx 的行数据：表头 + 每行。序号与数量写成数值型，Excel 里可直接求和 */
    private fun buildRows(items: List<ScanItem>): List<List<XlsxWriter.Cell>> {
        val rows = ArrayList<List<XlsxWriter.Cell>>(items.size + 1)
        rows += listOf(
            XlsxWriter.Cell.of("序号"),
            XlsxWriter.Cell.of("码制"),
            XlsxWriter.Cell.of("内容"),
            XlsxWriter.Cell.of("数量"),
            XlsxWriter.Cell.of("时间")
        )
        items.forEach { item ->
            rows += listOf(
                XlsxWriter.Cell.of(item.seq),
                XlsxWriter.Cell.of(item.formatName),
                XlsxWriter.Cell.of(item.value),
                XlsxWriter.Cell.of(item.qty),
                XlsxWriter.Cell.of(item.timeText)
            )
        }
        return rows
    }

    /** 附带一份「总条数 / 总数量」提示文本，分享时给对方看一眼就知道规模 */
    fun summary(items: List<ScanItem>): String {
        val rows = items.size
        val total = items.sumOf { it.qty }
        return if (total == rows) "共 $rows 条" else "共 $rows 条 / 数量合计 $total"
    }

    /**
     * 导出**扫描历史**（v1.8.9）。
     *
     * 不复用 [export] 的那套列：「序号 / 数量」在历史里没有意义
     * （历史是一条条事件，不是一行行货），而「时间 / 类型 / 来源」恰恰是它才有的信息。
     * 共用同一份缓存目录与 FileProvider 授权路径，只是列不同。
     *
     * @throws java.io.IOException 写文件失败（磁盘满等）
     */
    fun exportHistory(context: Context, records: List<ScanRecord>, kind: Kind): ExportFile {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        dir.listFiles()?.forEach { runCatching { it.delete() } }

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "ScanStock_history_$stamp.${kind.ext}")

        when (kind) {
            Kind.TXT -> file.writeText(buildHistoryText(records), Charsets.UTF_8)
            // 文件给 Excel 双击打开，加 UTF-8 BOM；剪贴板不加（会显示成乱码字符）
            Kind.CSV -> file.writeBytes(
                byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                    buildHistoryCsv(records).toByteArray(Charsets.UTF_8)
            )
            Kind.XLSX -> file.writeBytes(XlsxWriter.write(HISTORY_SHEET_NAME, buildHistoryRows(records)))
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return ExportFile(
            uri = uri,
            fileName = file.name,
            mime = kind.mime,
            sizeBytes = file.length(),
            kind = kind
        )
    }

    /** 纯文本：`时间<Tab>类型<Tab>内容`。保留了时间，否则导出去就对不上账 */
    private fun buildHistoryText(records: List<ScanRecord>): String {
        val sb = StringBuilder(records.size * 48)
        records.forEach { record ->
            sb.append(historyTime(record.timeMillis)).append('\t')
                .append(record.kind.label).append('\t')
                .append(record.value).append('\n')
        }
        return sb.toString()
    }

    private fun buildHistoryCsv(records: List<ScanRecord>): String {
        val sb = StringBuilder(records.size * 64)
        sb.append("时间,类型,码制,内容,来源\n")
        records.forEach { record ->
            sb.append(historyTime(record.timeMillis)).append(',')
                .append(CopyFormatter.csvEscape(record.kind.label)).append(',')
                .append(CopyFormatter.csvEscape(record.formatName)).append(',')
                .append(CopyFormatter.csvEscape(record.value)).append(',')
                .append(CopyFormatter.csvEscape(record.source.label))
                .append('\n')
        }
        return sb.toString()
    }

    private fun buildHistoryRows(records: List<ScanRecord>): List<List<XlsxWriter.Cell>> {
        val rows = ArrayList<List<XlsxWriter.Cell>>(records.size + 1)
        rows += listOf(
            XlsxWriter.Cell.of("时间"),
            XlsxWriter.Cell.of("类型"),
            XlsxWriter.Cell.of("码制"),
            XlsxWriter.Cell.of("内容"),
            XlsxWriter.Cell.of("来源")
        )
        records.forEach { record ->
            rows += listOf(
                XlsxWriter.Cell.of(historyTime(record.timeMillis)),
                XlsxWriter.Cell.of(record.kind.label),
                XlsxWriter.Cell.of(record.formatName),
                XlsxWriter.Cell.of(record.value),
                XlsxWriter.Cell.of(record.source.label)
            )
        }
        return rows
    }

    /** 历史跨天，时间必须带日期；精确到秒足够 */
    private fun historyTime(millis: Long): String =
        synchronized(HISTORY_TIME_FORMAT) { HISTORY_TIME_FORMAT.format(Date(millis)) }

    private val HISTORY_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private const val SHEET_NAME = "盘点结果"
    private const val HISTORY_SHEET_NAME = "扫描历史"
}
