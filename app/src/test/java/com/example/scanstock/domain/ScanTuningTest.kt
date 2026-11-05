package com.example.scanstock.domain

import com.example.scanstock.domain.ScanTuning.AnalysisProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「识别引擎调优」纯逻辑单测（v1.4）。
 *
 * 覆盖三块：
 *  1. 分辨率档位与增强识别的联动规则；
 *  2. 帧间隔（识别间隙）的归一化、文案与频率估算；
 *  3. 帧间隔闸门 [ScanTuning.shouldAnalyzeFrame] 的边界。
 */
class ScanTuningTest {

    // ---------------------------------------------------------- 分辨率档位

    @Test
    fun defaultProfileIsBalancedForZeroRegression() {
        // 默认必须是 720p，否则装了新版本用户的耗电会无声上升
        assertEquals(AnalysisProfile.BALANCED, AnalysisProfile.DEFAULT)
        assertEquals(1280, AnalysisProfile.DEFAULT.width)
        assertEquals(720, AnalysisProfile.DEFAULT.height)
    }

    @Test
    fun pixelScaleIsRelativeToBalanced() {
        assertEquals(1.0, AnalysisProfile.BALANCED.pixelScale, 0.0001)
        // 1920*1080 / (1280*720) = 2.25
        assertEquals(2.25, AnalysisProfile.SHARP.pixelScale, 0.0001)
        // 2560*1440 / (1280*720) = 4.0
        assertEquals(4.0, AnalysisProfile.ULTRA.pixelScale, 0.0001)
    }

    @Test
    fun fromOrdinalFallsBackToDefaultOnGarbage() {
        // 旧版本存盘 / 手改配置文件都可能给出越界序号，必须安全回落而不是崩溃
        assertEquals(AnalysisProfile.SHARP, AnalysisProfile.fromOrdinal(1))
        assertEquals(AnalysisProfile.ULTRA, AnalysisProfile.fromOrdinal(2))
        assertEquals(AnalysisProfile.DEFAULT, AnalysisProfile.fromOrdinal(-1))
        assertEquals(AnalysisProfile.DEFAULT, AnalysisProfile.fromOrdinal(99))
    }

    // ---------------------------------------------------------- 增强识别

    @Test
    fun boostLiftsBalancedToSharp() {
        assertEquals(
            AnalysisProfile.SHARP,
            ScanTuning.profileWithBoost(AnalysisProfile.BALANCED, boosted = true)
        )
    }

    @Test
    fun boostNeverDowngradesUserChoice() {
        // 用户主动选了 1440p 后再开增强识别，不能被降回 1080p
        assertEquals(
            AnalysisProfile.ULTRA,
            ScanTuning.profileWithBoost(AnalysisProfile.ULTRA, boosted = true)
        )
        assertEquals(
            AnalysisProfile.SHARP,
            ScanTuning.profileWithBoost(AnalysisProfile.SHARP, boosted = true)
        )
    }

    @Test
    fun boostOffKeepsCurrentProfile() {
        assertEquals(
            AnalysisProfile.BALANCED,
            ScanTuning.profileWithBoost(AnalysisProfile.BALANCED, boosted = false)
        )
        // 关闭增强识别不负责回落档位：用户的显式选择要保留
        assertEquals(
            AnalysisProfile.ULTRA,
            ScanTuning.profileWithBoost(AnalysisProfile.ULTRA, boosted = false)
        )
    }

    // ---------------------------------------------------------- 帧间隔归一化

    @Test
    fun defaultFrameIntervalIsZeroMeaningNoThrottle() {
        // 0 = 每帧都送检，等价 v1.3 行为
        assertEquals(0L, ScanTuning.DEFAULT_FRAME_INTERVAL_MS)
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1L, lastAcceptedMs = 0L, frameIntervalMs = 0L))
    }

    @Test
    fun normalizeClampsOutOfRange() {
        assertEquals(ScanTuning.MIN_FRAME_INTERVAL_MS, ScanTuning.normalizeFrameInterval(-500L))
        assertEquals(ScanTuning.MAX_FRAME_INTERVAL_MS, ScanTuning.normalizeFrameInterval(9_999L))
    }

    @Test
    fun normalizeSnapsToStep() {
        // 120 → 最接近的 50 的倍数 = 100
        assertEquals(100L, ScanTuning.normalizeFrameInterval(120L))
        // 130 → 150
        assertEquals(150L, ScanTuning.normalizeFrameInterval(130L))
        // 已经是步长的整数倍则原样返回
        assertEquals(200L, ScanTuning.normalizeFrameInterval(200L))
    }

    @Test
    fun normalizeSnapNeverExceedsBounds() {
        // 吸附后可能越过上限，必须再夹一次（499 → 500 是合法的；501 不在区间内）
        val top = ScanTuning.normalizeFrameInterval(ScanTuning.MAX_FRAME_INTERVAL_MS)
        assertTrue(top <= ScanTuning.MAX_FRAME_INTERVAL_MS)
        assertTrue(top >= ScanTuning.MIN_FRAME_INTERVAL_MS)
    }

    // ---------------------------------------------------------- 文案与频率

    @Test
    fun frameIntervalLabelReadsNaturally() {
        assertEquals("不节流", ScanTuning.frameIntervalLabel(0L))
        assertEquals("间隔 200ms", ScanTuning.frameIntervalLabel(200L))
    }

    @Test
    fun estimatedFpsMatchesInterval() {
        assertEquals(30, ScanTuning.estimatedFramesPerSecond(0L))
        assertEquals(10, ScanTuning.estimatedFramesPerSecond(100L))
        assertEquals(5, ScanTuning.estimatedFramesPerSecond(200L))
        assertEquals(2, ScanTuning.estimatedFramesPerSecond(500L))
    }

    @Test
    fun estimatedFpsNeverDropsBelowOne() {
        // 极慢的间隔也必须显示至少 1 帧/秒，不能出现 0 让用户以为坏了
        assertTrue(ScanTuning.estimatedFramesPerSecond(Long.MAX_VALUE) >= 1)
    }

    // ---------------------------------------------------------- 帧间隔闸门

    @Test
    fun firstFrameIsAlwaysAccepted() {
        // lastAcceptedMs = 0 表示「还没送检过」，首帧必须立刻识别，否则会有明显启动延迟
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 123_456L, lastAcceptedMs = 0L, frameIntervalMs = 500L))
    }

    @Test
    fun frameBeforeIntervalIsSkipped() {
        assertFalse(ScanTuning.shouldAnalyzeFrame(nowMs = 1_100L, lastAcceptedMs = 1_000L, frameIntervalMs = 200L))
    }

    @Test
    fun frameExactlyAtIntervalIsAccepted() {
        // 边界取「大于等于」，否则设 200ms 实际会变成 233ms（下一帧才够）
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1_200L, lastAcceptedMs = 1_000L, frameIntervalMs = 200L))
    }

    @Test
    fun frameAfterIntervalIsAccepted() {
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1_500L, lastAcceptedMs = 1_000L, frameIntervalMs = 200L))
    }

    @Test
    fun zeroIntervalAcceptsEveryFrame() {
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1_000L, lastAcceptedMs = 1_000L, frameIntervalMs = 0L))
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1_001L, lastAcceptedMs = 1_000L, frameIntervalMs = 0L))
    }

    @Test
    fun negativeIntervalIsTreatedAsNoThrottle() {
        // 防御性：万一配置被手改成负数，也不能变成「永不识别」
        assertTrue(ScanTuning.shouldAnalyzeFrame(nowMs = 1_000L, lastAcceptedMs = 1_000L, frameIntervalMs = -50L))
    }

    // ---------------------------------------------------------- 与同码间隔的独立性

    @Test
    fun frameIntervalAndDedupeWindowAreIndependent() {
        // 两个参数各管一事：帧间隔管「多久送检一次」，时间窗管「同码多久能再记一次」。
        // 帧间隔拉满也不应该改变时间窗的默认值。
        assertEquals(1_500L, DedupeTracker.DEFAULT_WINDOW_MS)
        assertEquals(0L, ScanTuning.DEFAULT_FRAME_INTERVAL_MS)
    }

    @Test
    fun dedupeWindowBoundsExtendedInV14() {
        // v1.4 把上限从 5000 放到 10000、下限从 300 放到 100：整垛同款与快速补扫都能覆盖
        assertEquals(100L, DedupeTracker.MIN_WINDOW_MS)
        assertEquals(10_000L, DedupeTracker.MAX_WINDOW_MS)
        assertTrue(DedupeTracker.MIN_WINDOW_MS < DedupeTracker.DEFAULT_WINDOW_MS)
        assertTrue(DedupeTracker.DEFAULT_WINDOW_MS < DedupeTracker.MAX_WINDOW_MS)
    }

    @Test
    fun dedupeWindowNormalizeSnapsAndClamps() {
        assertEquals(1_500L, DedupeTracker.normalizeWindow(1_537L))
        assertEquals(DedupeTracker.MIN_WINDOW_MS, DedupeTracker.normalizeWindow(0L))
        assertEquals(DedupeTracker.MAX_WINDOW_MS, DedupeTracker.normalizeWindow(99_999L))
        // 步长是 100ms
        assertEquals(0L, DedupeTracker.normalizeWindow(1_500L) % DedupeTracker.WINDOW_STEP_MS)
    }
}
