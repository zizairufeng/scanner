package com.example.scanstock.domain

/**
 * 一条**扫描历史**记录（v1.8.9）。
 *
 * ## 与 [ScanItem] 的区别（别混用）
 *
 * - [ScanItem] 是**结果列表的一行**：有屏幕序号、会被删除、会被计数聚合合并、会被清空。
 *   它是「当前这次盘点还剩下什么」。
 * - [ScanRecord] 是**发生过的一次事件**：只追加、永不修改、永不删除（除非用户主动清空历史）。
 *   它是「这台机器上曾经扫到过什么」。
 *
 * 之所以必须是两份数据而不是沿用列表：列表天然会被「清空 / 删除误扫 / 计数聚合」改写，
 * 而历史账本的价值恰恰在于**这些改写都不会让它少了任何一条**。盘点现场最常问的两个问题——
 * 「这个码我刚才扫过没有」「上一批里是不是有这个码」——都要求历史是只增不改的。
 *
 * ## 为什么一条事件一行，而不是一个码聚合一行
 *
 * 聚合行会丢掉「什么时候扫的、扫了几次」这两个信息，而重复扫到同一个码恰恰是最需要留痕的事
 * （严格去重下它是**静默丢弃**的，屏幕上什么都没发生）。按事件存，代价只是每条几十字节，
 * 却能事后回答「这箱货我到底过了几遍」。
 *
 * @param timeMillis 事件发生的墙上时间（毫秒）。跨天分片按它算，而不是按写盘时刻——
 *                   否则 23:59:59 扫的那一条会被写进第二天的文件里
 * @param kind       这次事件的性质，见 [RecordKind]
 * @param formatId   ML Kit 原始 format 常量；[RecordKind.INC] / [RecordKind.DUP] 时由列表反查得到，
 *                   查不到为 -1（不影响按码值搜索）
 * @param formatName 人类可读码制名，查不到为空串
 * @param value      条码原始内容（与列表同一口径，不做任何裁剪）
 * @param session    本次运行（进程）的标识。用于「只看这一批」过滤——
 *                   用户清空列表后仍能查回刚才扫的那批，这是本功能最常用的入口
 * @param source     来自相机实时识别还是相册图片
 */
data class ScanRecord(
    val timeMillis: Long,
    val kind: RecordKind,
    val formatId: Int,
    val formatName: String,
    val value: String,
    val session: String,
    val source: RecordSource
)

/**
 * 一次扫描事件的性质。
 *
 * 枚举顺序即导出文件里「类型」列的可能取值，新增时只往后加，不要插在中间——
 * 旧版解析用的是 `valueOf`，改名会让历史文件里已有的行读不出来。
 */
enum class RecordKind(val label: String) {
    /** 新增了一行：这个码此前不在列表里 */
    NEW("新增"),

    /** 计数聚合模式下给已有行 +1（此时列表里没有新行） */
    INC("累加"),

    /** 严格去重模式下被丢弃的重复码。屏幕上没有任何变化，只有历史里留得下 */
    DUP("重复")
}

/** 这条记录来自哪条识别通路 */
enum class RecordSource(val label: String) {
    CAMERA("相机"),
    GALLERY("相册")
}
