package com.example.scanstock.domain

/**
 * 历史记录的行编解码（v1.8.9）。
 *
 * ## 文件格式
 *
 * 每天一个文件，UTF-8，制表符分隔，第一行是版本头 [HEADER]：
 *
 * ```
 * SCANSTOCK-HISTORY-1
 * 1762912912345	NEW	256	EAN-13	6901234567892	mh3k2p1	0
 * ```
 *
 * 字段顺序固定：`时间戳 类型 码制ID 码制名 码值 会话 来源`。
 *
 * ## 为什么要写版本头
 *
 * 用户的数据要能跨版本活下去。以后若加字段，靠头一行就能判断「这是哪一版格式」，
 * 老文件按老规则读、新文件按新规则读，不至于一升级就把历史读成空。
 * 头部不认识 → 整个文件当成不存在（**只读不写**，绝不动它）。
 *
 * ## 为什么类型/来源存枚举名而不是序号
 *
 * 存 `NEW` 而不是 `0`：以后往枚举中间插一项，序号会让所有历史记录的语义集体错位，
 * 而名字不会。代价是每行多几个字节，可以忽略。
 */
object HistoryCodec {

    /** 版本头。改动字段结构时必须改成 -2，并让 [decode] 兼容旧版 */
    const val HEADER = "SCANSTOCK-HISTORY-1"

    /** 每行的字段数。解码时严格校验，少一个字段就判为损坏行丢弃 */
    const val FIELD_COUNT = 7

    fun encode(record: ScanRecord): String = buildString(64) {
        append(record.timeMillis)
        append(TsvCodec.SEP)
        append(record.kind.name)
        append(TsvCodec.SEP)
        append(record.formatId)
        append(TsvCodec.SEP)
        append(TsvCodec.escape(record.formatName))
        append(TsvCodec.SEP)
        append(TsvCodec.escape(record.value))
        append(TsvCodec.SEP)
        append(record.session)
        append(TsvCodec.SEP)
        append(record.source.name)
    }

    /**
     * 解析一行。
     *
     * 版本头、空行、字段数不对、时间戳或类型不可识别——一律返回 null。
     * **不抛异常**：历史文件是只增不改的，一行坏掉不该影响同文件的其它几千行。
     */
    fun decode(line: String): ScanRecord? {
        if (line.isEmpty() || line == HEADER) return null
        val parts = line.split(TsvCodec.SEP)
        if (parts.size != FIELD_COUNT) return null

        val time = parts[0].toLongOrNull() ?: return null
        if (time <= 0L) return null
        val kind = RecordKind.entries.firstOrNull { it.name == parts[1] } ?: return null
        val value = TsvCodec.unescape(parts[4])
        if (value.isEmpty()) return null
        val source = RecordSource.entries.firstOrNull { it.name == parts[6] }
            ?: RecordSource.CAMERA

        return ScanRecord(
            timeMillis = time,
            kind = kind,
            formatId = parts[2].toIntOrNull() ?: -1,
            formatName = TsvCodec.unescape(parts[3]),
            value = value,
            session = parts[5],
            source = source
        )
    }
}
