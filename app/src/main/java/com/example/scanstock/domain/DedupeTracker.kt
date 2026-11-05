package com.example.scanstock.domain

import android.os.SystemClock

/**
 * 去重器：解决「ML Kit 每帧都会回调同一个码」导致的列表刷屏问题。
 *
 * 语义（关键设计决策，见设计文档【假设确认】第 3 条）：
 * 采用**滑动窗口**而非固定窗口——只要该 rawValue 持续出现在画面里，
 * 每帧回调都会把它的「最近上报时间」刷新到当前时刻，因此它在离开画面
 * [windowMillis] 毫秒之后才允许被重新计入。
 *
 * 如果换成「只在计入时刷新时间戳」的固定窗口写法，一个被持续对着的码
 * 会每 1.5 秒往列表里塞一条，仍然刷屏——那正是本类要消灭的现象。
 *
 * 线程安全：内部只被分析器回调（ML Kit Task 默认主线程）访问，且 ScanViewModel
 * 统一在单一入口调用；这里用 synchronized 兜底，避免将来切线程时出错。
 *
 * @param initialWindowMillis 去重时间窗，默认 1500ms，可在设置里 100~10000ms 调节。
 */
class DedupeTracker(initialWindowMillis: Long = DEFAULT_WINDOW_MS) {

    companion object {
        /** 滑杆默认值：1.5 秒，多数场景够用。 */
        const val DEFAULT_WINDOW_MS = 1_500L

        /**
         * 下限 100ms。
         *
         * v1.4 之前是 300ms，但用户希望能把「同码重复识别间隔」调到更灵敏——
         * 例如对着同一排货架连续扫同款商品，希望抬手就能再记一次。
         * 100ms 仍高于相机的帧间隔（约 33ms），不会退化成「每帧都记」的刷屏。
         */
        const val MIN_WINDOW_MS = 100L

        /**
         * 上限 10000ms（10 秒）。
         *
         * v1.4 之前是 5000ms，但对着堆垛的整箱同款条码时，5 秒内仍可能被反复读到；
         * 拉到 10 秒可以让「严格去重」在慢速晃动场景下更稳。
         */
        const val MAX_WINDOW_MS = 10_000L

        /** 滑杆吸附粒度：100ms，与设置面板的档位刻度一致。 */
        const val WINDOW_STEP_MS = 100L

        /** 超过窗口 10 倍仍未再出现，就认为该码已永久离场，可安全回收 */
        private const val PRUNE_MULTIPLIER = 10
        private const val PRUNE_INTERVAL_MS = 30_000L

        /** 夹取到合法区间并对齐到 [WINDOW_STEP_MS] 的整数倍，供 UI 与 ViewModel 复用。 */
        fun normalizeWindow(millis: Long): Long {
            val clamped = millis.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
            val step = WINDOW_STEP_MS
            val snapped = (clamped + step / 2) / step * step
            return snapped.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        }
    }

    private val lock = Any()

    /** key = rawValue，value = 最近一次被 ML Kit 上报的时间（elapsedRealtime，不受系统改时间影响） */
    private val lastReportedAt = HashMap<String, Long>(64)

    private var pruneAt = 0L

    // 注意：构造参数名与属性名刻意不同。若同名，属性初始化式里的 `windowMillis`
    // 会被解析成属性自引用而编译失败。
    var windowMillis: Long = normalizeWindow(initialWindowMillis)
        set(value) {
            field = value.coerceIn(MIN_WINDOW_MS, MAX_WINDOW_MS)
        }

    /**
     * 判断本次识别是否应当计入列表。
     *
     * 注意：**无论返回 true 还是 false，都会刷新该 key 的时间戳**，这是滑动窗口的核心。
     *
     * @param key   去重键。按需求使用 rawValue 原文（不同码制但内容相同也视为同一个码）。
     * @param nowMs 当前时间，默认取 [SystemClock.elapsedRealtime]，便于单元测试注入。
     */
    fun shouldAccept(key: String, nowMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (key.isEmpty()) return false
        synchronized(lock) {
            val last = lastReportedAt[key]
            val accept = last == null || nowMs - last >= windowMillis
            lastReportedAt[key] = nowMs
            maybePruneLocked(nowMs)
            return accept
        }
    }

    /**
     * 单独移除某个码的跟踪记录。
     *
     * 用途：用户从列表里删除一条后立刻重扫同一个码，应当能马上录入，
     * 而不是因为该码的时间戳还在窗口内而被静默拒绝。清空列表请用 [reset]。
     */
    fun remove(key: String) {
        synchronized(lock) { lastReportedAt.remove(key) }
    }

    /** 清空列表时同步调用，让刚刚扫过的码可以立刻被重新录入 */
    fun reset() {
        synchronized(lock) {
            lastReportedAt.clear()
            pruneAt = 0L
        }
    }

    /** 当前被跟踪的码数量，供调试展示 */
    fun trackedCount(): Int = synchronized(lock) { lastReportedAt.size }

    private fun maybePruneLocked(nowMs: Long) {
        if (lastReportedAt.size < 512) return
        if (nowMs - pruneAt < PRUNE_INTERVAL_MS) return
        pruneAt = nowMs
        val deadline = windowMillis * PRUNE_MULTIPLIER
        val it = lastReportedAt.entries.iterator()
        while (it.hasNext()) {
            if (nowMs - it.next().value > deadline) it.remove()
        }
    }
}
