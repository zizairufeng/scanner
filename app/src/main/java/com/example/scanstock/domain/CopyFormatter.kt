package com.example.scanstock.domain

/**
 * 「复制全部」的文本构造器。纯函数，无副作用，方便单测。
 */
object CopyFormatter {

    /** 行分隔符统一用 \n（不是 System.lineSeparator），保证跨系统粘贴结果一致 */
    private const val EOL = "\n"

    /**
     * 把结果列表渲染成可放进剪贴板的字符串。
     *
     * @param items  当前结果列表（顺序即屏幕顺序）
     * @param format 输出格式
     */
    fun format(items: List<ScanItem>, format: CopyFormat): String {
        if (items.isEmpty()) return ""
        return when (format) {
            CopyFormat.NUMBERED_TAB -> expand(items) { "${it.seq}\t${it.value}" }
            CopyFormat.PLAIN -> expand(items) { it.value }
            CopyFormat.CSV -> buildCsv(items)
        }
    }

    /**
     * 按数量展开成多行。
     *
     * 计数聚合模式下同一行可能代表多个（qty = 3），而「纯文本 / 序号+Tab」这两种格式
     * **没有数量列**——它们的约定就是「一行一个物品」。所以这里把 qty=3 的行
     * 重复输出 3 次，保证下游数行数得到的就是实际总数。
     *
     * 有数量列的格式（CSV / Excel）不展开，直接写数量，见 [buildCsv]。
     *
     * 拼接用 StringBuilder 而不是 flatMap + joinToString：后者会先建一个
     * 几千元素的临时列表，复制操作虽低频但列表可能很长，没必要付这个代价。
     */
    private fun expand(items: List<ScanItem>, render: (ScanItem) -> String): String {
        val sb = StringBuilder(items.size * 32)
        var first = true
        for (item in items) {
            val line = render(item)
            repeat(item.qty.coerceAtLeast(1)) {
                if (!first) sb.append(EOL)
                sb.append(line)
                first = false
            }
        }
        return sb.toString()
    }

    /** 单条复制：无论当前选了哪种格式，单条都只复制码值本身，符合「贴进搜索框」的直觉 */
    fun formatSingle(item: ScanItem): String = item.value

    private fun buildCsv(items: List<ScanItem>): String {
        val sb = StringBuilder(items.size * 48)
        // 表头：下游系统（WMS / ERP）导入时依赖列顺序。
        // 数量列是 v1.3 计数聚合新增的，放在「内容」之后、「时间」之前——
        // 时间放最后是既有约定（它最不像业务字段），插入新列时不破坏它。
        sb.append("序号,码制,内容,数量,时间").append(EOL)
        items.forEach { item ->
            sb.append(item.seq).append(',')
                .append(csvEscape(item.formatName)).append(',')
                .append(csvEscape(item.value)).append(',')
                .append(item.qty).append(',')
                .append(csvEscape(item.timeText))
                .append(EOL)
        }
        // 去掉结尾多余换行，避免粘贴后多出一个空行
        return sb.toString().trimEnd('\n')
    }

    /**
     * CSV 字段转义（RFC 4180）：
     * 含逗号 / 双引号 / 换行时整体加双引号，内部双引号翻倍。
     *
     * 注：这里不写 UTF-8 BOM。剪贴板里带 BOM 会在记事本/聊天窗口里显示成乱码字符；
     * 若下游 Excel 直接粘贴出现乱码，请在导入时选择 UTF-8 编码。
     */
    internal fun csvEscape(raw: String): String {
        val needQuote = raw.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needQuote) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }
}
