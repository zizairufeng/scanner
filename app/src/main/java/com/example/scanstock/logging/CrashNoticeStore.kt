package com.example.scanstock.logging

import java.io.File

/**
 * 崩溃提示的「已读」状态（v1.8.10）。
 *
 * ## 为什么需要它
 *
 * 主屏的崩溃提示卡（`ScanScreen` 里的 `CrashNoticeCard`）原先只用一个内存布尔位
 * `crashNoticeDismissed` 记录「用户点了忽略」。但判断要不要显示用的却是**磁盘事实**：
 * `filesDir/logs/crash-*.txt` 是否存在。于是：
 *
 * - 点「忽略」→ 布尔位变 true，卡片隐藏；
 * - 进程重启（冷启动、或崩溃后被系统拉起）→ 新 ViewModel、布尔位回到 false，
 *   而崩溃报告文件还在（最多保留 5 份）→ 卡片必然再弹。
 *
 * 用户看到的就是「我明明忽略了，怎么还一直弹」。
 *
 * ## 这一版怎么做
 *
 * 把「哪些报告已经处理过」**按文件名持久化**到 `logs/seen-crash.txt`，两者分开：
 *
 * - 报告文件（`crash-*.txt`）：证据，绝不因为「忽略」而删除；
 * - 已读标记（`seen-crash.txt`）：UI 状态，可以随手重建。
 *
 * 显示条件改成「有没有**未读**报告」。这样：
 *
 * 1. 忽略旧报告 → 标记落盘，重启也不会再弹；
 * 2. 真的又崩了一次 → 产生新文件名的报告，不在已读集合里 → 仍然会提醒；
 * 3. 用户在设置里清空日志 → `AppLog.clearAll()` 连标记一起删，回到干净状态。
 *
 * 标记文件名以 `seen-` 开头，**不能**以 `crash-` 开头，否则会被
 * [AppLog.crashReports] 当成崩溃报告统计进去，造成自己触发自己的死循环。
 *
 * ## 为什么不用 SharedPreferences / DataStore
 *
 * 这个工程的 domain / data 层坚持纯 JVM 单测（不依赖 Android framework），
 * 而 `SharedPreferences` 在 `app/src/test` 下会抛 "not mocked"。
 * 存成一个小文件既能真实测「重启后还在」，也与 `SessionStore` / `ScanHistoryStore`
 * 的做法一致。写入走 tmp + rename，避免崩溃时留下半个标记文件。
 *
 * @param dir 日志目录（`filesDir/logs`）
 */
class CrashNoticeStore(private val dir: File) {

    /** 同一时刻可能有 UI 线程读、IO 线程写，全部串行 */
    private val lock = Any()

    /** 只返回还没被标记过的报告，保持传入顺序（AppLog 传进来的是最新的在前） */
    fun unseen(reports: List<File>): List<File> {
        if (reports.isEmpty()) return emptyList()
        val seen = readSeen()
        return reports.filter { it.name !in seen }
    }

    /**
     * 把这一批报告标记为已读。
     *
     * 做的是合并而不是覆盖：之前标记过的仍在，避免新报告一来就把旧状态冲掉。
     * 只存文件名（不存路径 / 时间戳），因为报告都在同一个目录、文件名本身就带时间。
     */
    fun markSeen(names: Collection<String>) {
        if (names.isEmpty()) return
        synchronized(lock) {
            val merged = LinkedHashSet(readSeen()).apply { addAll(names) }
            // 报告最多保留 5 份，标记留 32 条足够覆盖「反复崩溃 + 清空前的历史」；
            // 超过就砍最老的，不让这个小文件无限长
            val kept = if (merged.size > MAX_ENTRIES) {
                merged.toList().takeLast(MAX_ENTRIES)
            } else {
                merged.toList()
            }
            writeAtomically(markerFile, kept.joinToString("\n") + "\n")
        }
    }

    /** 清掉已读标记（设置里清空日志时由 AppLog.clearAll 直接删整个目录，这里是给测试与显式调用用的） */
    fun clear() {
        synchronized(lock) { runCatching { markerFile.delete() } }
    }

    private val markerFile: File get() = File(dir, MARKER_FILE)

    private fun readSeen(): Set<String> = runCatching {
        markerFile.readLines(Charsets.UTF_8)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }.getOrDefault(emptySet())

    /** tmp + rename：写到一半进程没了，最坏是旧的标记还在（会多提醒一次），不会读到半行 */
    private fun writeAtomically(target: File, content: String) {
        runCatching {
            if (!dir.exists() && !dir.mkdirs()) return
            val tmp = File(dir, "$MARKER_FILE.tmp")
            tmp.writeText(content, Charsets.UTF_8)
            if (target.exists() && !target.delete()) return
            if (!tmp.renameTo(target)) tmp.delete()
        }
    }

    companion object {
        /** 注意前缀：绝不能和 `AppLog.CRASH_PREFIX`（`crash-`）撞上 */
        const val MARKER_FILE = "seen-crash.txt"

        private const val MAX_ENTRIES = 32
    }
}
