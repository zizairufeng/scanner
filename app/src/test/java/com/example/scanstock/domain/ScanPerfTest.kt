package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别吞吐统计与诊断角标的纯逻辑测试（v1.8.2）。
 *
 * 这两块一起测，是因为它们是一条链：`FrameCostMeter` 算数错了，角标就会乱报；
 * 角标判据错了，`FrameCostMeter` 再准也没用。而两者都有一个共同的坑——
 * **边界**：滑动窗口的进出点、连续几个窗口才收掉、帧率保留几位小数。
 */
class ScanPerfTest {

    // ───────────────────────── FrameCostMeter ─────────────────────────

    @Test
    fun `没有样本时返回空快照而不是零帧率`() {
        val meter = FrameCostMeter()
        val snap = meter.snapshot(nowMs = 1_000L)
        assertEquals(0, snap.frames)
        assertEquals(PerfSnapshot.EMPTY, snap)
    }

    @Test
    fun `一秒内完成三十帧就是三十帧每秒`() {
        val meter = FrameCostMeter()
        for (i in 0 until 30) {
            meter.record(endAtMs = 1_000L + i * 33L, costMs = 30L)
        }
        val snap = meter.snapshot(nowMs = 2_000L)
        assertEquals(30, snap.frames)
        assertEquals(30f, snap.fps, 0.01f)
        assertEquals(30L, snap.avgCostMs)
        assertEquals(30L, snap.maxCostMs)
    }

    @Test
    fun `平均与最大值分别反映常态与最差帧`() {
        val meter = FrameCostMeter()
        meter.record(endAtMs = 1_000L, costMs = 40L)
        meter.record(endAtMs = 1_020L, costMs = 320L)   // 一帧补救链跑满
        meter.record(endAtMs = 1_040L, costMs = 40L)
        val snap = meter.snapshot(nowMs = 1_050L)
        assertEquals(3, snap.frames)
        assertEquals((40L + 320L + 40L) / 3, snap.avgCostMs)
        assertEquals(320L, snap.maxCostMs)
    }

    @Test
    fun `窗口外的旧样本会被丢掉`() {
        val meter = FrameCostMeter()
        meter.record(endAtMs = 1_000L, costMs = 500L)
        // 2 秒后：那条样本已经在窗口外，此时应是「还没测到」
        assertEquals(PerfSnapshot.EMPTY, meter.snapshot(nowMs = 3_000L))
    }

    @Test
    fun `正好落在窗口边界上的样本仍然保留`() {
        // 判定是 now - end > window，取等号时不丢——差一毫秒的区别，靠代码读不出来
        val meter = FrameCostMeter(windowMs = 1_000L)
        meter.record(endAtMs = 0L, costMs = 10L)
        assertEquals(1, meter.snapshot(nowMs = 1_000L).frames)
        assertEquals(0, meter.snapshot(nowMs = 1_001L).frames)
    }

    @Test
    fun `环形缓冲区写满后保留最新的样本`() {
        val meter = FrameCostMeter(windowMs = 10_000L, capacity = 4)
        for (i in 0 until 6) {
            meter.record(endAtMs = i.toLong(), costMs = 10L * (i + 1))   // 10,20,30,40,50,60
        }
        val snap = meter.snapshot(nowMs = 6L)
        assertEquals(4, snap.frames)
        // 最早的两条(10,20)被覆盖，剩下 30,40,50,60
        assertEquals(45L, snap.avgCostMs)
        assertEquals(60L, snap.maxCostMs)
    }

    @Test
    fun `reset 之后重新计时`() {
        val meter = FrameCostMeter()
        meter.record(endAtMs = 1_000L, costMs = 100L)
        meter.reset()
        assertEquals(PerfSnapshot.EMPTY, meter.snapshot(nowMs = 1_000L))
    }

    // ───────────────────────── ScanPerf ─────────────────────────

    @Test
    fun `还没出帧时不算慢`() {
        // 相机刚绑定、切后台都会是 0 帧；报成 0fps 只会误导用户
        assertFalse(ScanPerf.isSlow(PerfSnapshot.EMPTY))
    }

    @Test
    fun `帧率低于十判定为慢`() {
        assertTrue(ScanPerf.isSlow(PerfSnapshot(frames = 5, fps = 5f, avgCostMs = 90L, maxCostMs = 120L)))
        assertFalse(ScanPerf.isSlow(PerfSnapshot(frames = 20, fps = 20f, avgCostMs = 40L, maxCostMs = 60L)))
    }

    @Test
    fun `单帧耗时超标也算慢_即便平均帧率还过得去`() {
        // 一帧 200ms 会明显顿一下，而平均值可能被其余快帧摊薄——必须单独拦一道
        assertTrue(ScanPerf.isSlow(PerfSnapshot(frames = 25, fps = 25f, avgCostMs = 200L, maxCostMs = 400L)))
    }

    @Test
    fun `角标文案带一位小数的帧率与整数耗时`() {
        val text = ScanPerf.badgeText(PerfSnapshot(frames = 6, fps = 6.24f, avgCostMs = 178L, maxCostMs = 300L))
        assertEquals("识别 6.2 fps · 单帧 178ms", text)
    }

    @Test
    fun `角标文案用点号做小数点_与区域设置无关`() {
        // 用 Locale.US 固定格式：某些区域会把小数点写成逗号，那种文本在日志里很难 grep
        val text = ScanPerf.badgeText(PerfSnapshot(frames = 3, fps = 1.5f, avgCostMs = 320L, maxCostMs = 500L))
        assertTrue(text.contains("1.5"))
        assertFalse(text.contains("1,5"))
    }
}
