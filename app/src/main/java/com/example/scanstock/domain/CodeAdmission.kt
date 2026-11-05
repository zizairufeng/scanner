package com.example.scanstock.domain

/**
 * 一帧的准入结果。
 *
 * 拆成两个列表而不是只返回「要新增的码」，是因为计数聚合模式下
 * 「重复扫到的码」不是被丢弃，而是要让已有行的数量 +1 —— 这两种结果
 * 在 ViewModel 里的处理路径完全不同（一个 append，一个 map 改 qty）。
 *
 * @param newCodes   需要**新增一行**的码，保持输入顺序
 * @param increments 需要给**已有行**累加数量的码值；仅计数聚合模式下非空。
 *                   已做同帧去重，同一个值在一帧里最多出现一次
 * @param duplicates 本帧「命中列表里已有码」的值（v1.7）。严格去重时是被丢弃的那个，
 *                   计数聚合时是会累加的那个。UI 用它弹「重复码」提醒——
 *                   在此之前这种命中是完全静默的，用户分不清「已扫过」和「没识别出来」。
 *                   允许重复模式下恒为空：那种模式里每个码都算新记录，谈不上「重复」。
 */
data class AdmissionResult(
    val newCodes: List<DetectedCode>,
    val increments: List<String>,
    val duplicates: List<String> = emptyList()
) {
    /** 这一帧是否有任何变化（新增或累加），用于决定是否触发震动/提示音与写盘 */
    fun isEmpty(): Boolean = newCodes.isEmpty() && increments.isEmpty()
}

/**
 * 「这一帧里哪些码该计入列表」的判定。
 *
 * 刻意抽成独立对象而不是写在 ViewModel 里：它包含三级去重规则，是本 App 最容易被改错的地方，
 * 放在 domain 层就能用纯 JVM 单测覆盖（ViewModel 依赖 Android，跑不了单测）。
 *
 * 三级判定，任一不通过即丢弃：
 *
 *   1. **同帧去重** —— 同一帧回调里出现多个相同 rawValue（例如一张照片里 5 个一样的二维码、
 *      一个货架面扫到同一 SKU 的多个码）只保留第一个。无条件生效。
 *
 *   2. **时间窗去重（始终生效）** —— 同一个 rawValue 在 [DedupeTracker] 的时间窗内不重复计入。
 *      作用是**防刷屏**：持续对准同一个码时不会一秒钟记几十条。
 *      它对三种模式一视同仁——切换模式绝不是「不限速逐帧留痕」的开关。
 *
 *   3. **模式判定** —— 只有过了时间窗的码才走到这里：
 *      - [DedupeMode.STRICT]：已在列表里 → 丢弃（「不再扫描重复内容」）
 *      - [DedupeMode.COUNT]：已在列表里 → 累加数量，不新增行
 *      - [DedupeMode.ALLOW]：无论是否在列表里，都新增一行
 *
 * 时间戳的写入时机见 [DedupeTracker.shouldAccept]：无论接受与否都刷新，保证滑动窗口语义。
 * 注意时间窗判定排在模式判定**之前**，因此三种模式下时间戳都会刷新。
 */
object CodeAdmission {

    /**
     * @param decoded        本帧解析出的有效码（调用方需已剔除 potentialOnly、空值与规则过滤）
     * @param mode           重复码处理口径
     * @param dedupe         时间窗去重器，**三种模式下都会被调用**
     * @param existingValues 当前列表里已有的码值集合。仅 [DedupeMode.STRICT] 与
     *                       [DedupeMode.COUNT] 需要；[DedupeMode.ALLOW] 可传空集合省一次构建
     * @param nowMs          单调时钟（`SystemClock.elapsedRealtime()`）。
     *                       必须用单调时钟而非墙上时间——用户改系统时间会让时间窗失效
     * @return 新增行与累加项，两者都可能为空（同时为空表示这一帧什么都別做）
     */
    fun admit(
        decoded: List<DetectedCode>,
        mode: DedupeMode,
        dedupe: DedupeTracker,
        existingValues: Set<String>,
        nowMs: Long
    ): AdmissionResult {
        if (decoded.isEmpty()) return AdmissionResult(emptyList(), emptyList())

        // 容量按输入的 2 倍给，避免塞入过程中触发扩容（单帧码数不多，内存开销可忽略）
        val seenInFrame = HashSet<String>(decoded.size * 2)
        val newCodes = ArrayList<DetectedCode>(decoded.size)
        val increments = ArrayList<String>(decoded.size)
        val duplicates = ArrayList<String>(1)

        for (code in decoded) {
            // 1. 同帧去重：无条件的
            if (!seenInFrame.add(code.value)) continue
            // 2. 时间窗去重：始终生效，三种模式一视同仁
            if (!dedupe.shouldAccept(code.value, nowMs)) continue
            // 3. 模式判定
            val inList = code.value in existingValues
            when {
                mode == DedupeMode.STRICT && inList -> duplicates += code.value  // 丢弃，但要让用户知道
                mode == DedupeMode.COUNT && inList -> {
                    increments += code.value
                    duplicates += code.value
                }
                else -> newCodes += code
            }
        }
        return AdmissionResult(newCodes, increments, duplicates)
    }
}
