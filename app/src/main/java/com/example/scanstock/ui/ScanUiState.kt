package com.example.scanstock.ui

import android.net.Uri
import com.example.scanstock.domain.ChecksumPolicy
import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.CopyFormat
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DedupeTracker
import com.example.scanstock.domain.DuplicateAlert
import com.example.scanstock.domain.ScanItem
import com.example.scanstock.domain.ScanTuning

/**
 * 屏幕的唯一状态源。
 *
 * 约定：UI 只读它，所有写入都必须走 ScanViewModel 里的 `_uiState.update {}`，
 * 保证任何线程调用都是安全的（MutableStateFlow.update 是 CAS 循环）。
 */
data class ScanUiState(
    /** 已识别的结果列表，顺序即屏幕顺序 */
    val items: List<ScanItem> = emptyList(),

    // ---- 相机 ----
    val torchOn: Boolean = false,
    val zoomRatio: Float = 1f,
    val maxZoomRatio: Float = 1f,
    val hasFlashUnit: Boolean = true,

    /**
     * 自动变焦（ML Kit auto-zoom）。默认开启。
     *
     * 画面里的码都太远、解不出来时，ML Kit 会回调一个建议倍数，App 拿它调相机变焦。
     * 这是识别率的主战场——远处小码解不出，多数时候只是因为画面里码太小。
     * 手动双击缩放优先级更高：用户一旦手动调过，本次就以手动值为准。
     */
    val autoZoomEnabled: Boolean = true,

    // ---- 识别引擎调优（v1.4）----
    /**
     * 分析帧分辨率档位。默认 [ScanTuning.AnalysisProfile.BALANCED]（720p，= v1.3 行为）。
     *
     * 提到 1080p / 1440p 能让**小二维码、高版本复杂码**在分析帧里占更多像素，
     * 显著提升解码成功率，代价是单帧耗时上升、耗电发热增加。
     * 切换本项会重建相机绑定（CameraX 的分析分辨率绑定后不可变）。
     */
    val analysisProfile: ScanTuning.AnalysisProfile = ScanTuning.AnalysisProfile.DEFAULT,

    /**
     * 增强识别（远距 / 小码）。默认关闭，保持与 v1.3 一致的行为。
     *
     * 打开后：① 若当前档位低于 1080p，自动抬到 1080p；
     * ② 把可用变焦上限抬到至少 [ScanTuning.BOOST_MIN_ZOOM]（受硬件真实上限约束）。
     */
    val enhancedRecognition: Boolean = false,

    /**
     * 分析帧间隔（「识别间隙」）。0 = 不节流，每帧都送识别引擎（= v1.3 行为）。
     *
     * 每 N 毫秒才送一帧给 ML Kit，用于省电、降温、降低 CPU 占用。
     * 与 [dedupeWindowMs]（同码重复识别间隔）是两个独立参数，互不影响。
     */
    val frameIntervalMs: Long = ScanTuning.DEFAULT_FRAME_INTERVAL_MS,

    // ---- 识别准确率（v1.5）----

    /**
     * 校验位自校验口径，默认 [ChecksumPolicy.ENFORCE]（开启）。
     *
     * 与分辨率/帧间隔那几项不同，本项**默认就开**：它是纯算术、零性能代价，
     * 而收益是直接挡掉「解错一位」的误识——盘点场景里误识比漏识致命得多。
     * 没有可校验校验位的码制（Code-128 / QR / Data Matrix）不受影响，一律放行。
     */
    val checksumPolicy: ChecksumPolicy = ChecksumPolicy.DEFAULT,

    /**
     * ROI 二次精读（v1.5），默认开启。
     *
     * 粗检只拿到「疑似条码但解不出」的候选框时，把那一小块裁出来放大再解一次。
     * 依据是 ML Kit 官方对输入图的要求：除了「最小单元 ≥2px」，还要求
     * **条码占据输入图的绝大部分**——把码所在的小块单独送去识别，正是在满足这一条。
     *
     * 相比于继续抬全帧分辨率，它解码的像素量只有全帧的几十分之一，
     * 却能让目标在输入图里从「占 5%」变成「占大部分」。
     *
     * 只在粗检出「候选但未解码」时触发，不影响正常扫到码的快路径。
     */
    val roiRefineEnabled: Boolean = true,

    /**
     * 第二引擎（ZXing）补扫开关（v1.7），默认开启。
     *
     * 小码精读用 ML Kit 解不出时，把同一张裁块交给 ZXing 再解一次。
     * 两个引擎失败模式互补（见 `camera/ZxingDecoder`），代价只是一次几十毫秒的解码。
     */
    val zxingFallbackEnabled: Boolean = true,

    // ---- 重复码提醒（v1.7）----

    /**
     * 扫到列表里已有的码时顶部轻提示，默认开启。
     *
     * 严格去重模式下重复码原本是**静默丢弃**的，用户分不清「已经扫过」和
     * 「相机没识别出来」，只能反复凑近晃动。提示把「第几行、第一次扫到是什么时候」
     * 直接说清楚。提示频率由 [DuplicateMonitor] 的两级冷却控制（单码 3s、全局 1.2s），
     * 不会变成连续弹窗。
     *
     * 允许重复模式下恒不弹（那种模式里每个码都算新记录，提示纯噪音）。
     */
    val duplicateAlertEnabled: Boolean = true,

    /**
     * 当前待展示的重复码提示。null 表示没有。
     *
     * 放在 StateFlow 里而不是走一次性事件，是因为它需要一个**稳定的生命周期**：
     * 弹出 → 用户点掉 / 自动消失。用 SharedFlow 的话，Activity 重建（旋转）时
     * 那次事件已经丢了，提示会凭空消失且无法恢复。
     */
    val duplicateAlert: DuplicateAlert? = null,

    // ---- 反馈设置 ----
    val feedbackEnabled: Boolean = true,
    val vibrateEnabled: Boolean = true,
    val beepEnabled: Boolean = true,

    // ---- 导出设置 ----
    /** 「复制全部」的输出格式。默认纯文本（每行只有码值），最通用 */
    val copyFormat: CopyFormat = CopyFormat.PLAIN,

    // ---- 重复码口径 ----
    /**
     * 重复码的处理口径，默认 [DedupeMode.STRICT]。
     *
     * 三种口径的共同点：**时间窗防刷屏都生效**——持续对准一个码不会一秒记几十条。
     * 模式只决定「重复的那个码算不算、怎么算」，详情见 [DedupeMode]。
     */
    val dedupeMode: DedupeMode = DedupeMode.DEFAULT,

    /** 去重时间窗。三种模式下都生效（防刷屏 + 控制重复录入的间隔） */
    val dedupeWindowMs: Long = DedupeTracker.DEFAULT_WINDOW_MS,

    // ---- 准入规则 ----
    /**
     * 条码准入白名单（前缀 / 长度 / 正则）。默认关闭（全部放行）。
     * 用来挡掉画面里混进来的其它码，省得事后逐条删。
     */
    val codeRule: CodeRule = CodeRule(),

    // ---- 会话持久化 ----
    /**
     * 是否把当前会话写入本地，默认开启。
     *
     * 开启后误触返回 / 进程被回收都不会丢数据，下次启动会提示恢复。
     * 关掉则回到「纯内存」行为，适合在意数据不留存的场景。
     */
    val persistEnabled: Boolean = true,

    /**
     * 上次未收尾的会话快照。非空时 UI 弹「恢复 / 丢弃」卡片。
     * 只在冷启动读盘后设置一次，用户做出选择后立即置空。
     */
    val pendingRestore: PendingRestore? = null,

    // ---- 扫描历史（v1.8.9）----
    /**
     * 是否把每次扫码都追加进历史账本，默认开启。
     *
     * 与 [persistEnabled] 是**两件事**，不要合并：
     * - [persistEnabled] 管的是「当前会话要不要留个快照」——关掉是为了数据不留在本机；
     * - 本项管的是「扫过的码要不要留档」——关掉之后此前的历史一条不动，仍可搜索。
     *
     * 默认开，且不随进程重启恢复（本项目所有设置项都一样，会话级生效）。
     */
    val historyEnabled: Boolean = true,

    // ---- 运行日志 / 崩溃报告（v1.6）----
    /**
     * 本机已记录的崩溃报告份数。大于 0 时主屏顶部弹一条「上次闪退了，把日志发我」的横幅。
     *
     * 为什么要在界面上提这件事：日志不主动提示就等于不存在——用户不会想到去设置里翻。
     */
    val crashReportCount: Int = 0,

    /** 日志概况文案（大小 + 崩溃份数），设置面板与横幅共用，由 AppLog.summary() 生成 */
    val logSummary: String = "",

    /** 用户点了横幅上的「忽略」之后不再打扰本次启动 */
    val crashNoticeDismissed: Boolean = false,

    // ---- 瞬时提示（由 UI 消费，不参与业务）----
    /** 最近一次计入的码值，用于预览区顶部的提示条 */
    val lastValue: String? = null,
    /** 每次成功计入 +1，UI 用它触发边框闪动动画 */
    val flashTick: Int = 0,
    /** 每次「疑似条码但未解码」+1，UI 用它弹「再靠近一点」提示并自动隐藏 */
    val potentialTick: Int = 0,

    /**
     * 每次「连续多帧有码却解不出」+1（v1.7），UI 用它弹「太近会失焦」提示。
     *
     * 与 [potentialTick] 分开：那条是「看到疑似条码」，这条是「看到很多次却一直解不出」，
     * 后者才说明是**对焦距离**的问题，提示语也不同。
     */
    val focusHintTick: Int = 0,
    /** 相册图片解析中 */
    val busy: Boolean = false,
    /** 设置面板是否展开 */
    val sheetOpen: Boolean = false
) {
    val count: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 数量合计（计数聚合模式下的真实件数）。
     * 非计数模式下恒等于 [count]，所以顶部统计条两种模式下都能直接用它。
     */
    val totalQty: Int get() = items.sumOf { it.qty.coerceAtLeast(1) }
}

/**
 * 待恢复的会话快照。
 *
 * 只带「恢复到列表里所需」的最小信息：条目、下一个序号、当时的口径与规则
 * （口径要一起恢复，否则恢复后继续扫会因为口径不同而行为突变）。
 */
data class PendingRestore(
    val items: List<ScanItem>,
    val nextSeq: Int,
    val mode: DedupeMode,
    val rule: CodeRule,
    val savedAtMillis: Long
)

/** 一次性事件：不进 StateFlow，避免旋转屏幕后重放（例如重复弹「已复制」） */
sealed interface UiEvent {

    /**
     * 请求把文本写入剪贴板，由 UI 层执行（ViewModel 不碰系统服务）。
     *
     * @param itemCount 本次复制的条目数
     * @param formatLabel 实际使用的格式名，随事件一起下发。
     *                    以前由 UI 在消费时现读 `uiState.copyFormat`——用户先点复制、
     *                    再改格式时，提示语会写出一个**并没有真的用到**的格式名
     * @param isSingle  true 表示单条复制。单条的内容与「复制全部」的格式设置**无关**
     *                  （永远只有码值本身），因此提示语要与「复制全部」分开写，
     *                  否则会给用户「单条也套用了 CSV 格式」的错误暗示。
     */
    data class CopyToClipboard(
        val text: String,
        val itemCount: Int,
        val formatLabel: String,
        val isSingle: Boolean = false
    ) : UiEvent

    /**
     * 请求拉起系统分享面板，由 UI 层执行（ViewModel 不持 Context 之外的东西）。
     *
     * @param uri     FileProvider 生成的 content:// Uri，只授权这一次分享
     * @param summary 一句规模说明，如「共 128 条 / 数量合计 205」
     */
    data class ShareExport(
        val uri: Uri,
        val mime: String,
        val fileName: String,
        val summary: String
    ) : UiEvent

    /**
     * 删除后的可撤销提示。
     *
     * @param token 本次删除操作的**唯一令牌**，UI 点「撤销」时原样回传给 ViewModel。
     *
     * 为什么不是回传被删条目的 seq：v1.6 起删除后会**重排序号**，被删条目的 seq
     * 在重排后就变了，用它做校验会立刻失效。令牌是单调递增的计数器，
     * 与列表内容无关；ViewModel 用它判断「这条 Snackbar 还是不是最新一次删除的」，
     * 避免用户在新一次删除之后点了上一条的撤销、却恢复了另一条数据。
     */
    data class UndoableMessage(val text: String, val token: Int) : UiEvent

    /** 普通提示 */
    data class Message(val text: String) : UiEvent
}
