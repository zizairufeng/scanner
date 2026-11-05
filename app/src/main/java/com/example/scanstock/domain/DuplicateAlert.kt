package com.example.scanstock.domain

/**
 * 「又扫到同一个码」的轻提示内容（v1.7 引入，v1.8.1 从模态 AlertDialog改成顶部轻提示条）。
 *
 * ## 为什么需要它
 *
 * 严格去重模式下，重复码是被**静默丢弃**的：用户对准一个已经扫过的码，
 * 屏幕没有任何反应，很容易误以为「相机没识别出来」，于是继续凑近、反复晃动，
 * 白白浪费时间。提示条把「这个码已经扫过了、在第几行、算没算」说清楚，
 * 用户就能立刻改去扫下一个。
 *
 * ## 为什么最终只留两行字
 *
 * 第一版是模态 AlertDialog，写完实测发现它把连续盘点的节奏**整块切断**了：
 * 连扫时没人在屏幕中间点「知道了」，6 秒自动消失又意味着这 6 秒里画面被挡住。
 * 现在改成顶部一条 50dp 上下的小条、2.5 秒自己消失、不拦截任何触摸——
 * 能读就读一眼，读不完说明这条本来也不重要，列表里随时能查。
 * 因此文案只保留「结论 + 码值 + 落在第几行」，首次时间这类需要细看的信息全部砍掉。
 *
 * ## 为什么不在 domain 里放 Android 类型
 *
 * 本类只带**已经算好的字符串**，不持有时间格式化器、不碰 Context，因此能纯 JVM 单测。
 *
 * @param value        码值原文
 * @param rowNumber    该码在结果列表里的行号（1 起）。0 表示查不到（理论上不该发生）
 * @param qty          该行当前数量。只有计数聚合模式会大于 1
 * @param scanTimes    本会话第几次扫到这个码（含第一次，最小 2）
 * @param mode         当前重复码口径，决定「这次到底算不算」
 */
data class DuplicateAlert(
    val value: String,
    val rowNumber: Int,
    val qty: Int,
    val scanTimes: Int,
    val mode: DedupeMode
) {

    /** 这次是否被严格去重挡掉了（没有计入列表） */
    val blocked: Boolean get() = mode == DedupeMode.STRICT

    /**
     * 标题：一句话说清结论，用户扫一眼就知道该不该继续对准。
     *
     * 计数聚合把数量直接写进标题（「已累计 ×8」）——这条轻提示只停留两秒多，
     * 副标题已经腾给码值和位置，再让用户自己换算「这行到底几件」就来不及了。
     */
    fun title(): String = when (mode) {
        DedupeMode.STRICT -> "重复码 · 未计入"
        DedupeMode.COUNT -> "重复码 · 已累计 ×$qty"
        DedupeMode.ALLOW -> "重复码 · 已重复"
    }

    /**
     * 副标题：码在列表里的落点与扫到的次数。
     *
     * 首次时间刻意不放了：那串毫秒时间戳在 2.5 秒里根本读不完，
     * 用户真要核对时可以回列表看那一行，这里的价值只是「去哪儿找」。
     */
    fun detail(): String {
        val where = if (rowNumber > 0) "第 $rowNumber 行" else "已在列表"
        return "$where · 第 $scanTimes 次"
    }
}

/**
 * 重复码的「次数统计 + 提示节流」（v1.7，纯逻辑、可单测）。
 *
 * ## 为什么节流是必须的
 *
 * 时间窗去重（[DedupeTracker]）只保证**同一秒内不重复上报**，但用户如果把手机
 * 一直对着同一个码，每过一个时间窗（默认 1.5s）就会再来一次重复判定。
 * 若每次都提示，屏幕会被连续不断的提示条淹没。
 *
 * 于是这里做两级冷却：
 *  - **单码冷却**：同一个码两次提示至少隔 [perValueCooldownMs]（默认 3 秒）；
 *  - **全局冷却**：任意两次提示至少隔 [globalCooldownMs]（默认 1.2 秒）——
 *    防止货架上一排相同的码被逐个扫过时连续提示。
 *
 * 注意 `recordAndShouldAlert` **无论是否提示都会累加命中次数**：
 * 「第 N 次扫到」这句话要说得准，就不能只数弹出来的那几次。
 */
class DuplicateMonitor(
    private val perValueCooldownMs: Long = DEFAULT_PER_VALUE_COOLDOWN_MS,
    private val globalCooldownMs: Long = DEFAULT_GLOBAL_COOLDOWN_MS
) {

    companion object {
        /** 同一个码的提示间隔：3 秒。短于此值用户还没来得及看上一眼 */
        const val DEFAULT_PER_VALUE_COOLDOWN_MS = 3_000L

        /** 任意两次提示的全局间隔：1.2 秒 */
        const val DEFAULT_GLOBAL_COOLDOWN_MS = 1_200L

        /** 记录数超过这个规模就顺手清理已经冷却完的条目，防止长会话内存缓慢增长 */
        private const val PRUNE_THRESHOLD = 256
    }

    private class Entry(
        /** 本会话被判定为「重复」的次数（不含第一次扫码） */
        var hits: Int,
        /** 上次提示时刻；null 表示从没弹过 */
        var lastAlertAtMs: Long?
    )

    private val entries = HashMap<String, Entry>(64)

    /** 全局上次提示时刻；null 表示从没弹过 */
    private var globalLastAlertAtMs: Long? = null

    /**
     * 记录一次重复命中，并回答「这次该不该提示」。
     *
     * @param value 码值
     * @param nowMs 单调时钟（elapsedRealtime），由调用方注入，便于单测
     */
    fun recordAndShouldAlert(value: String, nowMs: Long): Boolean {
        if (value.isEmpty()) return false
        val entry = entries.getOrPut(value) { Entry(hits = 0, lastAlertAtMs = null) }
        entry.hits += 1

        val sinceValue = entry.lastAlertAtMs?.let { nowMs - it }
        if (sinceValue != null && sinceValue < perValueCooldownMs) return false

        val sinceGlobal = globalLastAlertAtMs?.let { nowMs - it }
        if (sinceGlobal != null && sinceGlobal < globalCooldownMs) return false

        entry.lastAlertAtMs = nowMs
        globalLastAlertAtMs = nowMs
        maybePruneLocked(nowMs)
        return true
    }

    /** 本会话这个码被重复命中的次数（不含第一次扫码） */
    fun hitsOf(value: String): Int = entries[value]?.hits ?: 0

    /**
     * 忘记某个码。
     *
     * 用户从列表里删掉一条后立刻重扫，这次不该被当成「重复」，所以删除时要同步清掉。
     */
    fun forget(value: String) {
        entries.remove(value)
    }

    /** 清空列表 / 换口径时调用：口径变了，「重复」的语义也变了 */
    fun reset() {
        entries.clear()
        globalLastAlertAtMs = null
    }

    private fun maybePruneLocked(nowMs: Long) {
        if (entries.size < PRUNE_THRESHOLD) return
        val deadline = perValueCooldownMs * 20
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val last = it.next().value.lastAlertAtMs
            if (last != null && nowMs - last > deadline) it.remove()
        }
    }
}
