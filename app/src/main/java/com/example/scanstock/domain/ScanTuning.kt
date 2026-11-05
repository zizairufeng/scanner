package com.example.scanstock.domain

/**
 * 识别引擎调优参数（纯逻辑，零 Android 依赖，可 JVM 单测）。
 *
 * 解决两个识别痛点：
 *  1. 小二维码 / 复杂二维码识别率低 —— 靠 [AnalysisProfile] 提升分析分辨率与变焦上限；
 *  2. 「识别间隙」不可调 —— 拆成两个独立参数：
 *     - [frameIntervalMs] 分析帧间隔：每隔多久才送一帧给识别引擎（省电、降热、降 CPU）；
 *     - 同码重复识别间隔：由 [DedupeTracker.windowMillis] 承载（见该类的上下限）。
 */
object ScanTuning {

    // ---------------------------------------------------------------- 分析分辨率档位

    /**
     * 分析帧分辨率档位。
     *
     * @param width 目标分析宽度（px）
     * @param height 目标分析高度（px）
     * @param label 设置面板展示名
     * @param hint 设置面板副标题
     */
    enum class AnalysisProfile(
        val width: Int,
        val height: Int,
        val label: String,
        val hint: String
    ) {
        /** 720p —— v1.3 的行为，省电优先，适合大码、近距。 */
        BALANCED(1280, 720, "标准 720p", "省电优先，适合大码、近距离"),

        /** 1080p —— 像素数约 2.25 倍，小码在画面里占的像素更多，识别率明显提升。 */
        SHARP(1920, 1080, "高清 1080p", "小码、中远距离，耗电与发热上升"),

        /**
         * 超高 1440p —— 极细密 / 高版本二维码（如 40×40 版本）与强反光场景。
         * 部分设备不支持该尺寸，由 CameraX 的 FALLBACK 规则自动降档。
         */
        ULTRA(2560, 1440, "超高 1440p", "极细密复杂码，性能开销最大，部分设备自动降档");

        /** 该档位相对基准 720p 的像素倍数，用于评估识别精度收益与性能代价。 */
        val pixelScale: Double
            get() = (width.toDouble() * height) / (BALANCED.width.toDouble() * BALANCED.height)

        companion object {
            /** 默认档位，与 v1.3 行为完全一致。 */
            val DEFAULT: AnalysisProfile = BALANCED

            /** 按存盘序号还原；越界回落到默认档，保证旧配置可读、坏配置不崩。 */
            fun fromOrdinal(ordinal: Int): AnalysisProfile =
                entries.firstOrNull { it.ordinal == ordinal } ?: DEFAULT
        }
    }

    // ---------------------------------------------------------------- 增强识别

    /**
     * 增强识别开关的档位提升规则。
     *
     * 打开「增强识别」但用户没手动选档时，自动把 [AnalysisProfile.BALANCED] 抬到 [AnalysisProfile.SHARP]；
     * 用户已显式选过更高档位则不动，避免把用户的主动选择降级。
     */
    fun profileWithBoost(current: AnalysisProfile, boosted: Boolean): AnalysisProfile =
        if (boosted && current.ordinal < AnalysisProfile.SHARP.ordinal) {
            AnalysisProfile.SHARP
        } else {
            current
        }

    /** 增强识别时变焦上限的下限值：至少给到 4x，否则远距小码仍看不清。 */
    const val BOOST_MIN_ZOOM: Float = 4f

    // ---------------------------------------------------------------- 分析帧间隔

    /** 分析帧间隔下限：0 表示不节流。 */
    const val MIN_FRAME_INTERVAL_MS: Long = 0L

    /** 分析帧间隔上限：500ms 即最快 2 帧/秒，再慢会影响扫码手感。 */
    const val MAX_FRAME_INTERVAL_MS: Long = 500L

    /** 默认帧间隔：0 表示不节流，每帧都送识别引擎 —— v1.3 的行为。 */
    const val DEFAULT_FRAME_INTERVAL_MS: Long = 0L

    /** 帧间隔吸附粒度：50ms，让滑杆停在不跳动的手感上。 */
    const val FRAME_INTERVAL_STEP_MS: Long = 50L

    /** 把任意输入夹到合法区间并对齐到 [FRAME_INTERVAL_STEP_MS] 的整数倍。 */
    fun normalizeFrameInterval(millis: Long): Long {
        val clamped = millis.coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
        val step = FRAME_INTERVAL_STEP_MS
        val snapped = (clamped + step / 2) / step * step
        return snapped.coerceIn(MIN_FRAME_INTERVAL_MS, MAX_FRAME_INTERVAL_MS)
    }

    /**
     * 0 显示成「不节流」，其余显示成 `间隔 200ms`。
     * 放在这里而不是 UI 层，是为了让文案口径可被单测锁定。
     */
    fun frameIntervalLabel(millis: Long): String =
        if (millis <= MIN_FRAME_INTERVAL_MS) "不节流" else "间隔 ${millis}ms"

    /**
     * 估算节流后的识别频率，用于设置面板上的「≈N 帧/秒」提示。
     * 相机实际供帧约 30fps，间隔为 0 时返回 30。
     */
    fun estimatedFramesPerSecond(frameIntervalMs: Long): Int {
        if (frameIntervalMs <= 0L) return 30
        val fps = 1000.0 / frameIntervalMs
        return fps.coerceAtMost(30.0).toInt().coerceAtLeast(1)
    }

    /**
     * 帧间隔节流的判定：距上次真正送检过去的时间是否已达到间隔。
     *
     * 抽成无状态纯函数，便于单测与在 Analyzer 中直接调用；
     * [lastAcceptedMs] 为 0（尚未送检过）时恒为 true，保证首帧立刻识别。
     */
    fun shouldAnalyzeFrame(nowMs: Long, lastAcceptedMs: Long, frameIntervalMs: Long): Boolean {
        if (frameIntervalMs <= 0L) return true
        if (lastAcceptedMs <= 0L) return true
        return nowMs - lastAcceptedMs >= frameIntervalMs
    }
}
