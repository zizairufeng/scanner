package com.example.scanstock.data

import com.example.scanstock.domain.HistoryCodec
import com.example.scanstock.domain.HistoryQuery
import com.example.scanstock.domain.ScanRecord
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 扫描历史账本（v1.8.9）：**每一次**扫码都实时追加落盘，永久保留，可按码值搜索。
 *
 * ## 与 [SessionStore] 的分工（两者互不影响）
 *
 * | | SessionStore | ScanHistoryStore |
 * | --- | --- | --- |
 * | 回答的问题 | 这次盘点还剩什么 | 这台机器扫到过什么 |
 * | 写入方式 | 整文件覆盖（写 tmp + rename） | 末尾追加 |
 * | 删除时机 | 用户清空 / 丢弃会话 | 只有用户主动清空历史 |
 * | 数据规模 | 一个会话（几十~几千条） | 无限（按天分片） |
 *
 * 分开是**刻意的**：会话快照是「可变的工作区」，历史是「只增的账本」。
 * 合成一份的话，「清空列表」这个高频动作就会顺手把账本也清掉——那正是要避免的事。
 *
 * ## 为什么是「追加 + 每天一个文件」而不是数据库
 *
 * 1. **崩溃安全模型最简单**：追加写只有「写完 / 没写完」两种状态，
 *    最坏情况是最后一行不完整（解码时判为坏行丢弃），前面几千行永远完好。
 *    任何「整文件重写」的方案在这一条上都输——写到一半断电就是全丢。
 * 2. **跨天分片让搜索有边界**：查「今天」只读今天那个文件，不会因为三个月的历史而变慢。
 * 3. **不引依赖**：这个工程的 release 构建是离线的（`--offline`），
 *    Room + KSP 的构建期产物根本拉不下来；而 SQLite 又会把这块逻辑变成
 *    「只能在真机上验」的黑盒，违背本项目「domain / data 层纯 JVM 单测」的做法。
 *
 * ## 不丢数据的实现细节（这是本类最重要的部分）
 *
 * - 写盘在**独立线程**上做，扫描回调只负责入队（微秒级），绝不阻塞识别主链路；
 * - 队列是**无界**的 `LinkedBlockingQueue`，不存在「满了就拒绝」这条路径——
 *   上一版闪退的根因就是向已关闭的线程池投递任务被 `AbortPolicy` 拒绝（见 v1.8.8），
 *   同样的坑不再踩第二次；
 * - 每批写完后 `fd.sync()`：**落盘之后才认为写成功**。进程被杀、闪退、
 *   甚至直接断电，已经 append 过的记录都在闪存里；
 * - [flush] 是一个 FIFO 屏障：调用它之后，此前所有 append 一定已写完并 sync 过。
 *   切后台（ON_STOP）与 ViewModel 销毁时都会调它，所以「扫完就切走」也不丢。
 *
 * 唯一会丢的窗口：`append` 入队之后、写线程真正执行之前（通常 < 5ms）。
 * 要彻底消掉它只能把写盘搬到识别回调线程上，那会让每帧多一次 fsync 抖动——
 * 对 20fps 的识别循环是不可接受的，权衡后保留这 5ms。
 *
 * @param dir     存储目录（App 私有 `filesDir/history`）。做成参数而不是吃 Context，
 *                是为了让纯 JVM 单测能传一个临时目录进来跑真实读写
 * @param now     时钟。可注入，便于测试构造跨天数据
 * @param onError 出错回调（磁盘满、权限异常等）。默认吞掉：历史写失败不该影响扫码主流程
 */
class ScanHistoryStore(
    private val dir: File,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val onError: (String, Throwable?) -> Unit = { _, _ -> }
) : Closeable {

    /**
     * 本次运行（进程）的标识。
     *
     * 用来支持「只看这一批」过滤：用户扫完一批、清空列表之后，
     * 最想查的往往就是「刚才那批」。进程重启即换新值（历史仍按码值可查）。
     */
    val session: String = now().toString(36)

    // ───────────────────────── 写线程 ─────────────────────────

    private sealed interface Task {
        class Append(val records: List<ScanRecord>) : Task
        class Flush(val latch: CountDownLatch) : Task
        class Clear(val latch: CountDownLatch) : Task
        object Close : Task
    }

    private val queue = LinkedBlockingQueue<Task>()
    private val writerLock = Any()

    /** 写线程独占的状态，外面一律不要碰 */
    private var writer: Thread? = null
    private var out: FileOutputStream? = null
    private var openDay: String? = null

    /** 已成功落盘的条数（含 fsync），只用于日志与统计 */
    private val writtenCount = AtomicLong(0L)

    @Volatile private var closed = false

    /** 统计缓存。历史是只增的，没必要每次打开面板都把全部文件读一遍 */
    @Volatile private var cachedStats: Stats? = null

    @Volatile private var statsDirty = true

    // ───────────────────────── 写入 ─────────────────────────

    /**
     * 追加一批记录。**立即返回**，真正的写盘在写线程上做。
     *
     * 调用方（识别回调）只付出一次入队的代价，不会因为磁盘慢而卡住预览。
     */
    fun append(records: List<ScanRecord>) {
        if (records.isEmpty() || closed) return
        ensureWriter()
        // 无界队列，offer 恒成功；用 put 只是为了让「万一将来改成有界」时仍然不丢
        queue.put(Task.Append(records))
    }

    /**
     * 等待此前所有 append 落盘 + sync 完成。
     *
     * @return true 表示确实排空了（false = 超时或写线程已关闭）
     */
    fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): Boolean {
        if (writer == null) return true    // 从没写过东西
        if (closed) return true
        val latch = CountDownLatch(1)
        queue.put(Task.Flush(latch))
        return await(latch, timeoutMs)
    }

    /** 已落盘条数。日志与「共 N 条」提示用 */
    fun writtenCount(): Long = writtenCount.get()

    /**
     * 清空全部历史。
     *
     * 必须经过写线程：正在被写的那条分片文件如果直接被 delete，
     * 打开着的 fd 会继续写进一个「已经不在目录里」的 inode，
     * 表现为「清空之后空间没释放、而且再也找不到这些数据」。先关流再删文件。
     */
    fun clear(): Boolean {
        if (writer == null) {
            deleteAllShards()
            return true
        }
        val latch = CountDownLatch(1)
        queue.put(Task.Clear(latch))
        return await(latch, CLEAR_TIMEOUT_MS)
    }

    override fun close() {
        if (closed) return
        closed = true
        val thread = writer ?: return
        queue.put(Task.Close)
        runCatching { thread.join(CLOSE_TIMEOUT_MS) }
    }

    private fun ensureWriter() {
        if (writer != null) return
        synchronized(writerLock) {
            if (writer != null) return
            writer = Thread(::drainLoop, "scan-history").apply {
                // 守护线程：它不该阻止进程退出。真正需要它写完的地方都显式调了 flush()
                isDaemon = true
                start()
            }
        }
    }

    private fun drainLoop() {
        try {
            while (true) {
                when (val task = queue.take()) {
                    is Task.Append -> {
                        // 合并紧随其后的追加：连扫时一帧一个任务，
                        // 合并后一次 write + 一次 fsync 就够，也不会把队列越积越长
                        val batch = ArrayList<ScanRecord>(task.records.size * 2)
                        batch += task.records
                        while (true) {
                            val next = queue.peek() ?: break
                            if (next !is Task.Append) break
                            val polled = queue.poll() as? Task.Append ?: break
                            batch += polled.records
                        }
                        writeBatch(batch)
                    }

                    is Task.Flush -> task.latch.countDown()

                    is Task.Clear -> {
                        closeStream()
                        deleteAllShards()
                        task.latch.countDown()
                    }

                    Task.Close -> {
                        closeStream()
                        return
                    }
                }
            }
        } catch (t: Throwable) {
            // 写线程死掉之后 append 仍会入队（不抛异常），只是不再落盘。
            // 这种情况必须让上层知道：宁可报错，也不能假装还在保存。
            onError("历史写线程异常退出", t)
            closeStream()
        }
    }

    private fun writeBatch(batch: List<ScanRecord>) {
        var wrote = 0
        for (record in batch) {
            val day = dayKey(record.timeMillis)
            val stream = openStream(day) ?: continue
            try {
                stream.write((HistoryCodec.encode(record) + "\n").toByteArray(Charsets.UTF_8))
                wrote++
            } catch (t: Throwable) {
                onError("写历史记录失败（已丢弃该条）", t)
                closeStream()     // 流可能已处于错误状态，关掉让下一条重开
            }
        }
        if (wrote > 0) {
            syncStream()
            writtenCount.addAndGet(wrote.toLong())
            statsDirty = true
        }
    }

    /** 打开某一天的分片文件（append 模式）。跨天时自动切换，旧流先关 */
    private fun openStream(day: String): FileOutputStream? {
        out?.let { if (openDay == day) return it }
        closeStream()
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                onError("无法创建历史目录：${dir.absolutePath}", null)
                return null
            }
            val file = File(dir, "$PREFIX$day$SUFFIX")
            val fresh = file.length() == 0L
            val stream = FileOutputStream(file, true)
            if (fresh) stream.write((HistoryCodec.HEADER + "\n").toByteArray(Charsets.UTF_8))
            out = stream
            openDay = day
            stream
        } catch (t: Throwable) {
            onError("打开历史分片失败：$day", t)
            null
        }
    }

    private fun syncStream() {
        val stream = out ?: return
        try {
            stream.fd.sync()
        } catch (t: Throwable) {
            // fsync 失败通常是磁盘满 / IO 错误。数据已经在页缓存里，
            // 进程被杀仍在，但断电可能丢——必须报出去
            onError("历史落盘同步（fsync）失败", t)
        }
    }

    private fun closeStream() {
        val stream = out ?: return
        out = null
        openDay = null
        runCatching { stream.fd.sync() }
        runCatching { stream.close() }
    }

    // ───────────────────────── 读取 / 搜索 ─────────────────────────

    /** 搜索结果 */
    data class SearchResult(
        val records: List<ScanRecord>,
        /** 实际读了多少个分片文件（用于给用户一个「扫了多少数据」的量级感） */
        val scannedDays: Int,
        /** 命中数超过 limit，只返回了最新的 limit 条 */
        val truncated: Boolean
    )

    /**
     * 搜索历史。
     *
     * 从**最新**的分片往前读，凑够 limit 就停——所以「最近发生的事」永远是最先返回的，
     * 老数据再大也不会拖慢常用查询。
     *
     * @param needle   用户输入。子串匹配、忽略大小写，空格分隔多个词表示「全部包含」
     * @param fromMillis 左闭区间
     * @param toMillis   右开区间（用 `Long.MAX_VALUE` 表示不限）
     * @param session  非空时只返回该会话的记录（「只看这一批」）
     * @param limit    最多返回多少条
     */
    fun search(
        needle: String,
        fromMillis: Long,
        toMillis: Long,
        session: String? = null,
        limit: Int = DEFAULT_LIMIT
    ): SearchResult {
        // 先把在途的写刷掉：用户刚扫完就打开历史页，不应该看不到最后那几条
        if (queue.isNotEmpty()) flush()
        val needles = HistoryQuery.needles(needle)
        val safeLimit = limit.coerceAtLeast(1)

        val hits = ArrayList<ScanRecord>(minOf(safeLimit, 256) + 1)
        var scannedDays = 0
        for (shard in shards()) {
            if (shard.endMillis <= fromMillis || shard.startMillis > toMillis) continue
            scannedDays++
            val dayHits = ArrayList<ScanRecord>(64)
            readRecords(shard.file) { record ->
                if (record.timeMillis < fromMillis || record.timeMillis >= toMillis) return@readRecords
                if (session != null && record.session != session) return@readRecords
                if (!HistoryQuery.matches(record, needles)) return@readRecords
                dayHits += record
            }
            // 文件内是按时间递增写的，倒着取才是「新的在前」
            for (i in dayHits.indices.reversed()) {
                hits += dayHits[i]
                if (hits.size > safeLimit) break
            }
            if (hits.size > safeLimit) break
        }

        val truncated = hits.size > safeLimit
        if (truncated) hits.subList(safeLimit, hits.size).clear()
        return SearchResult(records = hits, scannedDays = scannedDays, truncated = truncated)
    }

    /** 历史总览：多少天、多少条、占多少空间、最早与最近的记录时刻 */
    data class Stats(
        val days: Int,
        val records: Long,
        val bytes: Long,
        val firstMillis: Long,
        val lastMillis: Long
    ) {
        val isEmpty: Boolean get() = records == 0L
    }

    /** 统计（带缓存）。会读全部文件，所以只应在打开历史面板 / 清空之后调 */
    fun stats(): Stats {
        cachedStats?.let { if (!statsDirty) return it }
        val shards = shards()
        var records = 0L
        var bytes = 0L
        var first = 0L
        var last = 0L
        for (shard in shards) {
            bytes += shard.file.length()
            readRecords(shard.file) { record ->
                records++
                if (first == 0L || record.timeMillis < first) first = record.timeMillis
                if (record.timeMillis > last) last = record.timeMillis
            }
        }
        val computed = Stats(
            days = shards.size,
            records = records,
            bytes = bytes,
            firstMillis = first,
            lastMillis = last
        )
        cachedStats = computed
        statsDirty = false
        return computed
    }

    // ───────────────────────── 分片与文件 ─────────────────────────

    private data class Shard(val file: File, val day: String, val startMillis: Long, val endMillis: Long)

    /** 全部分片，**按时间倒序**（最新在前） */
    private fun shards(): List<Shard> {
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?: return emptyList()
        val result = ArrayList<Shard>(files.size)
        for (file in files) {
            val day = file.name.removePrefix(PREFIX).removeSuffix(SUFFIX)
            val start = parseDayStart(day) ?: continue
            // 分片边界只是搜索时的粗筛（用来跳过整个文件），跨夏令时差一小时也无妨：
            // 每条记录还会再做一次精确的范围判断
            result += Shard(file, day, start, start + DAY_MILLIS)
        }
        result.sortByDescending { it.startMillis }
        return result
    }

    private fun readRecords(file: File, sink: (ScanRecord) -> Unit) {
        try {
            file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    HistoryCodec.decode(line)?.let(sink)
                }
            }
        } catch (t: Throwable) {
            onError("读取历史分片失败：${file.name}", t)
        }
    }

    private fun deleteAllShards() {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
                ?.forEach { runCatching { it.delete() } }
        }.onFailure { onError("删除历史分片失败", it) }
        cachedStats = null
        statsDirty = true
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long): Boolean =
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)

    companion object {
        /** 分片文件名前缀 / 后缀。`scans-2026-11-12.tsv` */
        const val PREFIX = "scans-"
        const val SUFFIX = ".tsv"

        /** 单次搜索最多返回多少条。500 条在手机上已经滚不完，再多只是白读磁盘 */
        const val DEFAULT_LIMIT = 500

        private const val DEFAULT_FLUSH_TIMEOUT_MS = 2_000L
        private const val CLEAR_TIMEOUT_MS = 3_000L
        private const val CLOSE_TIMEOUT_MS = 1_000L
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

        /** 历史分片放在 `filesDir/history/`。**不**放 cacheDir：缓存会被系统清掉 */
        const val DIR_NAME = "history"

        /** 进程级单例。`@Volatile` + 双重检查：读多写一次，不必加锁 */
        @Volatile private var shared: ScanHistoryStore? = null

        /**
         * 进程内的唯一实例。
         *
         * 必须唯一：识别线程在写、历史面板在读，两个实例各自持有一个
         * `FileOutputStream` 往同一个文件追加，会互相踩掉对方的写入位置。
         *
         * @param filesDir 传 `context.filesDir`（不是 history 目录本身）
         */
        fun get(filesDir: File, onError: (String, Throwable?) -> Unit = { _, _ -> }): ScanHistoryStore =
            shared ?: synchronized(this) {
                shared ?: ScanHistoryStore(
                    dir = File(filesDir, DIR_NAME),
                    onError = onError
                ).also { shared = it }
            }

        /** 仅供单测：清掉进程级实例，避免上一个用例的写线程影响下一个 */
        internal fun resetSharedForTest() {
            runCatching { shared?.close() }
            shared = null
        }

        /** 本地时区的「哪一天」，用于分片命名。用 Calendar 而不是 java.time：minSdk 24 */
        private fun dayKey(millis: Long): String {
            val c = Calendar.getInstance()
            c.timeInMillis = millis
            return String.format(
                Locale.US,
                "%04d-%02d-%02d",
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH)
            )
        }

        /** `2026-11-12` → 当天本地零点毫秒。格式不对返回 null（不认识的分片直接跳过） */
        private fun parseDayStart(day: String): Long? {
            val parts = day.split('-')
            if (parts.size != 3) return null
            val year = parts[0].toIntOrNull() ?: return null
            val month = parts[1].toIntOrNull() ?: return null
            val date = parts[2].toIntOrNull() ?: return null
            if (year < 2000 || year > 2200 || month !in 1..12 || date !in 1..31) return null
            val c = Calendar.getInstance()
            c.clear()
            c.set(year, month - 1, date, 0, 0, 0)
            c.set(Calendar.MILLISECOND, 0)
            return c.timeInMillis
        }
    }
}
