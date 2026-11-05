package com.example.scanstock.domain

/**
 * 「补救链」的总时间预算（v1.8.2）。
 *
 * ## 为什么需要它：原来的闸门只管单次，不管累计
 *
 * v1.7 / v1.8 的精读管线里有 [SLOW_PASS] 这道闸门，但它判的是**单次尝试**的耗时：
 * 一次超过 150ms 就不再试第二次。问题是这道闸门是按级判的，而管线最多有五次尝试——
 * 每一级都「没超 150ms」，累起来却可以到 400~700ms。
 *
 * 期间 [ImageProxy] 一直没 `close()`，CameraX 又挂着 `KEEP_ONLY_LATEST`，
 * 于是**整段时间里新帧全被丢弃**。用户看到的现象很反直觉：
 * **码越难扫，画面越像卡住**——而这恰恰是最需要帧率的场景。
 *
 * 所以这里补一道「累计」闸门：整条补救链共用一个总预算，任何一级开始前先问
 * 「剩下的时间还够不够再跑一次」，不够就立刻收手，把这一帧放走。
 *
 * ## 为什么是纯逻辑
 *
 * 预算只看「起点 + 现在 + 总额」三个数，与 Android 时钟、线程都无关。
 * 抽成独立类之后，「剩 20ms 时还会不会开新一轮」这种边界能被单测钉死——
 * 这类边界靠真机手感是调不出来的。
 *
 * @param startedAtMs 补救链开始的时刻（同 [nowMs] 的时基，通常是 `elapsedRealtime`）
 * @param totalMs     这条链的总预算
 */
class RefineBudget(
    private val startedAtMs: Long,
    private val totalMs: Long
) {

    /** 已经花掉的时间。时钟回拨（理论上不该发生）按 0 处理，不返回负数 */
    fun spent(nowMs: Long): Long = (nowMs - startedAtMs).coerceAtLeast(0L)

    /** 还剩多少时间。可能为负，表示已经超出 */
    fun remaining(nowMs: Long): Long = totalMs - spent(nowMs)

    /**
     * 现在是否还值得**开始**一次尝试。
     *
     * 判「剩余 ≥ 一次尝试的典型成本」而不是「剩余 > 0」：
     * 只剩 5ms 时开一轮，一定是跑到一半被截断、白花一次内存分配，
     * 不如直接把剩下的时间留给下一帧。
     *
     * @param minCostMs 一次尝试的典型成本下限（见 `BarcodeAnalyzer.MIN_PASS_MS`）
     */
    fun canStart(minCostMs: Long, nowMs: Long): Boolean = remaining(nowMs) >= minCostMs

    companion object {
        /**
         * 单次尝试的耗时闸门（沿用 v1.7 的 `SLOW_PASS_MS`）。
         *
         * 命中这条说明这张图本身很难解，继续在同一条路上加码（增强、二值化）收益很低。
         */
        const val SLOW_PASS: Long = 150L
    }
}
