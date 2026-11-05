package com.example.scanstock

import android.app.Application
import com.example.scanstock.logging.AppLog

/**
 * 唯一的 Application。
 *
 * ## 为什么需要它（v1.6 新增）
 *
 * 以前没有 Application 子类，一切都在 `MainActivity.onCreate` 里起。
 * 但崩溃报告这件事**必须抢在最早的时机**：如果闪退发生在第一个 Activity
 * 起来之前（比如某个三方库在静态初始化时抛异常），日志系统还没就绪，
 * 那次崩溃就永远不会被记录下来——而用户反馈的「打开就闪退」恰恰多属于这一类。
 *
 * [onCreate] 是进程启动后我们能拿到控制权的最早点，所以：
 *  1. 先建日志目录、写设备与版本信息；
 *  2. 立刻安装未捕获异常处理器；
 *  3. 之后才开始常规初始化。
 *
 * 这里做的所有事情都包了 runCatching —— 日志系统自身的问题绝不能变成
 * 新的崩溃源（那会把「查不到崩溃」变成「每次启动都崩」）。
 */
class ScanStockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            AppLog.init(this)
            // 安装顺序有意如此：init 先写一行「进程启动」，
            // 万一安装处理器之后马上就有异常，日志里能看到它发生在哪一步
            AppLog.installCrashHandler(this)
        }
    }
}
