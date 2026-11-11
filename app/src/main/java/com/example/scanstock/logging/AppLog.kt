package com.example.scanstock.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.RejectedExecutionException

/**
 * 运行日志 + 崩溃报告（v1.6）。
 *
 * ## 为什么要有它
 *
 * 用户反馈「最近出现闪退」，但 APK 里原本**没有任何可回传的证据**：
 * 没有 crash 上报（本项目刻意不联网、不申请 INTERNET），
 * 系统「开发者选项-错误报告」对普通用户太绕，logcat 又需要数据线。
 * 结果就是只能靠猜——而崩溃 90% 的原因藏在那一次调用的参数里。
 *
 * 所以这一版的思路是：**把现场留在本机，让用户一键发回来。**
 *
 * ## 设计取舍
 *
 * 1. **写 filesDir，不写 cacheDir**。缓存目录会被系统在存储紧张时清掉，
 *    而崩溃报告恰恰最可能发生在「存储紧张 / OOM」的场景下——写 cache 等于
 *    最需要它的时候它已经不在了。
 * 2. **每行 append 落盘，不做内存缓冲**。扫描过程中最怕的是「日志还在缓冲区里，
 *    进程先没了」。代价是每次写有一次 open/close，所以日志只记**事件**，
 *    绝不逐帧记录——识别回调是 20fps，逐帧写会把主链路拖死。
 * 3. **崩溃报告里带一段运行日志尾部**。单看堆栈常常只能看到「框架层炸了」，
 *    带上崩溃前 200 行事件日志（最后一次相机绑定？刚切了分辨率？刚导出？）
 *    才能定位到真正触发它的那一步。
 * 4. **进程内不持有任何用户数据之外的东西**：日志里不写条码具体值（只写条数），
 *    避免把盘点内容随日志发出去。
 *
 * 线程：所有写操作在 [lock] 上串行；识别回调线程、主线程、崩溃线程都可能写。
 */
object AppLog {

    /** 日志目录名（filesDir 下） */
    const val LOG_DIR = "logs"

    /** 滚动日志文件名 */
    const val APP_LOG = "app.log"

    /** 导出的日志包文件名前缀 */
    const val BUNDLE_PREFIX = "ScanStock-logs-"

    /** 单个日志文件超过这个大小就滚动一次，避免无限增长占满存储 */
    private const val MAX_BYTES = 512L * 1024L

    /** 崩溃报告 / 已拦截报告各自最多保留几份（更早的自动删掉，用户只需要最近几次） */
    private const val MAX_CRASH_FILES = 5

    /** 崩溃报告文件名前缀 */
    const val CRASH_PREFIX = "crash-"

    /** 已拦截（非致命）异常报告的文件名前缀，v1.8.8 */
    const val SWALLOWED_PREFIX = "swallowed-"

    /** 判断异常类型时沿 cause 链最多走几层，防病态自引用 */
    private const val MAX_CAUSE_DEPTH = 16

    /** 崩溃报告里附带多少行运行日志 */
    private const val CRASH_LOG_TAIL_LINES = 200

    private val lock = Any()

    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val stampFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    private val fileStampFmt = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US)

    @Volatile
    private var logDir: File? = null

    /** 启动时一次性写入的设备/版本信息，崩溃报告与导出包里都要带 */
    @Volatile
    private var deviceHeader: String = ""

    // ───────────────────────────── 初始化 ─────────────────────────────

    /**
     * 初始化。[Application.onCreate] 里调用一次即可。
     *
     * 之所以要在这个最早的时机初始化：崩溃可能发生在**第一个 Activity 起来之前**
     * （静态初始化、Application 里的三方库初始化…），那时日志没就绪就等于没记。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        val dir = File(app.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        logDir = dir
        deviceHeader = buildDeviceHeader(app)
        i(TAG, "=== 进程启动 ===")
        deviceHeader.lineSequence().forEach { line -> if (line.isNotBlank()) i(TAG, line) }
        pruneOldReports(dir)
    }

    /** 安装全局未捕获异常处理器。必须在 Application.onCreate 里尽早调用 */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 「往已关闭的线程池投任务被拒」在**非主线程**上不算致命（v1.8.8）。
            // 它代表「有一件后台工作被丢弃」，那个线程本来也只是一次性任务线程——
            // 拦下来进程能继续用，见 [isDroppedWork]。
            //
            // 主线程不能这么处理：异常已经把 `Looper.loop()` 的栈整个展开，
            // 就算 handler 正常返回，主线程也回不去了 —— 表现出来是「界面冻住 / ANR」，
            // 比一个有对话框的闪退更难查，也更让人以为「没崩」。
            // 主线程这条路径靠的是上面三道防线（执行器包装、拆除顺序、released 闸门），
            // 而不是在最后一步假装没发生。
            if (!isMainThread(thread) && isDroppedWork(throwable)) {
                runCatching { writeSwallowedReport(app, thread, throwable) }
                return@setDefaultUncaughtExceptionHandler
            }
            // 记录崩溃绝不能自己再抛：catch 住之后无论如何都要把控制权交还给系统默认处理器，
            // 否则进程会「卡住不退出」，用户看到的是黑屏而不是崩溃对话框。
            runCatching { writeCrashReport(app, thread, throwable) }
            runCatching { previous?.uncaughtException(thread, throwable) }
        }
    }

    /** 崩溃线程是不是主线程。取不到 Looper（极端早期崩溃）时退回按线程名判断 */
    private fun isMainThread(thread: Thread): Boolean =
        runCatching { android.os.Looper.getMainLooper()?.thread === thread }
            .getOrDefault(thread.name == "main")

    /**
     * 这个异常是不是「被丢弃的后台工作」：沿 cause 链找 [RejectedExecutionException]。
     *
     * 只认这一种，别扩大范围：换成「所有 RuntimeException 都不崩」等于把真正的 bug
     * 变成「悄悄不工作」，那比闪退还难查。
     */
    private fun isDroppedWork(throwable: Throwable): Boolean {
        var t: Throwable? = throwable
        var depth = 0
        while (t != null && depth < MAX_CAUSE_DEPTH) {
            if (t is RejectedExecutionException) return true
            t = t.cause
            depth++
        }
        return false
    }

    // ───────────────────────────── 写日志 ─────────────────────────────

    fun i(tag: String, message: String) = write("I", tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) {
        write("W", tag, if (error == null) message else "$message | ${describe(error)}")
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        write("E", tag, if (error == null) message else "$message | ${describe(error)}")
    }

    /** 一行事件日志。所有入口最终都汇到这里，保证格式统一、线程安全、异常不外泄 */
    private fun write(level: String, tag: String, message: String) {
        val dir = logDir ?: return
        val line = "${timeFmt.format(Date())} $level/$tag [${Thread.currentThread().name}] $message\n"
        synchronized(lock) {
            runCatching {
                val file = File(dir, APP_LOG)
                rotateIfNeeded(file)
                file.appendText(line, Charsets.UTF_8)
            }
            // 写日志失败（磁盘满 / 权限）绝不能影响主链路，静默吞掉
        }
    }

    /** 超过上限就把当前日志滚成 app.log.1（旧的 .1 直接覆盖） */
    private fun rotateIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_BYTES) return
        val backup = File(file.parentFile, "$APP_LOG.1")
        runCatching { if (backup.exists()) backup.delete() }
        runCatching { file.renameTo(backup) }
    }

    // ───────────────────────────── 崩溃报告 ─────────────────────────────

    /**
     * 把崩溃现场写成一个独立的 txt。
     *
     * 内容顺序刻意是「最需要先看的放最前」：结论（版本/设备）→ 崩溃线程 →
     * 崩溃前的事件流 → 完整堆栈。
     */
    private fun writeCrashReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = ensureLogDir(context)
        val stamp = fileStampFmt.format(Date())
        val file = File(dir, "crash-$stamp-${safeThreadName(thread)}.txt")
        val body = reportBody(
            title = "=== ScanStock 崩溃报告 ===",
            note = null,
            thread = thread,
            throwable = throwable,
            dir = dir
        )
        runCatching { file.writeText(body, Charsets.UTF_8) }

        // 崩溃报告本身也要在运行日志里留一条：用户下次打开看到横幅时，
        // 日志里能对上「哪一次打开对应哪一份报告」
        write("E", TAG, "已记录崩溃报告: ${file.name}")
        pruneOldReports(dir)
    }

    /**
     * 记录一个「本可以杀掉进程、但被我们拦下来」的异常（v1.8.8）。
     *
     * 目前只用于 [RejectedExecutionException]：它意味着某个后台任务被丢弃，
     * 而不是进程状态损坏。这类异常留在**调用现场**（主线程 / 相机线程）上会直接闪退，
     * 而它往往来自库内部的时序竞争，端侧没有干净的改法。
     *
     * 单独存成 `swallowed-*.txt`，不混进崩溃报告计数里——它不是崩溃，
     * 但**必须留证据**：如果同一版本反复出现，说明有执行器被提前关了，那是真 bug。
     */
    private fun writeSwallowedReport(context: Context, thread: Thread, throwable: Throwable) {
        val dir = ensureLogDir(context)
        val stamp = fileStampFmt.format(Date())
        val file = File(dir, "swallowed-$stamp-${safeThreadName(thread)}.txt")
        val body = reportBody(
            title = "=== ScanStock 已拦截异常（进程未退出）===",
            note = "判定：线程池已关闭后仍有任务被提交，属于「一件后台工作被丢弃」，" +
                "不是进程状态损坏，因此只记录、不退出。若同一版本反复出现，需要排查执行器被提前关闭的位置。",
            thread = thread,
            throwable = throwable,
            dir = dir
        )
        runCatching { file.writeText(body, Charsets.UTF_8) }
        write("W", TAG, "已拦截非致命异常并记录: ${file.name}")
        pruneOldReports(dir)
    }

    private fun ensureLogDir(context: Context): File =
        logDir ?: File(context.filesDir, LOG_DIR).also { it.mkdirs() }

    private fun safeThreadName(thread: Thread): String =
        thread.name.replace(Regex("[^A-Za-z0-9_-]"), "_")

    /** 崩溃报告 / 已拦截异常报告的公共正文 */
    private fun reportBody(
        title: String,
        note: String?,
        thread: Thread,
        throwable: Throwable,
        dir: File
    ): String = buildString(16 * 1024) {
        appendLine(title)
        appendLine("时间: ${Date()}")
        appendLine("崩溃线程: ${thread.name} (id=${thread.id})")
        appendLine(deviceHeader)
        appendLine("内存: ${memorySummary()}")
        appendLine("进程: pid=${Process.myPid()} 本进程已运行=${uptimeText()}")
        // 这两个数以前混在一起：`SystemClock.elapsedRealtime()` 是**设备开机时长**，
        // 不是进程运行时长。1.8.7 的崩溃报告里写着「运行时长=195151s」（54 小时），
        // 而同一次启动的日志显示进程只活了 81 秒——那个误导让排查多绕了一圈。
        appendLine("设备已开机=${deviceUptimeText()}")
        if (note != null) {
            appendLine()
            appendLine("--- 说明 ---")
            appendLine(note)
        }
        appendLine()
        appendLine("--- 崩溃线程堆栈 ---")
        thread.stackTrace.forEach { appendLine("    at $it") }
        appendLine()
        appendLine("--- 异常 ---")
        appendLine(Log.getStackTraceString(throwable))
        appendLine()
        appendLine("--- 崩溃前的运行日志（尾部 $CRASH_LOG_TAIL_LINES 行）---")
        appendLine(tailOfAppLog(dir, CRASH_LOG_TAIL_LINES))
    }

    private fun tailOfAppLog(dir: File, lines: Int): String {
        val f = File(dir, APP_LOG)
        if (!f.exists()) return "(本次启动尚无运行日志)"
        return runCatching {
            // 只读尾部：先按行读全文再截断在几百 KB 上是可接受的（文件上限 512KB）
            f.readLines(Charsets.UTF_8).takeLast(lines).joinToString("\n")
        }.getOrDefault("(日志读取失败)")
    }

    /** 按类型分别滚动清理：崩溃报告与已拦截报告各留最近 [MAX_CRASH_FILES] 份 */
    private fun pruneOldReports(dir: File) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.startsWith(CRASH_PREFIX) }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
            dir.listFiles { f -> f.isFile && f.name.startsWith(SWALLOWED_PREFIX) }
                ?.sortedByDescending { it.name }
                ?.drop(MAX_CRASH_FILES)
                ?.forEach { it.delete() }
        }
    }

    // ───────────────────────────── 对外查询 ─────────────────────────────

    /** 现有的崩溃报告，最新的在前 */
    fun crashReports(context: Context): List<File> =
        reportsWithPrefix(context, CRASH_PREFIX)

    /** 被拦截下来、没有导致闪退的异常记录（v1.8.8），最新的在前 */
    fun swallowedReports(context: Context): List<File> =
        reportsWithPrefix(context, SWALLOWED_PREFIX)

    private fun reportsWithPrefix(context: Context, prefix: String): List<File> {
        val dir = File(context.filesDir, LOG_DIR)
        return dir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /** 一行日志概况，设置面板里显示用，如「12.3 KB · 崩溃报告 2 份」 */
    fun summary(context: Context): String {
        val dir = File(context.filesDir, LOG_DIR)
        val appLog = File(dir, APP_LOG)
        val size = (if (appLog.exists()) appLog.length() else 0L) +
            (File(dir, "$APP_LOG.1").takeIf { it.exists() }?.length() ?: 0L)
        val crashes = crashReports(context).size
        val swallowed = swallowedReports(context).size
        val sizeText = when {
            size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
            size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
            else -> "$size B"
        }
        return buildString {
            append(sizeText)
            if (crashes > 0) append(" · 崩溃报告 $crashes 份")
            if (swallowed > 0) append(" · 已拦截 $swallowed 次")
        }
    }

    /**
     * 把运行日志 + 全部崩溃报告合并成**一个** txt 放到 cacheDir，
     * 交给系统分享面板发出去。
     *
     * 为什么合并成一个文件而不是打包 zip：用户要在微信里直接点开看，
     * 或者原样发给开发者；txt 双击就能读，zip 还要先解压。
     *
     * @return 可分享的文件；连运行日志都还没有时返回 null
     */
    fun exportBundle(context: Context): File? {
        val srcDir = File(context.filesDir, LOG_DIR)
        val outDir = File(context.cacheDir, LOG_DIR).apply { mkdirs() }
        // 清掉上一次导出的包，避免堆一堆
        outDir.listFiles()?.forEach { runCatching { it.delete() } }

        val crashes = crashReports(context)
        val swallowed = swallowedReports(context)
        val appLog = File(srcDir, APP_LOG)
        val appLog1 = File(srcDir, "$APP_LOG.1")
        if (crashes.isEmpty() && swallowed.isEmpty() && !appLog.exists() && !appLog1.exists()) {
            return null
        }

        val out = File(outDir, "$BUNDLE_PREFIX${stampFmt.format(Date())}.txt")
        val sb = StringBuilder(32 * 1024)
        sb.appendLine("=== ScanStock 运行日志导出包 ===")
        sb.appendLine("导出时间: ${Date()}")
        sb.appendLine(deviceHeader)
        sb.appendLine("崩溃报告: ${crashes.size} 份 · 已拦截异常: ${swallowed.size} 份")
        sb.appendLine()

        crashes.forEachIndexed { index, file ->
            sb.appendLine("──────── 崩溃报告 ${index + 1}/${crashes.size}：${file.name} ────────")
            sb.appendLine(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("(读取失败)"))
            sb.appendLine()
        }

        // 已拦截的异常单独一段：它们不导致闪退，但往往是「另一个闪退」的先行指标
        swallowed.forEachIndexed { index, file ->
            sb.appendLine(
                "──────── 已拦截异常 ${index + 1}/${swallowed.size}：${file.name} ────────"
            )
            sb.appendLine(runCatching { file.readText(Charsets.UTF_8) }.getOrDefault("(读取失败)"))
            sb.appendLine()
        }

        sb.appendLine("──────── 运行日志 app.log.1（较早）────────")
        sb.appendLine(runCatching { if (appLog1.exists()) appLog1.readText(Charsets.UTF_8) else "(无)" }
            .getOrDefault("(读取失败)"))
        sb.appendLine("──────── 运行日志 app.log（最近）────────")
        sb.appendLine(runCatching { if (appLog.exists()) appLog.readText(Charsets.UTF_8) else "(无)" }
            .getOrDefault("(读取失败)"))

        out.writeText(sb.toString(), Charsets.UTF_8)
        return out
    }

    /** 清空所有日志与崩溃报告（用户在设置里主动清理，或分享成功后） */
    fun clearAll(context: Context) {
        val dir = File(context.filesDir, LOG_DIR)
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        i(TAG, "日志已清空")
    }

    // ───────────────────────────── 工具 ─────────────────────────────

    private const val TAG = "AppLog"

    private fun describe(error: Throwable): String =
        "${error.javaClass.name}: ${error.message ?: "(no message)"}"

    private fun memorySummary(): String = runCatching {
        val runtime = Runtime.getRuntime()
        val max = runtime.maxMemory() / 1024 / 1024
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        "heap used=${used}MB max=${max}MB"
    }.getOrDefault("(unknown)")

    /**
     * 本进程已运行时长。
     *
     * 口径修正（v1.8.8）：以前直接用 `SystemClock.elapsedRealtime()`，那是**设备开机时长**，
     * 而不是进程运行时长。1.8.7 的崩溃报告因此写着「运行时长=195151s」，同一次启动的
     * 运行日志却显示进程只活了 81 秒——两者差 2000 倍，排查时白白多绕一圈。
     */
    private fun uptimeText(): String = runCatching {
        val sinceProcessStart = android.os.SystemClock.elapsedRealtime() - Process.getStartUptimeMillis()
        "${sinceProcessStart / 1000}s"
    }.getOrDefault("(unknown)")

    /** 设备开机时长（与进程时长分开记，是判断「是不是系统级问题」的关键区分） */
    private fun deviceUptimeText(): String = runCatching {
        val ms = android.os.SystemClock.elapsedRealtime()
        "${ms / 1000}s"
    }.getOrDefault("(unknown)")

    /**
     * 设备与版本信息。
     *
     * 特意带上 ABI 与厂商：闪退里相当一部分是「特定机型 / 特定 ABI 的 native 库问题」，
     * 没有这两项就只能瞎猜。
     */
    private fun buildDeviceHeader(context: Context): String {
        val versionName = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName} (versionCode ${info.longVersionCode})"
        }.getOrDefault("(unknown)")
        val abis = runCatching { Build.SUPPORTED_ABIS.joinToString(",") }.getOrDefault("(unknown)")
        return buildString {
            appendLine("应用版本: $versionName")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("ABI: $abis")
            appendLine("包名: ${context.packageName}")
        }.trimEnd()
    }
}
