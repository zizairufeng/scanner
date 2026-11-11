package com.example.scanstock.util

import com.example.scanstock.logging.AppLog
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

/**
 * 包一层「往已关闭的线程池投任务不炸」的执行器（v1.8.8）。
 *
 * ## 为什么需要它
 *
 * 1.8.7 的一次闪退现场（OnePlus PJX110 / Android 16，崩溃报告 crash-2026-11-11_091819-main.txt）：
 *
 * ```
 * java.util.concurrent.RejectedExecutionException: Task  rejected from
 *     java.util.concurrent.ThreadPoolExecutor@7d37c3[Terminated, pool size = 0,
 *     active threads = 0, queued tasks = 0, completed tasks = 661]
 *     at java.util.concurrent.Executors$DelegatedExecutorService.execute
 *         ← MainActivity 主线程，进程直接被系统杀掉
 * ```
 *
 * `Executors.newSingleThreadExecutor()` 的默认拒绝策略是 **AbortPolicy**：
 * 池子一旦 `shutdown()` 之后再被人 `execute()`，它会**当场抛异常**。
 * 而相机分析执行器有两类「自己无法控制时机」的提交方：
 *
 *  - **CameraX**：帧到了才投递 `analyze()`，解绑与最后一帧之间天然存在窗口；
 *  - **ML Kit 的 Task 回调**：`scanner.process()` 是异步的，`shutdown()` 那一刻
 *    可能仍有在途识别，任务完成时才回来投递回调。
 *
 * 两者都发生在「页面已经销毁」之后，于是异常抛在**任意线程**（本次是主线程），
 * 没人接得住 → 闪退。**页面销毁本来就是正常的生命周期事件，不该让进程去死。**
 *
 * ## 处理方式：丢弃 + 记账，不抛
 *
 * 被拒绝的工作（一帧分析 / 一个已经没人等的回调）**丢掉是正确的**：
 * 页面都销毁了，结果也没人接收。关键是别让它以异常形式外泄。
 * 这里只吞 [RejectedExecutionException]，其它异常照常抛出——
 * 把不该吞的异常也吞掉，等于把真正的 bug 变成「悄悄不工作」。
 *
 * 记账不是多余的：如果这条日志在「页面活着」的时候反复出现，
 * 说明有别的地方把执行器提前关了，那是真 bug，必须能看见。
 *
 * @param delegate 真正干活的执行器（通常是单线程池）
 * @param name 记账日志里的名字，用于区分是哪个执行器
 * @param onRejected 拒绝回调（次数、名字），默认写日志；单元测试里替换掉
 */
class RejectionTolerantExecutor(
    private val delegate: Executor,
    private val name: String,
    private val onRejected: (Long, String) -> Unit = { total, n ->
        // 第一次和之后每 100 次记一条：正常销毁时只有个位数，不会刷屏；
        // 若在同一页面生命周期里暴涨到几百，看一眼日志就知道是有东西提前关了池子
        if (total == 1L || total % 100L == 0L) {
            AppLog.w(TAG, "[$n] 已关闭的执行器拒绝了第 $total 个任务（页面销毁后的正常丢弃）")
        }
    }
) : Executor {

    private val rejected = AtomicLong(0L)

    /** 累计被丢弃的任务数，供自检与单测断言 */
    fun rejectedCount(): Long = rejected.get()

    override fun execute(command: Runnable) {
        try {
            delegate.execute(command)
        } catch (e: RejectedExecutionException) {
            val total = rejected.incrementAndGet()
            runCatching { onRejected(total, name) }
        }
    }

    private companion object {
        const val TAG = "Executor"
    }
}
