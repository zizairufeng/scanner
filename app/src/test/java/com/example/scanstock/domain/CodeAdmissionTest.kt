package com.example.scanstock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 准入判定单元测试（三态口径）。
 *
 * 这是本 App 最容易被改错的一块：口径从「去重开 / 关」升级成三态后，
 * 「重复的那个码到底算不算、怎么算」的分支多了一条，靠人眼 review 不可靠。
 * 下面每个用例都对应一个真实使用场景。
 *
 * 运行：./gradlew :app:testDebugUnitTest
 */
class CodeAdmissionTest {

    private fun code(value: String) = DetectedCode(formatId = 0, formatName = "EAN-13", value = value)

    private fun admit(
        decoded: List<String>,
        mode: DedupeMode,
        tracker: DedupeTracker,
        existing: Set<String> = emptySet(),
        now: Long = 0L
    ): AdmissionResult = CodeAdmission.admit(
        decoded = decoded.map(::code),
        mode = mode,
        dedupe = tracker,
        existingValues = existing,
        nowMs = now
    )

    // ───────────────────── 严格去重 ─────────────────────

    @Test
    fun `严格去重_新码正常计入`() {
        val r = admit(listOf("A"), DedupeMode.STRICT, DedupeTracker(1_500L))
        assertEquals(listOf("A"), r.newCodes.map { it.value })
        assertTrue(r.increments.isEmpty())
    }

    @Test
    fun `严格去重_列表里已有的码不再计入`() {
        val r = admit(listOf("A"), DedupeMode.STRICT, DedupeTracker(1_500L), existing = setOf("A"))
        assertTrue("已有的码必须被拒绝", r.isEmpty())
    }

    // ───────────────────── 计数聚合 ─────────────────────

    @Test
    fun `计数聚合_新码仍然新增一行`() {
        val r = admit(listOf("A"), DedupeMode.COUNT, DedupeTracker(1_500L))
        assertEquals(listOf("A"), r.newCodes.map { it.value })
        assertTrue(r.increments.isEmpty())
    }

    @Test
    fun `计数聚合_已有码走累加而不是新增行`() {
        val r = admit(listOf("A"), DedupeMode.COUNT, DedupeTracker(1_500L), existing = setOf("A"))
        assertTrue("不能新增行", r.newCodes.isEmpty())
        assertEquals(listOf("A"), r.increments)
    }

    @Test
    fun `计数聚合_同帧内同一个码只累加一次`() {
        // 一帧里扫到 3 个一样的码：同帧去重必须先挡掉两个，只累加 1
        val r = admit(
            listOf("A", "A", "A"), DedupeMode.COUNT, DedupeTracker(1_500L), existing = setOf("A")
        )
        assertEquals(listOf("A"), r.increments)
    }

    // ───────────────────── 允许重复 ─────────────────────

    @Test
    fun `允许重复_已有码也新增一行`() {
        val r = admit(listOf("A"), DedupeMode.ALLOW, DedupeTracker(1_500L), existing = setOf("A"))
        assertEquals(listOf("A"), r.newCodes.map { it.value })
        assertTrue(r.increments.isEmpty())
    }

    @Test
    fun `允许重复_不依赖existing也能工作`() {
        // ALLOW 不需要知道列表里有什么，调用方传空集合即可
        val r = admit(listOf("A"), DedupeMode.ALLOW, DedupeTracker(1_500L), existing = emptySet())
        assertEquals(listOf("A"), r.newCodes.map { it.value })
    }

    // ───────────────────── 同帧去重（无条件）─────────────────────

    @Test
    fun `同帧去重_三种口径下都生效`() {
        DedupeMode.entries.forEach { mode ->
            val r = admit(listOf("A", "A"), mode, DedupeTracker(1_500L))
            assertEquals(
                "模式 $mode 下同帧重复应只留一条，实际 ${r.newCodes.size} 条",
                1,
                r.newCodes.size
            )
        }
    }

    // ───────────────────── 时间窗防刷屏（无条件）─────────────────────

    @Test
    fun `时间窗_三种口径下都挡住窗口内的重复`() {
        DedupeMode.entries.forEach { mode ->
            val tracker = DedupeTracker(1_500L)
            admit(listOf("A"), mode, tracker, now = 0L)
            val second = admit(listOf("A"), mode, tracker, now = 500L)
            assertTrue(
                "模式 $mode 下 500ms 内的重复必须被时间窗挡住",
                second.isEmpty()
            )
        }
    }

    @Test
    fun `时间窗_计数聚合下也不允许窗口内连续累加`() {
        // 持续对准同一个码：如果这里不挡，1 秒会加十几次
        val tracker = DedupeTracker(1_500L)
        admit(listOf("A"), DedupeMode.COUNT, tracker, now = 0L)
        admit(listOf("A"), DedupeMode.COUNT, tracker, now = 300L)
        assertTrue(admit(listOf("A"), DedupeMode.COUNT, tracker, existing = setOf("A"), now = 600L).isEmpty())
        // 满窗口后可以再累加一次。注意计的是「距最后一次刷新」而不是「距第一次」：
        // shouldAccept 每被调用一次就把时间戳推到当下（被拒也推），最后一次刷新在 600ms，
        // 所以下一次可接受的时刻是 600+1500=2100，这里取 2200。
        val later = admit(listOf("A"), DedupeMode.COUNT, tracker, existing = setOf("A"), now = 2_200L)
        assertEquals(listOf("A"), later.increments)
    }

    @Test
    fun `窗口满后严格去重仍然拒绝`() {
        // 严格去重的语义是「列表里已有就不再计入」，与时间窗无关
        val tracker = DedupeTracker(1_500L)
        admit(listOf("A"), DedupeMode.STRICT, tracker, now = 0L)
        assertTrue(
            admit(listOf("A"), DedupeMode.STRICT, tracker, existing = setOf("A"), now = 10_000L).isEmpty()
        )
    }

    // ───────────────────── 边界 ─────────────────────

    @Test
    fun `空输入返回空结果`() {
        val r = admit(emptyList(), DedupeMode.STRICT, DedupeTracker(1_500L))
        assertTrue(r.isEmpty())
    }

    @Test
    fun `一帧内多个不同码保持输入顺序`() {
        val r = admit(listOf("C", "A", "B"), DedupeMode.STRICT, DedupeTracker(1_500L))
        assertEquals(listOf("C", "A", "B"), r.newCodes.map { it.value })
    }

    @Test
    fun `一帧内新码与累加项可以同时出现`() {
        val r = admit(
            listOf("OLD", "NEW"), DedupeMode.COUNT, DedupeTracker(1_500L), existing = setOf("OLD")
        )
        assertEquals(listOf("NEW"), r.newCodes.map { it.value })
        assertEquals(listOf("OLD"), r.increments)
    }

    // ───────────────────── 重复码上报（v1.7 弹框用）─────────────────────

    @Test
    fun `严格去重_命中的重复码要上报给弹框`() {
        // 这是 v1.7 的关键：以前重复码在这里被静默丢掉，UI 完全不知道发生过什么
        val r = admit(listOf("A"), DedupeMode.STRICT, DedupeTracker(1_500L), existing = setOf("A"))
        assertTrue(r.isEmpty())
        assertEquals(listOf("A"), r.duplicates)
    }

    @Test
    fun `计数聚合_累加的码同时算重复命中`() {
        val r = admit(listOf("A"), DedupeMode.COUNT, DedupeTracker(1_500L), existing = setOf("A"))
        assertEquals(listOf("A"), r.increments)
        assertEquals(listOf("A"), r.duplicates)
    }

    @Test
    fun `允许重复_不算重复命中`() {
        // 这种口径下每次扫码都是新记录，弹「重复码」纯属噪音
        val r = admit(listOf("A"), DedupeMode.ALLOW, DedupeTracker(1_500L), existing = setOf("A"))
        assertEquals(1, r.newCodes.size)
        assertTrue(r.duplicates.isEmpty())
    }

    @Test
    fun `时间窗内的重复帧不上报`() {
        // 同一秒内连续 20 帧都看到同一个码：只该弹一次，不是 20 次
        val tracker = DedupeTracker(1_500L)
        val first = admit(listOf("A"), DedupeMode.STRICT, tracker, existing = setOf("A"), now = 0L)
        val second = admit(listOf("A"), DedupeMode.STRICT, tracker, existing = setOf("A"), now = 200L)
        assertEquals(listOf("A"), first.duplicates)
        assertTrue("时间窗未满的帧不该再上报", second.duplicates.isEmpty())
    }
}
