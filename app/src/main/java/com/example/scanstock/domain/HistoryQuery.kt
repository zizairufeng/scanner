package com.example.scanstock.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 历史记录的时间范围快捷选项。
 *
 * 顺序即界面上的显示顺序。默认 [WEEK]：盘点是按天干的活，
 * 「今天」太窄（昨天扫的经常还要查），「全部」太宽（翻不到重点）。
 */
enum class HistoryRange(val label: String) {
    TODAY("今天"),
    WEEK("7 天"),
    MONTH("30 天"),
    ALL("全部");

    /** 只要不是「全部」，界面就可以把结果按天分组显示 */
    val isBounded: Boolean get() = this != ALL
}

/**
 * 历史记录的查询规则（v1.8.9）。
 *
 * 抽成纯逻辑对象而不是写进存储类，理由与 [CodeAdmission] 一致：
 * 「哪些记录算命中」是最容易被改错的部分，放在 domain 层就能用纯 JVM 单测覆盖。
 *
 * ## 匹配口径
 *
 * - **子串匹配，忽略大小写**。不做前缀/全等匹配：盘点现场输入的是码的一部分
 *   （只记得后六位、只记得批次号），子串才是能用的那个口径。
 * - **空格分隔多个关键词 = 全部包含（AND）**。例如 `6901 0215` 只命中同时含这两段的码值。
 *   单个关键词时就是最普通的包含查询。
 * - 只在**码值**上匹配，不在码制名上匹配。用户搜 `EAN` 想找的是条码内容，
 *   而所有 EAN-13 的 `formatName` 都命中会得到一堆他并没有要找的东西。
 */
object HistoryQuery {

    /**
     * 把用户输入拆成关键词列表。
     *
     * 空串 / 全空白返回空列表——语义是「不加码值条件」，只按时间范围和来源过滤，
     * 这正好是「打开历史页先看到最近一批」的默认行为。
     */
    fun needles(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.trim().split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
    }

    /** 一条记录是否命中所有关键词。空关键词列表 = 恒命中 */
    fun matches(record: ScanRecord, needles: List<String>): Boolean {
        if (needles.isEmpty()) return true
        val haystack = record.value.lowercase(Locale.ROOT)
        for (needle in needles) {
            if (!haystack.contains(needle)) return false
        }
        return true
    }

    /**
     * 时间范围的左边界（含）。
     *
     * 「今天」= 本地零点；「7 天」= 六天前的零点（含今天共 7 个自然日）。
     * 按**自然日**而不是「now - 7×86400_000」算：盘点是按天对账的，
     * 用户说「7 天」指的是 7 个日历日，滚动 168 小时会把「上周同一天早上」切掉一半。
     */
    fun fromMillis(range: HistoryRange, nowMillis: Long): Long {
        if (range == HistoryRange.ALL) return 0L
        val daysBack = when (range) {
            HistoryRange.TODAY -> 0
            HistoryRange.WEEK -> 6
            HistoryRange.MONTH -> 29
            HistoryRange.ALL -> 0
        }
        return dayStartOf(nowMillis, daysBack)
    }

    /** 本地时区里「dayStartOf(now) 往前推 [daysBack] 天」的零点毫秒 */
    fun dayStartOf(millis: Long, daysBack: Int = 0): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = millis
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (daysBack != 0) c.add(Calendar.DAY_OF_MONTH, -daysBack)
        return c.timeInMillis
    }

    /**
     * 历史记录的时间戳文本。
     *
     * 与结果列表的 `HH:mm:ss.SSS` 不同：历史跨天，**必须带日期**，
     * 否则「昨天 14:03」和「今天 14:03」在搜索结果里长得一模一样。
     * 精确到秒即可（毫秒在这个场景里没有信息量，只是噪音）。
     */
    fun formatTimestamp(millis: Long): String =
        synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(millis)) }

    private val WHITESPACE = Regex("\\s+")

    private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
}
