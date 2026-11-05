package com.example.scanstock.logging

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** 崩溃报告最多保留几份（更早的自动删掉，用户只需要最近几次） */
    private const val MAX_CRASH_FILES = 5

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
        pruneOldCrashFiles(dir)
    }

    /** 安装全局未捕获异常处理器。必须在 Application.onCreate 里尽早调用 */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // 记录崩溃绝不能自己再抛：catch 住之后无论如何都要把控制权交还给系统默认处理器，
            // 否则进程会「卡住不退出」，用户看到的是黑屏而不是崩溃对话框。
            runCatching { writeCrashReport(app, thread, throwable) }
            runCatching { previous?.uncaughtException(thread, throwable) }
        }
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
        val dir = logDir ?: File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        val stamp = fileStampFmt.format(Date())
        val file = File(dir, "crash-$stamp-${thread.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}.txt")

        val sb = StringBuilder(16 * 1024)
        sb.appendLine("=== ScanStock 崩溃报告 ===")
        sb.appendLine("时间: ${Date()}")
        sb.appendLine("崩溃线程: ${thread.name} (id=${thread.id})")
        sb.appendLine(deviceHeader)
        sb.appendLine("内存: ${memorySummary()}")
        sb.appendLine("进程: pid=${Process.myPid()} 运行时长=${uptimeText()}")
        sb.appendLine()
        sb.appendLine("--- 崩溃线程堆栈 ---")
        thread.stackTrace.forEach { sb.appendLine("    at $it") }
        sb.appendLine()
        sb.appendLine("--- 异常 ---")
        sb.appendLine(Log.getStackTraceString(throwable))
        sb.appendLine()
        sb.appendLine("--- 崩溃前的运行日志（尾部 $CRASH_LOG_TAIL_LINES 行）---")
        sb.appendLine(tailOfAppLog(dir, CRASH_LOG_TAIL_LINES))
        runCatching { file.writeText(sb.toString(), Charsets.UTF_8) }

        // 崩溃报告本身也要在运行日志里留一条：用户下次打开看到横幅时，
        // 日志里能对上「哪一次打开对应哪一份报告」
        write("E", TAG, "已记录崩溃报告: ${file.name}")
        pruneOldCrashFiles(dir)
    }

    private fun tailOfAppLog(dir: File, lines: Int): String {
        val f = File(dir, APP_LOG)
        if (!f.exists()) return "(本次启动尚无运行日志)"
        return runCatching {
            // 只读尾部：先按行读全文再截断在几百 KB 上是可接受的（文件上限 512KB）
            f.readLines(Charsets.UTF_8).takeLast(lines).joinToString("\n")
        }.getOrDefault("(日志读取失败)")
    }

    private fun pruneOldCrashFiles(dir: File) {
        runCatching {
            val crashes = dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
                ?.sortedByDescending { it.name }
                ?: return
            crashes.drop(MAX_CRASH_FILES).forEach { it.delete() }
        }
    }

    // ───────────────────────────── 对外查询 ─────────────────────────────

    /** 现有的崩溃报告，最新的在前 */
    fun crashReports(context: Context): List<File> {
        val dir = File(context.filesDir, LOG_DIR)
        return dir.listFiles { f -> f.isFile && f.name.startsWith("crash-") }
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
        val sizeText = when {
            size >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", size / 1024.0 / 1024.0)
            size >= 1024 -> String.format(Locale.US, "%.1f KB", size / 1024.0)
            else -> "$size B"
        }
        return if (crashes == 0) sizeText else "$sizeText · 崩溃报告 $crashes 份"
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
        val appLog = File(srcDir, APP_LOG)
        val appLog1 = File(srcDir, "$APP_LOG.1")
        if (crashes.isEmpty() && !appLog.exists() && !appLog1.exists()) return null

        val out = File(outDir, "$BUNDLE_PREFIX${stampFmt.format(Date())}.txt")
        val sb = StringBuilder(32 * 1024)
        sb.appendLine("=== ScanStock 运行日志导出包 ===")
        sb.appendLine("导出时间: ${Date()}")
        sb.appendLine(deviceHeader)
        sb.appendLine("崩溃报告: ${crashes.size} 份")
        sb.appendLine()

        crashes.forEachIndexed { index, file ->
            sb.appendLine("──────── 崩溃报告 ${index + 1}/${crashes.size}：${file.name} ────────")
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

    private fun uptimeText(): String = runCatching {
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
