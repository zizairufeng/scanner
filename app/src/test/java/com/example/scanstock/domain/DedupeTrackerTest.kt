package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DedupeTracker 单元测试。
 *
 * 全部用例都显式传入 nowMs，不依赖 android.os.SystemClock，
 * 因此是标准 JVM 单测（无需 Robolectric / 真机）。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 *
 * 这里的期望值不是拍脑袋写的，而是用 tools/verify_dedupe.py 按真实帧率
 * 仿真对过一遍的（见 docs/06-验证报告.md）。
 */
class DedupeTrackerTest {

    /** 模拟 ML Kit 在 20fps 下的逐帧回调：把同一帧内的所有码都喂进去 */
    private fun feedFrames(
        tracker: DedupeTracker,
        key: String,
        fromMs: Long,
        toMs: Long,
        frameMs: Long = 50L
    ): Int {
        var accepted = 0
        var t = fromMs
        while (t < toMs) {
            if (tracker.shouldAccept(key, t)) accepted++
            t += frameMs
        }
        return accepted
    }

    // ───────────────────── 防刷屏（核心） ─────────────────────

    @Test
    fun `持续对准同一个码10秒只计入1次`() {
        val tracker = DedupeTracker(1_500L)
        // 20fps × 10s = 200 次回调
        val accepted = feedFrames(tracker, "6901234567892", fromMs = 0L, toMs = 10_000L)
        assertEquals("持续可见期间每帧都会刷新时间戳，因此只能计入 1 次", 1, accepted)
    }

    @Test
    fun `帧间隔抖动不影响去重`() {
        val tracker = DedupeTracker(1_500L)
        var accepted = 0
        var t = 0L
        // 模拟抖动：帧间隔在 1~3 帧之间随机（用确定性序列，保证可复现）
        val jitter = longArrayOf(50, 150, 50, 100, 50, 50, 150, 100, 50, 100)
        var i = 0
        while (t < 5_000L) {
            if (tracker.shouldAccept("X", t)) accepted++
            t += jitter[i % jitter.size]
            i++
        }
        assertEquals(1, accepted)
    }

    @Test
    fun `同一帧内多个相同码只计入1次`() {
        val tracker = DedupeTracker(1_500L)
        var accepted = 0
        repeat(5) {                       // 一张图里 5 个同款 QR，now 完全相同
            if (tracker.shouldAccept("QR-DUP", 1_000L)) accepted++
        }
        assertEquals(1, accepted)
    }

    // ───────────────────── 计件能力 ─────────────────────

    @Test
    fun `移开超过窗口后重新出现会计为新的一条`() {
        val tracker = DedupeTracker(1_500L)
        val first = feedFrames(tracker, "A", fromMs = 0L, toMs = 500L)
        val second = feedFrames(tracker, "A", fromMs = 2_500L, toMs = 3_000L)
        assertEquals(1, first)
        assertEquals(1, second)
    }

    @Test
    fun `遮挡恰好等于窗口时计入`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        // 距上次上报正好 1500ms：判定条件是 >= window，因此接受
        assertTrue(tracker.shouldAccept("A", 1_500L))
    }

    @Test
    fun `遮挡比窗口少1毫秒时不计入`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        assertFalse(tracker.shouldAccept("A", 1_499L))
    }

    @Test
    fun `快速补扫同款在1500ms窗口下会被吞掉_这是已知取舍`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        // 300ms 后用户已经扫了第二件同款商品，但仍在窗口内
        assertFalse(tracker.shouldAccept("A", 300L))
    }

    @Test
    fun `把窗口调到300ms即可快速补扫同款且仍不刷屏`() {
        val tracker = DedupeTracker(300L)
        assertTrue(tracker.shouldAccept("A", 0L))
        assertTrue("离开 300ms 后应能重新计入", tracker.shouldAccept("A", 300L))
        // 同时：持续对准 10 秒（每帧 50ms）依然只计入 1 次
        val tracker2 = DedupeTracker(300L)
        assertEquals(1, feedFrames(tracker2, "A", 0L, 10_000L, frameMs = 50L))
    }

    // ───────────────────── 多码互不干扰 ─────────────────────

    @Test
    fun `不同码之间互不影响`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        assertTrue(tracker.shouldAccept("B", 0L))
        assertFalse(tracker.shouldAccept("A", 50L))
        assertFalse(tracker.shouldAccept("B", 50L))
        assertTrue(tracker.shouldAccept("C", 50L))
    }

    // ───────────────────── 边界与健壮性 ─────────────────────

    @Test
    fun `空key永不计入`() {
        val tracker = DedupeTracker(1_500L)
        assertFalse(tracker.shouldAccept("", 0L))
        assertFalse(tracker.shouldAccept("", 10_000L))
        assertEquals(0, tracker.trackedCount())
    }

    @Test
    fun `reset之后同一个码可以立刻重新计入`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        assertFalse(tracker.shouldAccept("A", 10L))
        assertEquals(1, tracker.trackedCount())

        tracker.reset()
        assertEquals("reset 应清空跟踪表", 0, tracker.trackedCount())
        assertTrue("清空列表后立刻重扫同一个码应当被立即录入", tracker.shouldAccept("A", 20L))
    }

    @Test
    fun `remove单个key后该码可以立刻重新计入且不影响其他码`() {
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("A", 0L))
        assertTrue(tracker.shouldAccept("B", 0L))
        assertFalse(tracker.shouldAccept("A", 10L))

        tracker.remove("A")
        assertEquals("remove 只移除目标 key，B 的记录保留", 1, tracker.trackedCount())
        assertTrue("删除列表项后立刻重扫 A 应当马上录入", tracker.shouldAccept("A", 20L))
        assertFalse("B 未被删除，仍在窗口内应被拒", tracker.shouldAccept("B", 30L))
    }

    @Test
    fun `窗口值被限制在合法区间内`() {
        val tooSmall = DedupeTracker(1L)
        assertEquals(DedupeTracker.MIN_WINDOW_MS, tooSmall.windowMillis)

        val tooBig = DedupeTracker(999_999L)
        assertEquals(DedupeTracker.MAX_WINDOW_MS, tooBig.windowMillis)

        // 运行时改窗口同样会被 clamp
        val tracker = DedupeTracker()
        tracker.windowMillis = 10L
        assertEquals(DedupeTracker.MIN_WINDOW_MS, tracker.windowMillis)
    }

    @Test
    fun `跟踪表不会无限增长_超过阈值后自动清理过期键`() {
        val tracker = DedupeTracker(1_500L)

        // 第一批：300 个码，出现在很早的时刻
        for (i in 0 until 300) {
            tracker.shouldAccept("OLD$i", 1_000L)
        }
        // 第二批：300 个码，出现在很久之后（触发 size >= 512 且间隔 > 30s 的清理）
        for (i in 0 until 300) {
            tracker.shouldAccept("NEW$i", 200_000L)
        }

        val tracked = tracker.trackedCount()
        assertTrue(
            "过期键应被清理，实际跟踪 $tracked 个（预期清理后只剩第二批）",
            tracked in 200..320
        )
    }

    @Test
    fun `不同码制的相同码值视为同一条_与需求一致`() {
        // 需求明确要求按 rawValue 去重，因此 formatId 不参与 key
        val tracker = DedupeTracker(1_500L)
        assertTrue(tracker.shouldAccept("123456789012", 0L))
        assertFalse(tracker.shouldAccept("123456789012", 100L))
    }
}
