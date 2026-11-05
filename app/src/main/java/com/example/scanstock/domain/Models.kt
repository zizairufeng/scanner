package com.example.scanstock.domain

/**
 * 一条「已计入列表」的扫码记录。
 *
 * @param seq       屏幕上显示的序号。**恒等于它在列表里的行号（1..N）**：
 *                  删除中间项后会自动重排补齐，由 [SequenceRenumber] 统一维护。
 *                  （v1.5 及以前是不重排的稳定递增号，v1.6 按用户要求改成连续号——
 *                  理由是用户会拿屏幕上的序号去核对实物，断号会让人怀疑漏扫。
 *                  已导出的文件保存的是导出那一刻的编号，不会被事后重排改动。）
 * @param formatId  ML Kit 原始 format 常量（仅用于展示与调试）
 * @param formatName 人类可读码制名，如 "EAN-13"、"Code-128"
 * @param value     条码原始内容 rawValue（严格使用 ML Kit 原值，不做任何裁剪/补零）
 * @param timeMillis 计入时刻的墙上时间
 * @param timeText  预格式化时间串 "HH:mm:ss.SSS"。在创建时算好，
 *                  避免列表滚动重组时反复 new SimpleDateFormat。
 */
data class ScanItem(
    val seq: Int,
    val formatId: Int,
    val formatName: String,
    val value: String,
    val timeMillis: Long,
    val timeText: String,

    /**
     * 这一行的数量，缺省 1。
     *
     * 只有计数聚合模式（[DedupeMode.COUNT]）下重复扫到同一个码才会累加；
     * 其余模式恒为 1，不参与展示与导出判断。
     * 放在参数表最后并带默认值，既有构造点无需改动。
     */
    val qty: Int = 1
)

/**
 * 一次图像分析回调解析出的候选码。
 * 这里是纯 Kotlin 数据类，刻意不依赖 com.google.mlkit.vision.barcode 类型，
 * 使 domain / ui 层与具体识别库解耦（换库时不改 UI）。
 *
 * @param potentialOnly true 表示「疑似条码但未成功解码」（ML Kit 的 potential barcode），
 *                      只用于给用户提示「再靠近一点」，绝不计入结果列表。
 */
data class DetectedCode(
    val formatId: Int,
    val formatName: String,
    val value: String,
    val potentialOnly: Boolean = false
)

/**
 * 「复制全部」的输出格式。
 *
 * 枚举顺序 = 设置面板里的显示顺序，默认项排在第一位。
 */
enum class CopyFormat(val label: String, val hint: String) {
    /** 默认：每行只有码值，适合直接粘进聊天窗口 / 搜索框 / 任意文本框 */
    PLAIN("纯文本", "每行：内容"),

    /** 每行 `序号 + 制表符 + 内容`，可直接粘进 Excel 两列 */
    NUMBERED_TAB("序号+Tab", "每行：序号<Tab>内容"),

    /** CSV：带表头，供 WMS / ERP 等其他系统导入 */
    CSV("CSV", "表头+每行：序号,码制,内容,时间");
}
