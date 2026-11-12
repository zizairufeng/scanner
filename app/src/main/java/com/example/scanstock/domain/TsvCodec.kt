package com.example.scanstock.domain

/**
 * 制表符分隔文本的字段转义（v1.8.9）。
 *
 * ## 为什么要转义
 *
 * 条码值确实可能含控制字符：二维码里塞一个换行、一条 Code-128 编进制表符都不罕见。
 * 不转义的话，一条记录会在文件里裂成两行，从此整个文件读出来的都是错位的垃圾数据——
 * 这种损坏是**不可逆**的（原值已经拼不回去了）。
 *
 * ## 为什么是自己写而不是用 JSON / CSV 库
 *
 * 与 [com.example.scanstock.data.SessionStore] 同一套理由：`org.json` 在纯 JVM 单测里
 * 调用会抛 "not mocked"（等于这块逻辑没法验证），而 Gson / kotlinx.serialization
 * 要为两个内部文件引一整个依赖。这里的格式完全由我们自己定义、不对外，
 * 转义 + 单测覆盖就够了。
 *
 * 规则（与 SessionStore v1.6 起的实现逐字节一致，历史文件与旧会话快照共用同一套规则）：
 * `\` → `\\`、制表符 → `\t`、换行 → `\n`、回车 → `\r`。
 * 反斜杠必须**先**转义，否则后面的 `\t` 会被二次转义。
 */
object TsvCodec {

    /** 字段分隔符。**只**在写文件/解析时使用，不要拿去 split 用户输入 */
    const val SEP = '\t'

    fun escape(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("\t", "\\t")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /**
     * 反转义。遇到不认识的转义序列（`\x`）时保留 `x` 本身——
     * 这是宽松策略：宁可显示成一个奇怪的字符，也不要把整条记录判为损坏而丢掉。
     */
    fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch != '\\' || i + 1 >= raw.length) {
                sb.append(ch)
                i++
                continue
            }
            when (raw[i + 1]) {
                't' -> sb.append('\t')
                'n' -> sb.append('\n')
                'r' -> sb.append('\r')
                '\\' -> sb.append('\\')
                else -> sb.append(raw[i + 1])
            }
            i += 2
        }
        return sb.toString()
    }
}
