package com.example.scanstock.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.example.scanstock.domain.CropRect
import com.example.scanstock.domain.DetectedCode
import com.example.scanstock.domain.FrameCostMeter
import com.example.scanstock.domain.PerfSnapshot
import com.example.scanstock.domain.RefineBudget
import com.example.scanstock.domain.RoiPlan
import com.example.scanstock.domain.RoiPlanner
import com.example.scanstock.domain.ScanTuning
import com.example.scanstock.util.ImageEnhance
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * CameraX 分析器：把一帧 YUV 图像交给 ML Kit，解析成纯 Kotlin 的 [DetectedCode]。
 *
 * ## 为什么这样写才能「连续扫描不暂停」
 *
 * 1. **预览与分析是两个独立的 UseCase**。ImageAnalysis 慢，只会丢帧，
 *    不会让 Preview 掉帧——所以预览永远是流畅的实时画面。
 *
 * 2. **背压闸门 = imageProxy.close()**。我们把 close() 放在
 *    `addOnCompleteListener` 里，也就是 ML Kit 出结果之后才放行下一帧。
 *    配合 `STRATEGY_KEEP_ONLY_LATEST`，CameraX 会自动丢弃中间帧、
 *    只留最新的一帧，绝不会出现多个 process() 并发跑同一份缓冲区。
 *
 * 3. **不在回调里碰 UI**。onResult 只是把不可变数据丢给 ViewModel，
 *    ViewModel 用 StateFlow.update {} 更新状态（线程安全）。
 *
 * 4. **v1.4 新增帧间隔节流**。见 [frameIntervalMs]：未到间隔的帧直接 close 丢弃，
 *    连 `InputImage` 都不构造。默认 0 = 不节流，行为与 v1.3 完全一致。
 *
 * 5. **v1.5 新增 ROI 二次精读**。见 [roiRefineEnabled] 与 [runRoiPass]。
 *
 * 6. **v1.8.2 给补救链加总预算**。见 [refinePass] 与 [PIPELINE_BUDGET_MS]：以前只有
 *    「单次超过 150ms 就停」这道按级判的闸门，五级累加仍可到 400~700ms，
 *    这段时间新帧全被丢掉，于是「码越难扫画面越卡」。现在整条链共用一个总预算。
 *
 * ## ⚠️ 不要换成 CameraX 的 MlKitAnalyzer
 *
 * ML Kit 官方的 autozoom（`ZoomSuggestionOptions` 的 `ZoomCallback`）**只在**
 * `Detector.process(image: InputImage)` 这条路径上被处理。
 * 而 `MlKitAnalyzer` 接受的是 `List<Detector<*>>`，泛型擦除后内部统一走
 * `Detector.process(image: Image, rotation: Int, transform: Matrix)` 这条泛化路径，
 * 于是回调**永远不会触发**——相机不再自动变焦，而且不报任何错。
 *
 * `MlKitAnalyzer` 是 final 的、内部方法私有，无法覆写，只能复制源码改写。
 * 本类是自写的，直接调 `scanner.process(inputImage)`，走的是正确的那条路。
 * **为「简化代码」换成 MlKitAnalyzer 是一次静默的功能退化。**
 *
 * 线程模型：analyze() 在分析 Executor 上执行；ML Kit 的 Task 回调我们**显式指定**
 * 在 [workExecutor] 上执行——因为回调里可能要同步等待二次识别，
 * 留在默认的（主线程）回调上会直接卡住 UI。
 * [frameIntervalMs] 与 [roiRefineEnabled] 都是 `@Volatile`，允许设置面板在任意线程改。
 */
class BarcodeAnalyzer(
    private val scanner: BarcodeScanner,
    private val onResult: (List<DetectedCode>) -> Unit,
    private val onError: (Throwable) -> Unit,
    /**
     * 分析帧间隔（ms）。0 表示每帧都送识别引擎（v1.3 行为）。
     * 由设置里的「识别间隙」调节，用于省电、降温、降低 CPU 占用。
     */
    @Volatile var frameIntervalMs: Long = ScanTuning.DEFAULT_FRAME_INTERVAL_MS,

    /**
     * ROI 二次精读开关（v1.5，默认开启）。
     *
     * 粗检只拿到「疑似条码但未解码」的候选框时，把那块区域单独裁出来放大再解一次。
     * 依据见 [RoiPlanner] 的说明：ML Kit 除了要求「最小单元 ≥2px」，
     * 还要求**条码占据输入图的绝大部分**，而把候选区域单独送去识别正是在满足这一条。
     */
    @Volatile var roiRefineEnabled: Boolean = true,

    /**
     * 第二引擎（ZXing）补扫开关（v1.7），默认开启。
     *
     * 只在「ML Kit 对裁出来的小块也没解出」时才用，走的是同一张已经裁好、放大的位图。
     * 两个引擎的失败模式互补，见 [ZxingDecoder] 的说明。关掉它不影响主链路。
     */
    @Volatile var zxingFallbackEnabled: Boolean = true,

    /**
     * 「离太近会失焦」提示回调（v1.7）。
     *
     * 触发条件：连续 [FOCUS_HINT_THRESHOLD] 帧都「框里有码但解不出」。
     * 手机镜头的最近对焦距离普遍在 10~12cm，贴身去扫细密小码是最常见的失误，
     * 而用户看不到任何提示只会把手机贴得更近——这条提示比任何算法都省时间。
     */
    private val onFocusHelp: () -> Unit = {},

    /**
     * 执行「裁剪 + 二次识别」的线程。
     *
     * 必须是非主线程：二次识别要**同步等待** ML Kit 的结果（[Tasks.await]），
     * 在主线程上等待会直接阻塞 UI。
     */
    private val workExecutor: Executor
) : ImageAnalysis.Analyzer {

    /**
     * 上一次真正送检的时刻（`elapsedRealtime`）。
     *
     * 用 [AtomicLong] 而非普通 `Long`：analyze() 跑在分析 Executor 线程，
     * 而读取/重置可能来自主线程（相机重启、切档），跨线程读写要保证可见性。
     * 0 表示「尚未送检过」，此时恒放行，保证首帧立刻识别。
     */
    private val lastAnalyzedAtMs = AtomicLong(0L)

    /** 被节流丢弃的帧数，供调试与设置面板展示实际省下的算力。 */
    private val skippedFrames = AtomicLong(0L)

    /** 触发过精读的帧数。用于评估「候选多不多」——若长期为 0，说明这条通路没派上用场。 */
    private val roiPassCount = AtomicLong(0L)

    /** 触发过中心区补扫的次数（v1.7）。 */
    private val centerPassCount = AtomicLong(0L)

    /** ZXing 补扫成功的次数（v1.7）。长期为 0 说明这个引擎在当前码制组合下没贡献。 */
    private val zxingHitCount = AtomicLong(0L)

    /** 触发过 DPM 阶梯的帧数（v1.8）。 */
    private val dpmPassCount = AtomicLong(0L)

    /** DPM 阶梯解出码的次数（v1.8）。 */
    private val dpmHitCount = AtomicLong(0L)

    /**
     * DPM 阶梯的游标：**下一次该先试哪一级**（v1.8.2 起是唯一的顺序来源）。
     *
     * 加总预算之后一轮阶梯扫不完九级，所以必须记住位置，下一轮接着往下扫。
     * 命中时把游标移到命中的那一级——这样连续扫同款货第一级就中，
     * 而换成别的尺寸时，未命中的轮次会把游标一路推下去，1~2 秒覆盖全部尺寸。
     *
     * ⚠️ 不要再用一个「上次命中级别」的独立变量来决定起点：那样只要它一直有效，
     * 每轮都从同一级开始，换了尺寸的码所在的级别**永远轮不到**（不报错，只是扫不出）。
     * v1.8.2 之前正是这么写的，本次改掉。
     */
    private val dpmCursor = AtomicInteger(0)

    /** 单帧吞吐统计（v1.8.2）：诊断角标从这里读，见 [perfSnapshot] */
    private val frameMeter = FrameCostMeter()

    /** 上次 DPM 阶梯的时刻。阶梯本身不便宜，按 [DPM_PASS_INTERVAL_MS] 节流 */
    private val dpmPassAtMs = AtomicLong(0L)

    /** 上次中心区补扫的时刻。用它节流：对着空白区域持续失败时不必每帧重解 */
    private val centerPassAtMs = AtomicLong(0L)

    /** 连续「有候选框但一码未解」的帧数，到阈值触发「太近会失焦」提示 */
    private val consecutiveMisses = AtomicInteger(0)

    /** 上次「太近会失焦」提示的时刻 */
    private val lastFocusHintAt = AtomicLong(0L)

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        // ---- 帧间隔闸门：没到间隔就把这帧原样放行给下一帧，绝不占着缓冲区 ----
        val interval = frameIntervalMs
        if (interval > 0L) {
            val now = android.os.SystemClock.elapsedRealtime()
            if (!ScanTuning.shouldAnalyzeFrame(now, lastAnalyzedAtMs.get(), interval)) {
                skippedFrames.incrementAndGet()
                imageProxy.close()
                return
            }
            lastAnalyzedAtMs.set(now)
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            // 极少数机型在切换镜头瞬间会给出空 Image，直接放行即可，不能卡住下一帧
            imageProxy.close()
            return
        }

        // rotationDegrees 由 CameraX 根据设备姿态与 targetRotation 计算好，
        // 传进去后 ML Kit 会自动摆正图像，横竖屏切换无需我们做任何矩阵变换。
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        // 性能记账的起点（v1.8.2）：从 ML Kit 主趟开始前，量到缓冲区释放。
        // 这段包含了主趟 + 整条补救链，正是用户感知到的「这一帧占用了多久」。
        val frameStartedAt = SystemClock.elapsedRealtime()

        scanner.process(inputImage)
            // ★ 显式指定 workExecutor：见类注释，回调里可能会同步等待二次识别
            .addOnCompleteListener(workExecutor) { task ->
                try {
                    if (!task.isSuccessful) {
                        // 单帧失败不是致命错误（常见于镜头切换/图像损坏），只上报不中断
                        task.exception?.let { onError(it) }
                        return@addOnCompleteListener
                    }

                    val barcodes = task.result.orEmpty()
                    val primary = barcodes.map(::toDetectedCode)

                    // ---- 第二段：精读（v1.5 候选框 → v1.7 中心区 + 第二引擎）----
                    // 只在「一张码都没解出来」时才走，正常扫到码的快路径完全不受影响。
                    val refined = if (primary.any { !it.potentialOnly }) {
                        consecutiveMisses.set(0)
                        emptyList()
                    } else {
                        refinePass(barcodes, imageProxy, rotationDegrees)
                    }

                    if (refined.isNotEmpty()) {
                        consecutiveMisses.set(0)
                    } else if (primary.isNotEmpty()) {
                        // 有候选框却一码未解：可能太近/太抖/太斜，累计到阈值就提示用户
                        noteMiss()
                    }

                    onResult(if (refined.isEmpty()) primary else primary + refined)
                } catch (t: Throwable) {
                    // 精读属于「锦上添花」，任何异常都不该吞掉已经拿到的主结果
                    onError(t)
                } finally {
                    // 无论成功失败都必须释放，否则 CameraX 永远不会送来下一帧
                    val ended = SystemClock.elapsedRealtime()
                    frameMeter.record(endAtMs = ended, costMs = ended - frameStartedAt)
                    imageProxy.close()
                }
            }
    }

    // ──────────────────────── 精读管线（v1.7）────────────────────────

    /**
     * 一次精读尝试的结果。
     *
     * 带上耗时是因为**慢的时候要主动放弃后续尝试**：解密一张裁块若已经花了 200ms，
     * 再叠三次尝试就会让画面变成幻灯片，而连续扫描的手感比多救一个码更重要。
     */
    private data class PassOutcome(val codes: List<DetectedCode>, val elapsedMs: Long)

    /**
     * 全帧解不出时的补救管线，按「代价从低到高」依次尝试，命中即返回：
     *
     * ```
     * ① 候选框精读（v1.5）           —— 粗检给了「疑似条码」的框
     * ② 同一块 + 对比度拉伸 + 双引擎  —— 反光 / 低对比度导致的解不出
     * ③ 中心区补扫（v1.7）           —— 粗检连候选框都没给的情况
     * ④ 中心区 + 拉伸 + 双引擎        —— ③ 的兜底
     * ⑤ DPM 阶梯（v1.8）             —— 金属蚀刻码，ML Kit 从不给框
     * ```
     *
     * ## 三道闸门（v1.8.2）
     *
     * 1. **总预算** [PIPELINE_BUDGET_MS]：整条链共用。任何一级开始前先问「剩下还够不够」，
     *    不够就收手、把这一帧放走。这是「码越难扫、画面越卡」的正面解法——
     *    以前只有按单级判的那道闸门，五级累加能到 400~700ms，期间新帧全被丢弃；
     * 2. **按单次耗时** [SLOW_PASS_MS]：一次就超过 150ms，说明这张图本身难解，
     *    同一条路上加码（拉伸、二值化）收益很低，不再试第二次；
     * 3. **按场景分流**：有候选框 ⇒ 码对 ML Kit 是可见的，DPM 阶梯那套
     *    「反色 + 紧贴裁剪」的前提（连框都没有）不成立，直接跳过，省下最贵的一段。
     *
     * ③ 另有 [CENTER_PASS_INTERVAL_MS] 的时间节流；注意它**不再连坐 DPM 阶梯**
     * （v1.8.2 修正）：以前中心区被节流时整个函数提前 return，DPM 也跟着不跑，
     * 实际变成了「中心区 + DPM」两道节流串联，DPM 最快也要 ~500ms 才动一次。
     *
     * 开关归属也一并理顺（v1.8.2）：①~④ 归 [roiRefineEnabled]（小码精读），
     * ⑤ 归 [zxingFallbackEnabled]（双引擎补扫）。此前 ⑤ 挂在函数开头那道
     * `if (!roiRefineEnabled) return` 之下，等于关掉精读就顺带失去金属蚀刻码能力。
     */
    private fun refinePass(
        barcodes: List<com.google.mlkit.vision.barcode.common.Barcode>,
        imageProxy: ImageProxy,
        rotationDegrees: Int
    ): List<DetectedCode> {
        val budget = RefineBudget(SystemClock.elapsedRealtime(), PIPELINE_BUDGET_MS)

        // 「小码精读」开关只管 ①~④（按候选框 / 中心区的裁块重解）。
        // ⑤ DPM 阶梯归「双引擎补扫」管，见 [runDpmLadder]——两者语义不同，
        // 不能让关掉精读的人顺带失去金属蚀刻码能力（v1.8.2 修正）。
        val candidate = if (roiRefineEnabled) {
            pickRoiPlan(barcodes, imageProxy, rotationDegrees)
        } else {
            null
        }

        // ① 候选框精读 → ② 同一块 + 拉伸 + 双引擎
        if (candidate != null) {
            roiPassCount.incrementAndGet()
            val first = runRoiPass(imageProxy, candidate, enhance = false)
            if (first.codes.isNotEmpty()) return first.codes
            if (first.elapsedMs <= SLOW_PASS_MS && hasRoom(budget, reserveMs = 0L)) {
                val second = runRoiPass(imageProxy, candidate, enhance = true)
                if (second.codes.isNotEmpty()) return second.codes
            }
        }

        // 没有候选框 ⇒ 后面还有 DPM 阶梯要跑，尽量给它留出时间：
        // 不留的话中心区那两遍会把预算吃干净，阶梯连头都开不了（v1.8 的能力就丢了）。
        // 注意这是**尽力而为**：预留只在「开始某一级之前」判，单次尝试本身仍可能超支，
        // 极端情况下阶梯拿不到时间——下一帧会再试，且阶梯自己的节流时钟不会被白占。
        val dpmReserve = if (candidate == null) DPM_RESERVE_MS else 0L

        // ③ 中心区补扫 → ④ 中心区 + 拉伸
        if (roiRefineEnabled && hasRoom(budget, dpmReserve) && centerPassGatePasses()) {
            val center = RoiPlanner.centerPlan(
                bufferWidth = imageProxy.width,
                bufferHeight = imageProxy.height,
                rotationDegrees = rotationDegrees
            )
            if (center != null) {
                centerPassCount.incrementAndGet()
                val centerFirst = runRoiPass(imageProxy, center, enhance = false)
                if (centerFirst.codes.isNotEmpty()) return centerFirst.codes
                if (centerFirst.elapsedMs <= SLOW_PASS_MS && hasRoom(budget, dpmReserve)) {
                    val centerSecond = runRoiPass(imageProxy, center, enhance = true)
                    if (centerSecond.codes.isNotEmpty()) return centerSecond.codes
                }
            }
        }

        // ⑤ DPM 阶梯：前提是「ML Kit 连候选框都没给」。给了框说明是常规可视图码，
        //    反色 + 紧裁那套对它没用，跳过能省下最贵的一段（九级裁剪 + 解码）。
        if (candidate != null) return emptyList()

        val remaining = budget.remaining(SystemClock.elapsedRealtime())
        if (remaining < MIN_PASS_MS) return emptyList()
        return runDpmLadder(imageProxy, rotationDegrees, remaining)
    }

    /** 扣除给后续阶段的预留后，剩余预算还够不够再开一次尝试 */
    private fun hasRoom(budget: RefineBudget, reserveMs: Long): Boolean =
        budget.canStart(MIN_PASS_MS + reserveMs, SystemClock.elapsedRealtime())

    /** 中心区补扫的时间节流：放行时记账并返回 true */
    private fun centerPassGatePasses(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val last = centerPassAtMs.get()
        if (last != 0L && now - last < CENTER_PASS_INTERVAL_MS) return false
        centerPassAtMs.set(now)
        return true
    }

    // ─────────────────────── DPM 阶梯（v1.8）───────────────────────

    /**
     * 按尺寸阶梯扫一遍画面中心，专治金属蚀刻的低对比度反色 Data Matrix。
     *
     * ## 为什么必须「扫阶梯」而不是「裁一块」
     *
     * 实测（海光 CPU 顶盖那颗 26×26 的蚀刻码）：分析区域只能比码大 **1.2~1.8 倍**，
     * 一旦超过 2 倍就彻底解不出——码周围混进大片同色背景后，
     * 全局直方图二值化的阈值被背景拉偏，整张图被二值化成一片纯色。
     *
     * 而用户不知道自己的码在画面里有多大，所以只能按 1.3 倍递进扫一遍。
     * 黄金窗口宽 1.5 倍 > 步长 1.3 倍，数学上保证**任意尺寸的码至少有一级落在窗内**。
     *
     * ## 成本控制（v1.8.2 改）
     *
     * 每级只跑 ZXing 的 DataMatrix 单码制（比多码制快一个量级），且：
     *  - 整条阶梯按 [DPM_PASS_INTERVAL_MS] 节流，不会每帧都扫；
     *  - 单帧额度取「阶梯自己的上限」与「整条补救链的剩余预算」的较小值；
     *  - 上一次命中的级别会被提到最前，扫同类货时第一级就中；
     *  - 一轮扫不完时**记住停下位置**（[dpmCursor]），下一轮接着往下扫，
     *    保证任意尺寸的码在 1~2 秒内必被扫到——这一点在加了总预算之后是必须的，
     *    否则小码需要的那几级永远等不到。
     *
     * @param budgetMs 这一轮最多能用多少毫秒（由 [refinePass] 按剩余预算算出来）
     */
    private fun runDpmLadder(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        budgetMs: Long
    ): List<DetectedCode> {
        // 阶梯完全依赖第二引擎（ML Kit 在这类码上连候选框都不给），
        // 所以它直接挂在「双引擎补扫」开关下——关掉 ZXing 就不该做这份无用功。
        if (!zxingFallbackEnabled) return emptyList()

        val now = SystemClock.elapsedRealtime()
        val last = dpmPassAtMs.get()
        if (last != 0L && now - last < DPM_PASS_INTERVAL_MS) return emptyList()
        dpmPassAtMs.set(now)
        dpmPassCount.incrementAndGet()

        val started = SystemClock.elapsedRealtime()
        val budget = budgetMs.coerceAtMost(DPM_TIME_BUDGET_MS)

        // 起点永远是游标：命中过就是那一级（同款货第一级即中），没命中过就是接着上次停下的地方
        val start = dpmCursor.get()
        val order = RoiPlanner.levelOrder(start)

        var lastTried = -1
        for ((index, level) in order.withIndex()) {
            // 至少跑一级：预算再紧也先把最可能的那一级做完，否则一轮下来什么都没试
            if (index > 0 && elapsed(started) > budget) break
            // 先记「走到了第几级」再判断能不能裁：这一级若因尺寸太小规划不出来，
            // 也算走过，否则游标永远停在原地——预算只够 3 级时，帧帧重复这 3 个废级别，
            // 后面真正能用的级别永远轮不到（不报错，只是扫不出）。
            lastTried = index

            val plan = RoiPlanner.dpmPlan(
                bufferWidth = imageProxy.width,
                bufferHeight = imageProxy.height,
                rotationDegrees = rotationDegrees,
                level = level
            ) ?: continue

            val hit = runDpmPass(imageProxy, plan) ?: continue
            dpmHitCount.incrementAndGet()
            zxingHitCount.incrementAndGet()
            // 游标停在命中这一级：下一帧同款货第一级就中，省掉其余八级的裁剪解码
            dpmCursor.set(level)
            return listOf(hit)
        }

        // 记下这一轮扫到哪儿：下一轮从这里接着往下，而不是又从第一级开始
        if (lastTried >= 0) {
            dpmCursor.set(RoiPlanner.cursorAfter(start = start, triedCount = lastTried + 1))
        }
        return emptyList()
    }

    /**
     * 跑阶梯中的一级：裁块 → 放大 → 摆正 → 交给 ZXing 的 DPM 专用管线。
     *
     * 刻意**不经过 ML Kit**：这类码 ML Kit 连候选框都不会给，多问一次纯属浪费。
     */
    private fun runDpmPass(imageProxy: ImageProxy, plan: RoiPlan): DetectedCode? {
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var upright: Bitmap? = null
        return try {
            cropped = cropYPlaneToBitmap(imageProxy, plan.crop) ?: return null
            scaled = if (plan.scale > 1.01f) {
                Bitmap.createScaledBitmap(
                    cropped,
                    plan.outputWidth.coerceAtLeast(1),
                    plan.outputHeight.coerceAtLeast(1),
                    // 双线性：蚀刻码的边缘本来就是软过渡，最近邻会把它切成锯齿
                    true
                )
            } else {
                cropped
            }

            upright = rotateIfNeeded(scaled, plan.rotationDegrees) ?: return null
            val w = upright.width
            val h = upright.height
            if (w <= 0 || h <= 0) return null

            val pixels = IntArray(w * h)
            upright.getPixels(pixels, 0, w, 0, 0, w, h)
            val hit = ZxingDecoder.decodeDpm(pixels, w, h) ?: return null
            DetectedCode(
                formatId = ZXING_FORMAT_ID,
                formatName = hit.formatName,
                value = hit.value
            )
        } catch (t: Throwable) {
            // 阶梯是加分项，任何异常都当作「这级没中」
            null
        } finally {
            if (upright != null && upright !== scaled && upright !== cropped) upright.recycle()
            if (scaled != null && scaled !== cropped) scaled.recycle()
            cropped?.recycle()
        }
    }

    // ──────────────────────────── ROI 精读 ────────────────────────────

    /**
     * 从粗检结果里挑一个值得精读的候选框。
     *
     * 只有「疑似条码但未解码」（`rawValue` 为空）的条目才是目标：
     * 已经解出来的码不需要帮忙，一个候选都没有时更是无从裁起。
     *
     * **每帧最多挑一个**，取面积最大的那个——面积大代表它在画面里更靠前、
     * 更可能是用户正在对准的目标。多裁几个会让单帧耗时线性上涨，
     * 而连扫体验对延迟很敏感。
     */
    private fun pickRoiPlan(
        barcodes: List<Barcode>,
        imageProxy: ImageProxy,
        rotationDegrees: Int
    ): RoiPlan? {
        var best: RoiPlan? = null
        var bestArea = 0L

        for (barcode in barcodes) {
            val raw = barcode.rawValue
            if (!raw.isNullOrEmpty()) continue          // 已解出，无需精读
            val box = barcode.boundingBox ?: continue   // 连框都没有就无法定位

            val plan = RoiPlanner.plan(
                boxLeft = box.left,
                boxTop = box.top,
                boxRight = box.right,
                boxBottom = box.bottom,
                bufferWidth = imageProxy.width,
                bufferHeight = imageProxy.height,
                rotationDegrees = rotationDegrees
            ) ?: continue

            val area = plan.crop.width.toLong() * plan.crop.height
            if (area > bestArea) {
                bestArea = area
                best = plan
            }
        }
        return best
    }

    /**
     * 裁出 ROI、放大、（可选）拉伸对比度，再解一次。
     *
     * 全程**不做全帧旋转**：裁出来的小块仍处于原始方向，把相机旋转角原样交给
     * `InputImage.fromBitmap(bitmap, rotationDegrees)` 让 ML Kit 自己去摆正。
     * 这样既省掉一次全帧旋转，也避开了「自己旋图 + 自己算偏移」这个最容易写错的地方。
     *
     * @param enhance true 表示先做一次灰阶拉伸再解，并交给 ZXing 兜底。
     *                只在第一次尝试失败后才走这条路（见 [refinePass]）。
     * @return 解出的码 + 本次耗时；失败或无收获时 `codes` 为空，**绝不抛异常**
     */
    private fun runRoiPass(imageProxy: ImageProxy, plan: RoiPlan, enhance: Boolean): PassOutcome {
        val started = SystemClock.elapsedRealtime()
        var cropped: Bitmap? = null
        var scaled: Bitmap? = null
        var enhanced: Bitmap? = null
        return try {
            cropped = cropYPlaneToBitmap(imageProxy, plan.crop)
                ?: return PassOutcome(emptyList(), 0L)

            scaled = if (plan.scale > 1.01f) {
                Bitmap.createScaledBitmap(
                    cropped,
                    plan.outputWidth.coerceAtLeast(1),
                    plan.outputHeight.coerceAtLeast(1),
                    // filter = true：放大时必须走双线性插值，最近邻会放大出锯齿、
                    // 反而让 1D 码的条空边界更难辨
                    true
                )
            } else {
                cropped
            }

            // 增强只在第二次尝试时做：它不创造细节，先让原图跑一遍才是对的顺序
            if (enhance) {
                enhanced = stretchedCopy(scaled)
                // 跨度过小说明这一块本来就没有可用信息，拉伸也是白拉，直接放弃
                if (enhanced == null) return PassOutcome(emptyList(), elapsed(started))
            }
            val target = enhanced ?: scaled

            var codes = decodeWithMlKit(target, plan.rotationDegrees)

            // ML Kit 没解出来 → 交给第二引擎（同一张图，只多花几十毫秒）
            if (codes.isEmpty() && zxingFallbackEnabled) {
                codes = decodeWithZxing(target, plan.rotationDegrees)
            }

            PassOutcome(codes, elapsed(started))
        } catch (t: Throwable) {
            // 精读是加分项，失败就当作没做——不打扰用户，也不污染主结果
            PassOutcome(emptyList(), elapsed(started))
        } finally {
            // 逐层回收：enhanced 与 scaled 可能是同一个对象，用引用比较避免 double recycle
            if (enhanced != null && enhanced !== scaled && enhanced !== cropped) enhanced.recycle()
            if (scaled != null && scaled !== cropped) scaled.recycle()
            cropped?.recycle()
        }
    }

    /** ML Kit 解一张（已裁好、放大的）位图 */
    private fun decodeWithMlKit(bitmap: Bitmap, rotationDegrees: Int): List<DetectedCode> =
        Tasks.await(scanner.process(InputImage.fromBitmap(bitmap, rotationDegrees)))
            .map(::toDetectedCode)
            .filter { !it.potentialOnly }

    /**
     * ZXing 解同一张位图。
     *
     * 与 ML Kit 不同的是，ZXing 的定位图案搜索假定图像是**正向**的，
     * 所以这里先把图按相机旋转角摆正（只在兜底路径上发生，一次 Matrix 变换的代价可忽略）。
     */
    private fun decodeWithZxing(bitmap: Bitmap, rotationDegrees: Int): List<DetectedCode> {
        val upright = rotateIfNeeded(bitmap, rotationDegrees) ?: return emptyList()
        return try {
            val w = upright.width
            val h = upright.height
            if (w <= 0 || h <= 0) return emptyList()
            val pixels = IntArray(w * h)
            upright.getPixels(pixels, 0, w, 0, 0, w, h)
            val hit = ZxingDecoder.decode(pixels, w, h) ?: return emptyList()
            zxingHitCount.incrementAndGet()
            listOf(DetectedCode(formatId = ZXING_FORMAT_ID, formatName = hit.formatName, value = hit.value))
        } catch (t: Throwable) {
            emptyList()
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
    }

    /** 按相机旋转角把裁块摆正；0 度时原样返回（不复制位图） */
    private fun rotateIfNeeded(bitmap: Bitmap, rotationDegrees: Int): Bitmap? {
        val degrees = RoiPlanner.normalizeRotation(rotationDegrees) ?: return bitmap
        if (degrees == 0) return bitmap
        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull()
    }

    /**
     * 灰阶拉伸后的一份新位图；跨度过小（本来就没信息）时返回 `null`。
     *
     * `Bitmap.createBitmap(pixels, ...)` 得到的是**不可变**位图，
     * 所以不能直接在原图上 `setPixels`，必须先读出来、改完再建。
     */
    private fun stretchedCopy(source: Bitmap): Bitmap? {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val changed = ImageEnhance.contrastStretchInPlace(pixels)
        if (changed == 0) return null
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun elapsed(startedAt: Long): Long = SystemClock.elapsedRealtime() - startedAt

    /**
     * 「有候选框但一码未解」计数：连续到阈值就提示用户「太近了会失焦」。
     * 每次提示后清零并进入冷却，避免一直弹。
     */
    private fun noteMiss() {
        val misses = consecutiveMisses.incrementAndGet()
        if (misses < FOCUS_HINT_THRESHOLD) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusHintAt.get() < FOCUS_HINT_INTERVAL_MS) return
        lastFocusHintAt.set(now)
        consecutiveMisses.set(0)
        runCatching { onFocusHelp() }
    }


    /**
     * 直接从 Y plane 裁一块出来转成灰度 `Bitmap`。
     *
     * ## 为什么不直接 `imageProxy.toBitmap()`
     *
     * 那会把**整帧**做一次 YUV→ARGB 转换（1920×1080 就是 8MB 的分配 + 一次逐像素插值），
     * 再裁掉其中 95% 扔掉——本末倒置。
     *
     * 这里只读 Y plane（亮度通道），原因有三：
     *  1. 条码识别本来就不需要颜色信息，灰度图完全够用；
     *  2. 跳过了 U/V 两个 plane 的采样与色度插值，开销降到零头；
     *  3. 只处理 ROI 那块区域，1920×1080 里取一个 200×120 的框，数据量差了两个数量级。
     *
     * 用**绝对索引** `buffer.get(i)` 而不是先 `position()` 再 `get()`：
     * 绝对读取不改变缓冲区游标，即便别处也在读同一块缓冲区也不会互相干扰。
     *
     * @return 灰度 Bitmap；`crop` 越界或 plane 异常时返回 `null`（由调用方兜住）
     */
    private fun cropYPlaneToBitmap(imageProxy: ImageProxy, crop: CropRect): Bitmap? {
        val planes = imageProxy.planes
        if (planes.isEmpty()) return null
        val plane = planes[0]

        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null
        if (crop.width <= 0 || crop.height <= 0) return null

        val width = crop.width
        val height = crop.height
        val pixels = IntArray(width * height)

        var index = 0
        for (row in 0 until height) {
            var pos = (crop.top + row).toLong() * rowStride + crop.left.toLong() * pixelStride
            if (pixelStride == 1) {
                // 紧凑排布（绝大多数设备）：步进为 1，分支最少
                for (col in 0 until width) {
                    val gray = buffer.get(pos.toInt()).toInt() and 0xFF
                    pixels[index++] = OPAQUE_GRAY or (gray shl 16) or (gray shl 8) or gray
                    pos++
                }
            } else {
                // 少数设备的 Y plane 带行内填充，必须按 pixelStride 跳采
                for (col in 0 until width) {
                    val gray = buffer.get(pos.toInt()).toInt() and 0xFF
                    pixels[index++] = OPAQUE_GRAY or (gray shl 16) or (gray shl 8) or gray
                    pos += pixelStride
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** ML Kit 的 `Barcode` → 纯 Kotlin 的 [DetectedCode]（domain/ui 层不依赖 ML Kit 类型） */
    private fun toDetectedCode(barcode: Barcode): DetectedCode {
        val raw = barcode.rawValue
        return DetectedCode(
            formatId = barcode.format,
            formatName = BarcodeFormats.nameOf(barcode.format),
            value = raw ?: "",
            // rawValue == null 说明这是「疑似条码但未解码」的潜在目标
            potentialOnly = raw.isNullOrEmpty()
        )
    }

    /**
     * 重置节流时钟。相机重启、分辨率档位切换、从后台回前台时必须调用，
     * 否则可能因为「上次送检时刻」距今不足一个间隔而白等一帧。
     */
    fun resetThrottleClock() {
        lastAnalyzedAtMs.set(0L)
        // 换分辨率档 / 相机重绑后，旧的耗时样本已经不代表新配置，一起清掉
        frameMeter.reset()
    }

    /** 被节流丢掉的帧数，用于设置面板展示实际效果。 */
    fun skippedFrameCount(): Long = skippedFrames.get()

    /** 触发过精读的帧数。 */
    fun roiPassCount(): Long = roiPassCount.get()

    /** 触发过中心区补扫的次数。 */
    fun centerPassCount(): Long = centerPassCount.get()

    /** ZXing 补扫成功次数。 */
    fun zxingHitCount(): Long = zxingHitCount.get()

    /** 触发过 DPM 阶梯的次数（v1.8）。 */
    fun dpmPassCount(): Long = dpmPassCount.get()

    /** DPM 阶梯解出码的次数（v1.8）。 */
    fun dpmHitCount(): Long = dpmHitCount.get()

    /**
     * 当前识别吞吐快照（v1.8.2）。
     *
     * 由 UI 每秒轮询，用来判断「是否慢到值得弹诊断角标」。
     * 返回的是**最近一秒**的窗口数据：没有样本时 `frames == 0`（表示还没测到，
     * 不代表 0fps——相机刚绑定、切后台都会是这个状态）。
     *
     * 轮询而不是回调：角标的显示条件需要「连续几个窗口都慢」这种跨窗口判断，
     * 回调模式会把这套状态机塞进分析线程，反而更绕。
     */
    fun perfSnapshot(): PerfSnapshot = frameMeter.snapshot(SystemClock.elapsedRealtime())

    private companion object {
        /** 不透明灰度像素的高 8 位（alpha = 255） */
        const val OPAQUE_GRAY = 0xFF shl 24

        /** ZXing 解出的码没有 ML Kit 的格式常量，用 -1 标明来源，便于日志区分 */
        const val ZXING_FORMAT_ID = -1

        /** 中心区补扫的最小间隔：250ms。比一帧长，避免每帧都重解同一块区域 */
        const val CENTER_PASS_INTERVAL_MS = 250L

        /** 单次精读超过这个耗时，就放弃后续的多次尝试，保连续扫描的帧率 */
        const val SLOW_PASS_MS = RefineBudget.SLOW_PASS

        /**
         * 整条补救链的总预算：220ms（v1.8.2）。
         *
         * ## 这个数字怎么来的
         *
         * 优化前最坏情况实测估算：①②③④⑤ 连续跑完要 400~700ms，
         * 而这段时间 [ImageProxy] 一直没释放，新帧全被丢——实际分析帧率只有 1.5~3fps。
         * 用户感受就是「越难的码越像卡死」。
         *
         * 220ms 对应的下限约 4.5fps，同时保证两件事：
         *  - 比一帧（33ms）长得多，值得做几级尝试；
         *  - 比 400ms 的 DPM 间隔短，一帧的补救不会拖到下一轮该开始的时刻。
         *
         * 为什么不更小：DPM 那类码本来就只在「一个码都没解出来」时才有机会，
         * 预算给到跑不动就等于关掉了 v1.8 的能力。
         *
         * 它是**上限**而不是硬截止：闸门只在「开始某一级之前」判，
         * 单次尝试一旦开跑就让它跑完（跑到一半掐断只会白花一次内存分配）。
         * 所以最坏耗时约为本值 + 一次尝试，量级上仍是 200ms 级而不是 700ms。
         */
        const val PIPELINE_BUDGET_MS = 220L

        /**
         * 给 DPM 阶梯预留的时间：90ms（v1.8.2）。
         *
         * 只有「ML Kit 连候选框都没给」时才会走到阶梯，那时中心区那两遍最容易
         * 把预算吃干。预留 90ms 大约够跑 3~4 级，配合轮转游标
         * （[RoiPlanner.levelOrder]）可以在一两秒内覆盖全部九个尺寸。
         */
        const val DPM_RESERVE_MS = 90L

        /**
         * 一次尝试值得开始的最小剩余时间：25ms。
         *
         * 低于这个数开一轮，必然跑到一半被截断——白做一次裁剪 + 放大 + 内存分配，
         * 不如把时间直接留给下一帧（下一帧会立即重试这条链）。
         */
        const val MIN_PASS_MS = 25L

        /**
         * DPM 阶梯的最小间隔：400ms。
         *
         * 阶梯一次最多扫九级，比中心区补扫贵得多，必须比它更保守。
         * 400ms 约等于 2.5 次/秒，用户把手机移到码上之后平均不到半秒就能出结果，
         * 同时不会把 CPU 拖满（这条路径本来只在「一个码都没解出来」时才走）。
         */
        const val DPM_PASS_INTERVAL_MS = 400L

        /**
         * 一帧内 DPM 阶梯的时间上限：180ms。
         *
         * 实际用的是「本值与 [refinePass] 算出的剩余预算」中较小的那个——
         * 阶梯是补救链的最后一段，不能自己再超支一次。
         * 超了就放弃剩下的级，下一轮（400ms 后）从停下的位置接着扫。
         */
        const val DPM_TIME_BUDGET_MS = 180L

        /** 连续多少帧「有候选但解不出」触发一次失焦提示（约 0.6 秒 @20fps） */
        const val FOCUS_HINT_THRESHOLD = 12

        /** 失焦提示的冷却时间 */
        const val FOCUS_HINT_INTERVAL_MS = 15_000L
    }
}
