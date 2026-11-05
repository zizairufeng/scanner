package com.example.scanstock.data

import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.ScanItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 当前会话的本地快照存储（F4 会话防丢）。
 *
 * 为什么需要它：结果列表原本只活在 ViewModel 内存里。误触返回键、切别的应用后
 * 进程被系统回收、手滑清了后台——任何一种都会让盘了一半的数据**瞬间归零且不可恢复**。
 * 这是本 App 体验上最大的单点风险，比任何功能缺失都严重。
 *
 * 为什么用自制的 TSV 而不是 JSON：
 * - `org.json` 是 Android 提供的类，纯 JVM 单元测试里调用会直接抛
 *   "Method ... not mocked"，等于这块逻辑没法人机验证；
 * - Gson / kotlinx.serialization 要引依赖，为一个文件不值得；
 * - 这里的数据形状完全由我们自己定（不是对外协议），TSV 足够且能被单测覆盖。
 *
 * 线程：save/load 都是阻塞 IO，调用方必须在 IO 线程调用（ViewModel 里用 Dispatchers.IO）。
 *
 * @param dir 存储目录（App 私有 filesDir）。做成参数而不是吃 Context，
 *            是为了让纯 JVM 单测能传一个临时目录进来跑真实的读写。
 */
class SessionStore(private val dir: File) {

    private val file: File get() = File(dir, FILE_NAME)
    private val tmp: File get() = File(dir, "$FILE_NAME.tmp")

    /**
     * 写入快照。
     *
     * 先写临时文件再 rename：rename 在同一文件系统上是原子的，
     * 写到一半进程被杀不会留下半个损坏的快照（损坏会让下次启动直接丢数据）。
     */
    fun save(items: List<ScanItem>, nextSeq: Int, mode: DedupeMode, rule: CodeRule) {
        if (!dir.exists()) dir.mkdirs()
        val sb = StringBuilder(items.size * 64 + 128)
        sb.append(HEADER).append('\n')
        sb.append(mode.name).append(SEP)
            .append(if (rule.enabled) "1" else "0").append(SEP)
            .append(escape(rule.prefix)).append(SEP)
            .append(rule.length).append(SEP)
            .append(escape(rule.regex)).append(SEP)
            .append(nextSeq).append(SEP)
            .append(System.currentTimeMillis())
            .append('\n')
        for (item in items) {
            sb.append(item.seq).append(SEP)
                .append(item.formatId).append(SEP)
                .append(escape(item.formatName)).append(SEP)
                .append(escape(item.value)).append(SEP)
                .append(item.timeMillis).append(SEP)
                .append(item.qty)
                .append('\n')
        }
        val tmpFile = tmp
        tmpFile.writeText(sb.toString(), Charsets.UTF_8)
        // 原子替换。rename 失败（极罕见）时退化为复制，总比两个都坏好
        if (!tmpFile.renameTo(file)) {
            tmpFile.copyTo(file, overwrite = true)
            tmpFile.delete()
        }
    }

    /**
     * 读取快照。
     *
     * 任何异常（文件不存在、格式损坏、版本不认识、字段解析失败）都返回 null 并**删掉坏文件**，
     * 让下次启动是干净状态，而不是每次都卡在同一处。
     */
    fun load(): Snapshot? {
        val f = file
        if (!f.exists()) return null
        // 注意：坏文件必须在这里被删掉。之前的实现把「头一行不认识」这类情况写成
        // runCatching 里的 `return null`，那是**非局部返回**，会绕过 getOrElse 的删除分支——
        // 结果坏文件永远留在磁盘上，每次启动都白跑一遍解析。改成 parse 返回 null、
        // 由这里统一删除，两种失败路径就合并了。
        val snapshot = runCatching { parse(f.readLines(Charsets.UTF_8)) }.getOrNull()
        if (snapshot == null) {
            runCatching { f.delete() }
            return null
        }
        return snapshot
    }

    /** 纯解析，不碰磁盘。任何不认识/不完整的输入都返回 null */
    private fun parse(lines: List<String>): Snapshot? {
        if (lines.isEmpty() || lines[0].trim() != HEADER) return null

        val meta = lines.getOrNull(1)?.split(SEP) ?: return null
        if (meta.size < 7) return null
        val mode = runCatching { DedupeMode.valueOf(meta[0]) }.getOrDefault(DedupeMode.STRICT)
        val rule = CodeRule(
            enabled = meta[1] == "1",
            prefix = unescape(meta[2]),
            length = meta[3].toIntOrNull()?.coerceAtLeast(0) ?: 0,
            regex = unescape(meta[4])
        )
        val nextSeq = meta[5].toIntOrNull() ?: 1
        val savedAt = meta[6].toLongOrNull() ?: 0L

        val items = ArrayList<ScanItem>((lines.size - 2).coerceAtLeast(0))
        for (i in 2 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val p = line.split(SEP)
            if (p.size < 6) continue
            val value = unescape(p[3])
            if (value.isEmpty()) continue
            val millis = p[4].toLongOrNull() ?: 0L
            items += ScanItem(
                seq = p[0].toIntOrNull() ?: (i - 1),
                formatId = p[1].toIntOrNull() ?: -1,
                formatName = unescape(p[2]),
                value = value,
                timeMillis = millis,
                // 时间串是派生数据：恢复时按墙上时间重算，避免旧版本缺字段时显示成空
                timeText = formatTime(millis),
                qty = p[5].toIntOrNull()?.coerceAtLeast(1) ?: 1
            )
        }
        if (items.isEmpty()) return null

        return Snapshot(
            items = items,
            // 序号必须至少大于已有最大值，否则恢复后会与旧条目撞号
            nextSeq = nextSeq.coerceAtLeast(items.maxOf { it.seq } + 1),
            mode = mode,
            rule = rule,
            savedAtMillis = savedAt
        )
    }

    /** 删除快照。导出完成或用户主动清空 / 丢弃时调用 */
    fun clear() {
        runCatching { file.delete() }
        runCatching { tmp.delete() }
    }

    fun exists(): Boolean = file.exists()

    /** 一次会话快照 */
    data class Snapshot(
        val items: List<ScanItem>,
        val nextSeq: Int,
        val mode: DedupeMode,
        val rule: CodeRule,
        val savedAtMillis: Long
    )

    private companion object {
        const val FILE_NAME = "session.tsv"
        const val HEADER = "SCANSTOCK-SESSION-1"
        const val SEP = '\t'

        /**
         * 转义：字段里若出现分隔符、换行或反斜杠，必须先转义，否则一行会裂成多行，
         * 整个文件从此读不出来。条码值确实可能含这些字符（二维码里塞换行不罕见）。
         */
        fun escape(raw: String): String = raw
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

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

        /**
         * load() 只在 ViewModel 初始化的 IO 线程里跑一次，不存在并发，
         * 所以这里持有静态 SimpleDateFormat 是安全的（它本身不是线程安全的）。
         */
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

        fun formatTime(millis: Long): String =
            synchronized(TIME_FORMAT) { TIME_FORMAT.format(Date(millis)) }
    }
}
