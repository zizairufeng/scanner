package com.example.scanstock.domain

import java.util.Locale

/**
 * 某一时刻的识别吞吐快照（纯数据，可安全跨线程传递）。
 *
 * @param frames    窗口内**完成**的分析帧数（不是相机出帧数——被节流丢掉的帧不算）
 * @param fps       窗口内实际完成的帧率
 * @param avgCostMs 单帧从送入识别到释放缓冲区的平均耗时（含补救链）
 * @param maxCostMs 窗口内最慢的一帧，用于判断「偶发卡顿」还是「持续卡顿」
 */
data class PerfSnapshot(
    val frames: Int,
    val fps: Float,
    val avgCostMs: Long,
    val maxCostMs: Long
) {
    companion object {
        /** 没有任何样本时的取值。`frames == 0` 表示「还没测到」，不代表「0 帧/秒」 */
        val EMPTY = PerfSnapshot(frames = 0, fps = 0f, avgCostMs = 0L, maxCostMs = 0L)
    }
}

/**
 * 单帧耗时的滑动窗口计量器（v1.8.2）。
 *
 * ## 为什么用「完成时刻」而不是「开始时刻」算帧率
 *
 * 分析器是「一帧跑完才 close、CameraX 才给下一帧」的串行模型，
 * 用开始时刻只能测出我们自己的处理耗时，测不出**相机送帧 + 传输 + 队列**那部分空白。
 * 而用户感受到的「卡」是两者之和，所以这里记完成时刻：
 * 一秒内完成了多少帧，就是用户真正看到的识别帧率。
 *
 * ## 线程模型
 *
 * 写入来自分析线程（`analyze` 的 `finally`），读取来自主线程（诊断角标轮询）。
 * 方法全部 `@Synchronized`——读写都只操作几个 Long 数组，锁竞争可以忽略（每秒几十次）。
 *
 * 容量固定、覆盖最旧样本：窗口 1 秒 + 30fps 时最多 30 条，96 的容量留足余量，
 * 且**永不分配新数组**（这个类跑在每一帧结束的热路径上）。
 */
class FrameCostMeter(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val capacity: Int = DEFAULT_CAPACITY
) {

    private val ends = LongArray(capacity)
    private val costs = LongArray(capacity)

    /** 环形缓冲区：`start` 是最旧样本，`size` 是有效样本数 */
    private var start = 0
    private var size = 0

    /** 记一帧：它在 [endAtMs] 完成，共花了 [costMs] */
    @Synchronized
    fun record(endAtMs: Long, costMs: Long) {
        if (size < capacity) {
            ends[(start + size) % capacity] = endAtMs
            costs[(start + size) % capacity] = costMs
            size++
        } else {
            // 满了：覆盖最旧的一条，窗口语义只关心「最近一秒」，丢掉更早的没有损失
            ends[start] = endAtMs
            costs[start] = costMs
            start = (start + 1) % capacity
        }
        prune(endAtMs)
    }

    /** 取 [nowMs] 时刻的窗口快照；窗口内没有样本时返回 [PerfSnapshot.EMPTY] */
    @Synchronized
    fun snapshot(nowMs: Long): PerfSnapshot {
        prune(nowMs)
        if (size == 0) return PerfSnapshot.EMPTY

        var sum = 0L
        var max = 0L
        for (i in 0 until size) {
            val cost = costs[(start + i) % capacity]
            sum += cost
            if (cost > max) max = cost
        }
        // 窗口内完成帧数 ÷ 窗口秒数 = 实际帧率。窗口固定，所以不需要记「窗口实际跨度」，
        // 代价是启动后第一个不满一秒的窗口会偏低——由 ScanPerf 的连续两窗口判定吸收掉。
        val fps = size * 1000f / windowMs
        return PerfSnapshot(frames = size, fps = fps, avgCostMs = sum / size, maxCostMs = max)
    }

    /** 清空（相机重绑、切换分辨率档位后重新计时） */
    @Synchronized
    fun reset() {
        start = 0
        size = 0
    }

    /** 丢掉窗口外的旧样本 */
    private fun prune(nowMs: Long) {
        while (size > 0 && nowMs - ends[start] > windowMs) {
            start = (start + 1) % capacity
            size--
        }
    }

    companion object {
        /** 统计窗口：1 秒。与诊断角标 1 秒一次的刷新节奏对齐 */
        const val DEFAULT_WINDOW_MS: Long = 1_000L

        /** 环形缓冲区容量：96 条，30fps 下一秒最多 30 条，留 3 倍余量 */
        const val DEFAULT_CAPACITY: Int = 96
    }
}

/**
 * 识别性能的判定与文案（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么角标要「自己冒出来」而不是给个开关
 *
 * 仓储场景里，屏幕上常驻性能数字是纯噪音；可调参时又必须看得见。两者用**出现条件**
 * 调和：只有当识别链路明显跟不上时才显示，恢复后自己消失。
 *
 * 这不只是给用户看——它同时是这道优化最诚实的验收标准：
 * 优化前，对难扫的码角标会常驻「5fps」；优化后应当很少出现，出现了数字也该更高。
 *
 * 另外设置面板已经顶到 `ModalBottomSheet` 的高度上限（见 [SettingsPage]），
 * 再加一行开关就会被 sheet 从底部裁掉——这也是不给它开关的现实原因。
 */
object ScanPerf {

    /**
     * 低于这个帧率就算「跟不上」。
     *
     * 取 10fps：人眼对 10fps 以下的连续画面会明显感到顿，而且这个数量级还够不上
     * 「连续扫描」——用户把手机移到下一个码之后，等一帧结果的时间已超过 100ms。
     */
    const val SLOW_FPS: Float = 10f

    /**
     * 单帧耗时超过这个值也算「慢」，即便帧率还没掉下来。
     *
     * 帧率是平均值，单帧 180ms 但其余帧很快时平均值可能仍显得可以，
     * 而用户感受到的恰是那一顿。150ms 同时也是 [RefineBudget.SLOW_PASS]，
     * 两个阈值取同一个数量级，口径一致。
     */
    const val SLOW_COST_MS: Long = 150L

    /** 连续多少个「快」窗口才把角标收掉。3 个 = 约 3 秒，避免数字一直闪烁 */
    const val FAST_STREAK_TO_HIDE: Int = 3

    /**
     * 是否慢到值得提示。
     *
     * `frames <= 0` 恒为 `false`：相机还没出帧（启动中、切后台）不是性能问题，
     * 报成 0fps 只会误导。
     */
    fun isSlow(snapshot: PerfSnapshot): Boolean {
        if (snapshot.frames <= 0) return false
        return snapshot.fps < SLOW_FPS || snapshot.avgCostMs > SLOW_COST_MS
    }

    /**
     * 角标文案。刻意**只给两个数**：帧率（用户能感知的「顿」）和单帧耗时（能定位原因的量）。
     *
     * 帧率保留一位小数：1.5 与 2.0 的差别在调参时是有意义的，取整会把它们都变成 2。
     */
    fun badgeText(snapshot: PerfSnapshot): String {
        val fps = String.format(Locale.US, "%.1f", snapshot.fps)
        return "识别 $fps fps · 单帧 ${snapshot.avgCostMs}ms"
    }
}
