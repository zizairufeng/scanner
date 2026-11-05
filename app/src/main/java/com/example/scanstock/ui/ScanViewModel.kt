package com.example.scanstock.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scanstock.camera.BarcodeFormats
import com.example.scanstock.camera.ImageDecoder
import com.example.scanstock.data.SessionStore
import com.example.scanstock.domain.ChecksumPolicy
import com.example.scanstock.domain.ChecksumVerifier
import com.example.scanstock.domain.CodeAdmission
import com.example.scanstock.domain.CodeFilter
import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.CopyFormat
import com.example.scanstock.domain.CopyFormatter
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DedupeTracker
import com.example.scanstock.domain.DetectedCode
import com.example.scanstock.domain.DuplicateAlert
import com.example.scanstock.domain.DuplicateMonitor
import com.example.scanstock.domain.ScanItem
import com.example.scanstock.domain.ScanTuning
import com.example.scanstock.domain.SequenceRenumber
import com.example.scanstock.export.Exporter
import com.example.scanstock.feedback.FeedbackPlayer
import com.example.scanstock.logging.AppLog
import com.google.mlkit.vision.barcode.BarcodeScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 扫码主链路的唯一状态容器。
 *
 * 数据流：
 *   分析器线程 ──onCodesDetected()──▶ 规则过滤 ──▶ 准入判定 ──▶ MutableStateFlow.update{} ──▶ Compose
 *
 * 注意：ViewModel 里**没有**任何 UI 类型（View / Context 除外用于系统服务），
 * 也不直接操作剪贴板——剪贴板属于 UI 层职责，通过 [UiEvent.CopyToClipboard] 下发。
 *
 * 生命周期：跟随 Activity，旋转屏幕后 items 不丢（这是不落库也能接受的关键）。
 * 进程被回收的情况由 [SessionStore] 兜底（F4）。
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    /** extraBufferCapacity 保证 tryEmit 在无人订阅时也不丢失（如刚启动瞬间） */
    private val _events = MutableSharedFlow<UiEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private val feedback = FeedbackPlayer(application)
    private val dedupe = DedupeTracker(DedupeTracker.DEFAULT_WINDOW_MS)

    /**
     * 重复码的命中计数 + 提示节流（v1.7）。
     *
     * 与 [dedupe] 分工不同：dedupe 决定「这一帧要不要计入」，本类决定
     * 「这次重复要不要打扰用户」。两者都需要，缺一个就会出现
     * 「要么反复弹窗，要么用户完全不知道发生了什么」。
     */
    private val duplicateMonitor = DuplicateMonitor()
    // 传 filesDir 而不是 Context：存储类不依赖 Android，纯 JVM 单测能直接跑真实读写
    private val store = SessionStore(application.filesDir)
    private val appContext = application.applicationContext

    /**
     * 下一个序号**不再单独维护**（v1.6）。
     *
     * 重排之后「下一个」必然是 `items.size + 1`，见 [SequenceRenumber.nextSeqOf]。
     * 原先那个自增计数器与列表是两份独立状态，撤销 / 恢复会话时任意一处漏改都会撞号，
     * 而撞号会让 LazyColumn 直接抛 `Key ... was already used`——正是要根治的闪退类型。
     */

    /**
     * 列表里已有码值的索引：value -> 出现行数。**增量维护**。
     *
     * 为什么不是每次回调现算一个 HashSet：识别回调在 20fps 下每秒触发约 20 次，
     * 现算就要每秒做 20 次 O(n) 的遍历 + 20 次临时 HashSet 分配；盘点几百条时
     * 这是实打实的 GC 抖动来源。改成索引后每次查询是 O(1)、零分配。
     * 只在本类的调用线程（ML Kit 主线程回调 / 主线程）上访问，无需同步。
     */
    private val valueIndex = HashMap<String, Int>(64)

    /** 「疑似条码，再靠近一点」提示的节流时间戳（elapsedRealtime） */
    private var lastPotentialHintAt = 0L

    /** 识别帧异常写日志的节流时间戳。逐帧失败时不能每帧写一行日志 */
    private var lastAnalyzerErrorLogAt = 0L

    /** 「太近会失焦」提示的节流时间戳（elapsedRealtime，v1.7） */
    private var lastFocusHintAt = 0L

    /**
     * 复用时间格式化器。SimpleDateFormat 构造代价高（内部会建 Calendar + DecimalFormat），
     * 原先每次识别都 new 一个。它不是线程安全的，但本类只在主线程与
     * ML Kit 的主线程回调里调用它，不存在并发。
     */
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 相册识别专用的扫描器，与预览分析器互不干扰，惰性创建 */
    private val galleryScanner: BarcodeScanner by lazy { BarcodeFormats.createScanner() }

    /** 最近一次删除的快照，用于「撤销」。非空时 UI 的 Snackbar 才显示撤销按钮 */
    private var pendingUndo: PendingUndo? = null

    /**
     * 撤销令牌发生器（v1.6）。
     *
     * 不能再用「被删条目的 seq」当凭证：删除后会重排序号，那个 seq 立刻就不代表它了。
     * 令牌是独立于列表内容的单调计数器，语义是「这是第几次删除」。
     */
    private var undoTokenSeq = 0

    private data class PendingUndo(val item: ScanItem, val index: Int, val token: Int)

    /** 持久化防抖任务。列表每次变动都会重建它，实现「停止变动 500ms 后写一次」 */
    private var persistJob: Job? = null

    init {
        AppLog.i(TAG, "ViewModel 初始化")
        // 冷启动读盘：有未收尾的会话就挂到 pendingRestore 上，等用户决定恢复还是丢弃。
        // 不自动恢复——万一上次是误操作留下的、或者用户想重新开始，自动塞回去更烦人。
        viewModelScope.launch(Dispatchers.IO) {
            val snap = runCatching { store.load() }
                .onFailure { AppLog.w(TAG, "读取会话快照失败", it) }
                .getOrNull()
            if (snap != null) {
                AppLog.i(TAG, "发现未收尾会话: ${snap.items.size} 条")
                _uiState.update {
                    it.copy(
                        pendingRestore = PendingRestore(
                            items = snap.items,
                            nextSeq = snap.nextSeq,
                            mode = snap.mode,
                            rule = snap.rule,
                            savedAtMillis = snap.savedAtMillis
                        )
                    )
                }
            }
            refreshLogSummaryInternal()
        }
    }

    // ──────────────────────────── 识别入口 ────────────────────────────

    /**
     * 分析器回调入口。**唯一**允许写入去重器与结果列表的地方。
     *
     * 线程安全性：ML Kit 的 Task 回调默认在主线程执行，因此 nextSeq 的 `++`
     * 不存在竞态；即便将来改为后台 Executor，也只需把整个方法体加锁即可。
     */
    fun onCodesDetected(codes: List<DetectedCode>) {
        val decoded = codes.filter { !it.potentialOnly && it.value.isNotEmpty() }
        if (decoded.isEmpty()) {
            // 只有「疑似条码」而没解出来 → 提高 potentialTick 让 UI 提示用户靠近。
            // 节流：回调是逐帧的（20fps 就是每秒 20 次），每次都 update 会让 Compose
            // 每秒重组 20 次，纯浪费。300ms 一次足够让提示条保持可见。
            if (codes.any { it.potentialOnly }) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastPotentialHintAt >= POTENTIAL_HINT_INTERVAL_MS) {
                    lastPotentialHintAt = now
                    _uiState.update { it.copy(potentialTick = it.potentialTick + 1) }
                }
            }
            return
        }

        val stateSnapshot = _uiState.value

        // 第 -1 级：校验位自校验（v1.5）。
        //
        // 排在所有判定之前，是因为它筛掉的是**解错的码**——一个解错一位的 EAN-13
        // 根本不该进入后面的规则过滤、同帧去重、时间窗判定，否则它会刷新去重时间戳、
        // 甚至被写进列表。放在最前面，语义最干净。
        //
        // 注意它同时覆盖了相册识别：那条路径最终也汇到这里（decodeFromGallery →
        // onCodesDetected），所以不必在两处各写一遍。
        val checked = if (stateSnapshot.checksumPolicy == ChecksumPolicy.ENFORCE) {
            decoded.filter {
                ChecksumVerifier.verify(it.formatName, it.value) != ChecksumVerifier.CheckResult.INVALID
            }
        } else {
            decoded
        }
        // 全是解错的码 → 静默丢弃，不震动、不提示。
        // 这里刻意不弹「校验失败」：扫到码却没反应，用户自然会再试一次；
        // 而弹提示会变成高频打扰（误识往往在同一个位置连续发生）。
        if (checked.isEmpty()) return

        // 第 0 级：准入规则过滤。被规则挡掉的码**不刷新**去重时间戳——
        // 它不是「扫到了又重复」，而是「根本不该要」，不该影响同一个码后续正常的计入时机。
        val rule = stateSnapshot.codeRule
        val candidates = if (rule.enabled) {
            checked.filter { CodeFilter.accepts(it.value, rule) }
        } else {
            checked
        }
        if (candidates.isEmpty()) return

        val nowElapsed = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()

        // 三级判定：同帧去重 + 时间窗防刷屏（始终生效）+ 模式判定。
        // 判定逻辑放在 domain 层的 CodeAdmission 里，便于用纯 JVM 单测覆盖。
        val admission = CodeAdmission.admit(
            decoded = candidates,
            mode = stateSnapshot.dedupeMode,
            dedupe = dedupe,
            // 直接传索引的 keySet 视图（O(1) 查询、零额外分配），不再每帧现拷一个 HashSet。
            // 只有「需要知道这个码是否已在列表里」的两种模式才传；ALLOW 传空集合省掉构建。
            existingValues = if (stateSnapshot.dedupeMode == DedupeMode.ALLOW) {
                emptySet()
            } else {
                valueIndex.keys
            },
            nowMs = nowElapsed
        )
        if (admission.isEmpty() && admission.duplicates.isEmpty()) {
            return   // 全是重复帧：静默丢弃，不震动、不闪边框
        }

        // 重复码提醒（v1.7）：必须排在 isEmpty 判断**之前**——严格去重下
        // 重复码恰恰是「什么都不新增」，但它正是最需要告诉用户的那件事。
        if (admission.duplicates.isNotEmpty()) {
            handleDuplicateHits(admission.duplicates, nowElapsed)
        }
        if (admission.isEmpty()) return

        // 同一次回调内所有条目的时间戳必然相同，只格式化一次，省掉 N 次 SimpleDateFormat
        val timeText = formatTime(nowWall)

        // 增量维护索引（放在状态更新之外：索引是派生物，不需要参与 StateFlow 的 CAS 循环）
        for (code in admission.newCodes) {
            valueIndex[code.value] = (valueIndex[code.value] ?: 0) + 1
        }

        // 两种变更合并在一次 update 里完成：新增行 + 已有行数量累加。
        // 分开写两次 update 会让 UI 在同一帧内收到两个状态，白白多重组一次。
        //
        // 序号在 update 内部按当时的列表长度派生（v1.6）：把它放在外面算，
        // 就等于又引入了一份「外面看到的长度」与「CAS 里的长度」可能不一致的状态。
        val increments = admission.increments
        var addedCount = 0
        _uiState.update { state ->
            var items = state.items
            val newItems = admission.newCodes.mapIndexed { index, code ->
                ScanItem(
                    seq = SequenceRenumber.nextSeqOf(state.items) + index,
                    formatId = code.formatId,
                    formatName = code.formatName,
                    value = code.value,
                    timeMillis = nowWall,
                    timeText = timeText,
                    qty = 1
                )
            }
            addedCount = newItems.size
            if (newItems.isNotEmpty()) items = items + newItems
            if (increments.isNotEmpty()) {
                // 单元素时直接比字符串，省掉 HashSet 构建（绝大多数帧只有 1 个）
                val single = if (increments.size == 1) increments[0] else null
                val multi = if (single == null) increments.toHashSet() else null
                items = items.map { item ->
                    val hit = if (single != null) item.value == single else item.value in multi!!
                    if (hit) item.copy(qty = item.qty + 1) else item
                }
            }
            state.copy(
                items = items,
                lastValue = newItems.lastOrNull()?.value ?: increments.lastOrNull() ?: state.lastValue,
                flashTick = state.flashTick + 1
            )
        }

        // 反馈只在「真的计入」时触发，不会因为同一帧重复回调而连震
        val snapshot = _uiState.value
        if (snapshot.feedbackEnabled) {
            feedback.play(vibrate = snapshot.vibrateEnabled, beep = snapshot.beepEnabled)
        }
        // 不记录每个码值（那是用户的盘点内容），只记规模变化，够定位问题了
        if (addedCount > 0) {
            AppLog.i(TAG, "计入 $addedCount 条，累计 ${snapshot.items.size} 条")
        }
        schedulePersist()
    }

    /**
     * 处理「这一帧命中了列表里已有的码」（v1.7）。
     *
     * 三件事，顺序不能换：
     *  1. **先记命中次数**（无论提不提示）：提示条上的「第 N 次扫到」要说得准，
     *     就不能只数真正弹出来的那几次；
     *  2. 再由 [DuplicateMonitor] 的两级冷却判定这次该不该提示；
     *  3. 提示了就配一次**区别于成功**的震动（两下短震），用户不用看屏幕也能分辨。
     *
     * 已经有一条提示在屏上时**直接换成新的**（v1.8.1 起）：提示条不拦截触摸、
     * 也不暂停相机，连扫时「第二条被吞掉」比「旧信息被顶掉」更糟。
     * 频率由 [DuplicateMonitor] 的冷却兜住，不会出现提示条一直闪。
     */
    private fun handleDuplicateHits(values: List<String>, nowElapsed: Long) {
        val state = _uiState.value
        // 关掉提醒时仍然记录次数：下次打开，「第 N 次」依然对得上
        if (!state.duplicateAlertEnabled) {
            values.forEach { duplicateMonitor.recordAndShouldAlert(it, nowElapsed) }
            return
        }

        var picked: DuplicateAlert? = null
        for (value in values) {
            val shouldAlert = duplicateMonitor.recordAndShouldAlert(value, nowElapsed)
            if (!shouldAlert || picked != null) continue

            // 用当前列表定位行号：提示条上「第几行」是用户回列表核对的唯一线索
            val index = state.items.indexOfFirst { it.value == value }
            val item = state.items.getOrNull(index)
            // 计数聚合下这一帧马上会把 qty +1，所以提示要显示**累加之后**的数量，
            // 否则「已累计 ×7」会和列表里的 ×8 对不上
            val qtyAfter = (item?.qty ?: 1) + if (state.dedupeMode == DedupeMode.COUNT) 1 else 0
            picked = DuplicateAlert(
                value = value,
                rowNumber = index + 1,
                qty = qtyAfter,
                // hits 只统计「重复命中」，加上第一次才是用户在提示条上看到的次数
                scanTimes = duplicateMonitor.hitsOf(value) + 1,
                mode = state.dedupeMode
            )
        }

        val alert = picked ?: return
        _uiState.update { it.copy(duplicateAlert = alert) }
        if (state.feedbackEnabled) {
            // 只震动不出声：连续盘点时「啪」两声的提示音很容易和成功音混在一起
            feedback.playDuplicate(vibrate = state.vibrateEnabled)
        }
        AppLog.i(TAG, "重复码提示：第 ${alert.rowNumber} 行，第 ${alert.scanTimes} 次扫到，口径 ${alert.mode.label}")
    }

    /** 收起重复码轻提示（到时自动消失，或用户点了那条提示） */
    fun dismissDuplicateAlert() {
        _uiState.update { it.copy(duplicateAlert = null) }
    }

    /**
     * 分析器回报「连续多帧有码却解不出」（v1.7）。
     *
     * 绝大多数情况是**手机贴得太近**：主流手机镜头的最近对焦距离在 10~12cm，
     * 而要看清一颗 5mm 的 Data Matrix 又必须靠得很近，两者直接冲突。
     * 提示「退到 15cm 左右 + 用 2× 变焦」才是真正能解决问题的动作。
     */
    fun onFocusHelp() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusHintAt < FOCUS_HINT_INTERVAL_MS) return
        lastFocusHintAt = now
        _uiState.update { it.copy(focusHintTick = it.focusHintTick + 1) }
        AppLog.i(TAG, "连续多帧解不出，提示用户调整距离")
    }

    /** 单帧分析失败（镜头切换、图像损坏等），只发一条可读提示，不中断扫描 */
    fun onAnalyzerError(error: Throwable) {
        // 逐帧失败会刷屏，日志按 3 秒窗口节流；提示条本身也只在首次出现时弹一次
        val now = SystemClock.elapsedRealtime()
        if (now - lastAnalyzerErrorLogAt >= ANALYZER_ERROR_LOG_INTERVAL_MS) {
            lastAnalyzerErrorLogAt = now
            AppLog.w(TAG, "识别帧异常（已自动恢复）", error)
            _events.tryEmit(
                UiEvent.Message("识别异常，已自动恢复：${error.message ?: error.javaClass.simpleName}")
            )
        }
    }

    // ──────────────────────────── 列表操作 ────────────────────────────

    /**
     * 删除一条（误扫，v1.6 起删除后**序号自动重排**）。
     *
     * @param seq 屏幕上显示的序号。因为重排后 seq 恒等于行号，这里等价于按行删除。
     */
    fun deleteItem(seq: Int) {
        var removedIndex = -1
        var removedItem: ScanItem? = null
        _uiState.update { state ->
            val index = state.items.indexOfFirst { it.seq == seq }
            if (index < 0) return@update state
            val removed = state.items[index]
            removedIndex = index
            removedItem = removed
            // 同步移除该码的时间戳：删完立刻重扫同一个码应当能马上录入，
            // 而不是因为它还在画面里、时间窗未满而被静默拒绝
            dedupe.remove(removed.value)
            // 同理清掉重复码的命中/冷却记录：删掉后再扫到它，应当是「新码」而不是「重复」
            duplicateMonitor.forget(removed.value)
            // 索引同步减一；归零则删键，保证「不在列表里」== 「不在索引里」
            val left = (valueIndex[removed.value] ?: 0) - 1
            if (left <= 0) valueIndex.remove(removed.value) else valueIndex[removed.value] = left
            // ★ 重排：删掉中间那条之后，后面的全部前移一位补齐断号
            state.copy(items = SequenceRenumber.renumber(state.items.filterIndexed { i, _ -> i != index }))
        }
        val item = removedItem
        if (item != null && removedIndex >= 0) {
            // 记住原位置：撤销时插回原处，而不是追加到末尾——
            // 盘点列表是有顺序含义的，插回末尾会让用户以为刚才那条是新扫的
            val token = ++undoTokenSeq
            pendingUndo = PendingUndo(item, removedIndex, token)
            AppLog.i(TAG, "删除第 ${removedIndex + 1} 条（seq=$seq），剩余 ${_uiState.value.items.size} 条并已重排序号")
            _events.tryEmit(UiEvent.UndoableMessage("已删除 ${item.value}", token))
        }
        schedulePersist()
    }

    /** 撤销上一次删除。只能撤销最近一次——这是刻意的，不做多级撤销栈 */
    fun undoDelete(token: Int) {
        val pending = pendingUndo ?: return
        // 令牌对不上说明用户已经做了别的删除，这条 Snackbar 的撤销已经失效。
        // 不用「条目 seq」做校验的原因见 [UiEvent.UndoableMessage]。
        if (pending.token != token) return
        pendingUndo = null
        val item = pending.item
        dedupe.remove(item.value)        // 撤销后立刻重扫这个码应当能马上录入
        duplicateMonitor.forget(item.value)
        valueIndex[item.value] = (valueIndex[item.value] ?: 0) + 1
        _uiState.update { state ->
            // 幂等保护：这一行（同一个码值 + 同一个计入时刻）已经在列表里就别再插一次。
            // 判据不能用 seq —— 重排之后 seq 只表示「第几行」，不再标识某一条记录。
            val alreadyPresent = state.items.any {
                it.timeMillis == item.timeMillis && it.value == item.value
            }
            if (alreadyPresent) return@update state
            val at = pending.index.coerceIn(0, state.items.size)
            val restored = state.items.toMutableList().apply { add(at, item) }
            // 插回原处之后同样要重排：插回去的那条 seq 是旧的，会和后面那条撞号
            state.copy(items = SequenceRenumber.renumber(restored))
        }
        AppLog.i(TAG, "撤销删除，恢复到第 ${pending.index + 1} 位，共 ${_uiState.value.items.size} 条")
        _events.tryEmit(UiEvent.Message("已恢复 ${item.value}"))
        schedulePersist()
    }

    /** 撤销窗口过期（UI 的 Snackbar 消失）时调用，清掉快照避免误恢复 */
    fun dismissUndo() {
        pendingUndo = null
    }

    fun clearAll() {
        val before = _uiState.value.items.size
        _uiState.update { it.copy(items = emptyList(), lastValue = null, duplicateAlert = null) }
        // 同步清空去重记录与索引：刚清空后立刻重扫同一个码应当被立即录入
        dedupe.reset()
        duplicateMonitor.reset()
        valueIndex.clear()
        pendingUndo = null
        AppLog.i(TAG, "清空列表（原有 $before 条）")
        _events.tryEmit(UiEvent.Message("已清空"))
        schedulePersist()   // items 为空 → 实际执行的是删除快照文件
    }

    // ──────────────────────────── 复制 / 导出 ────────────────────────────

    fun requestCopyAll() {
        val state = _uiState.value
        if (state.items.isEmpty()) {
            _events.tryEmit(UiEvent.Message("列表为空，没有可复制的内容"))
            return
        }
        val text = CopyFormatter.format(state.items, state.copyFormat)
        _events.tryEmit(
            UiEvent.CopyToClipboard(
                text = text,
                itemCount = state.items.size,
                formatLabel = state.copyFormat.label,
                isSingle = false
            )
        )
    }

    /** 界面上的「长按/点击单条」复制：无论当前格式设置如何，单条只复制码值 */
    fun requestCopySingle(item: ScanItem) {
        _events.tryEmit(
            UiEvent.CopyToClipboard(
                text = CopyFormatter.formatSingle(item),
                itemCount = 1,
                // 单条不套格式，这个字段用不上；显式给个空串避免误导
                formatLabel = "",
                isSingle = true
            )
        )
    }

    /**
     * 导出成文件并请求系统分享（F3 / F11）。
     *
     * 走 IO 线程：写几 MB 的 xlsx 不能卡主线程。失败（磁盘满等）时给可读提示。
     */
    fun requestExport(kind: Exporter.Kind) {
        val items = _uiState.value.items
        if (items.isEmpty()) {
            _events.tryEmit(UiEvent.Message("列表为空，没有可导出的内容"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { Exporter.export(getApplication(), items, kind) }
                .onSuccess { file ->
                    AppLog.i(TAG, "导出 ${kind.name} 成功: ${file.fileName} (${file.sizeBytes}B, ${items.size} 条)")
                    _events.tryEmit(
                        UiEvent.ShareExport(
                            uri = file.uri,
                            mime = file.mime,
                            fileName = file.fileName,
                            summary = Exporter.summary(items)
                        )
                    )
                }
                .onFailure { e ->
                    AppLog.e(TAG, "导出 ${kind.name} 失败（${items.size} 条）", e)
                    _events.tryEmit(UiEvent.Message("导出失败：${e.message ?: e.javaClass.simpleName}"))
                }
        }
    }

    fun setCopyFormat(format: CopyFormat) {
        _uiState.update { it.copy(copyFormat = format) }
    }

    // ──────────────────────────── 设置项 ────────────────────────────

    fun setFeedbackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(feedbackEnabled = enabled) }
    }

    fun setVibrateEnabled(enabled: Boolean) {
        _uiState.update { it.copy(vibrateEnabled = enabled) }
    }

    fun setBeepEnabled(enabled: Boolean) {
        _uiState.update { it.copy(beepEnabled = enabled) }
    }

    /**
     * 切换重复码口径（三态）。
     *
     * 切换时一并清空去重器的时间戳记录：若不清，切换后的一小段时间内，
     * 上一个口径刚计入的码会被时间窗误判而拒绝，表现为「刚切完没反应」。
     */
    fun setDedupeMode(mode: DedupeMode) {
        _uiState.update { it.copy(dedupeMode = mode, duplicateAlert = null) }
        dedupe.reset()
        // 口径变了，「重复」的语义也变了：旧模式下攒的命中次数不该带进新口径
        duplicateMonitor.reset()
        _events.tryEmit(UiEvent.Message("重复码口径：${mode.label}，${mode.hint}"))
        schedulePersist()
    }

    /** 主屏 chip 点击：在三种口径之间循环 */
    fun cycleDedupeMode() {
        val next = when (_uiState.value.dedupeMode) {
            DedupeMode.STRICT -> DedupeMode.COUNT
            DedupeMode.COUNT -> DedupeMode.ALLOW
            DedupeMode.ALLOW -> DedupeMode.STRICT
        }
        setDedupeMode(next)
    }

    /** 去重时间窗。三种口径下都生效（防刷屏 + 控制重复录入的间隔） */
    fun setDedupeWindow(millis: Long) {
        dedupe.windowMillis = millis
        _uiState.update { it.copy(dedupeWindowMs = dedupe.windowMillis) }
    }

    /**
     * 分析帧分辨率档位。
     *
     * 提高档位能让小码/复杂码在分析帧里占更多像素，识别率上升；代价是单帧耗时与耗电。
     * 本项会触发相机重建（CameraX 的分析分辨率绑定后不可变），因此不做防抖，
     * 只在用户明确点选档位时调用。
     */
    fun setAnalysisProfile(profile: ScanTuning.AnalysisProfile) {
        _uiState.update { it.copy(analysisProfile = profile) }
        _events.tryEmit(
            UiEvent.Message(
                "识别精度已切到 ${profile.label}（约 ${"%.1f".format(profile.pixelScale)}× 像素）"
            )
        )
        schedulePersist()
    }

    /**
     * 增强识别开关。打开时若档位还停在 720p，自动抬到 1080p ——
     * 只调变焦不抬分辨率，远距小码依然解不出（变焦放大的是预览，分析帧还是那个尺寸）。
     */
    fun setEnhancedRecognition(enabled: Boolean) {
        _uiState.update { state ->
            val boosted = ScanTuning.profileWithBoost(state.analysisProfile, enabled)
            state.copy(enhancedRecognition = enabled, analysisProfile = boosted)
        }
        _events.tryEmit(
            UiEvent.Message(
                if (enabled) "已开启增强识别：提升分辨率与变焦上限，更吃电"
                else "已关闭增强识别"
            )
        )
        schedulePersist()
    }

    /**
     * 分析帧间隔（「识别间隙」）。
     *
     * 0 = 不节流，每帧都送识别引擎（v1.3 行为）。调大后省电降温，代价是识别响应变慢。
     * 立即生效，不重建相机。
     */
    fun setFrameInterval(millis: Long) {
        val normalized = ScanTuning.normalizeFrameInterval(millis)
        _uiState.update { it.copy(frameIntervalMs = normalized) }
    }

    /**
     * 设置准入规则。
     *
     * 先 validate 再落库：正则写错是最常见的情况，必须当场告诉用户，
     * 否则表现为「扫什么都进不去」，用户会以为是相机坏了。
     */
    fun setCodeRule(rule: CodeRule) {
        CodeFilter.reset()   // 清掉旧正则的编译缓存，否则改了不生效
        val error = CodeFilter.validate(rule)
        _uiState.update { it.copy(codeRule = rule) }
        dedupe.reset()
        if (error != null) {
            _events.tryEmit(UiEvent.Message(error))
        } else if (rule.enabled) {
            _events.tryEmit(
                UiEvent.Message(
                    if (rule.isBlank()) "已开启规则过滤，但还没填任何条件（当前等于不限制）"
                    else "规则过滤已生效，不符合的码不会再计入"
                )
            )
        }
        schedulePersist()
    }

    fun setAutoZoomEnabled(enabled: Boolean) {
        _uiState.update { it.copy(autoZoomEnabled = enabled) }
        if (enabled) {
            _events.tryEmit(UiEvent.Message("已开启自动变焦：码太远时会自动放大"))
        }
        // v1.4.3 补：之前关了自动变焦，重启后会悄悄恢复成开启，
        // 用户会以为「设置没保存」。与其他开关一致地落盘。
        schedulePersist()
    }

    /**
     * 校验位自校验口径（v1.5）。
     *
     * 切换时顺带重置去重器：口径变了意味着「哪些码会被接纳」变了，
     * 留着旧的时间戳会让刚被拒绝的码在新口径下继续被时间窗挡住，
     * 表现为「改了设置没反应」。
     */
    fun setChecksumPolicy(policy: ChecksumPolicy) {
        _uiState.update { it.copy(checksumPolicy = policy) }
        dedupe.reset()
        if (policy == ChecksumPolicy.ENFORCE) {
            _events.tryEmit(
                UiEvent.Message("已开启校验位检查：EAN / UPC 系列的校验位算不对就丢弃")
            )
        }
        schedulePersist()
    }

    /**
     * ROI 二次精读开关（v1.5）。
     *
     * 立即生效、不重建相机——分析器读的是 `@Volatile` 字段，与「识别间隙」同一套机制。
     */
    fun setRoiRefineEnabled(enabled: Boolean) {
        _uiState.update { it.copy(roiRefineEnabled = enabled) }
        if (enabled) {
            _events.tryEmit(UiEvent.Message("已开启小码精读：解不出时放大候选区域再试一次"))
        }
        schedulePersist()
    }

    /**
     * 第二引擎（ZXing）补扫开关（v1.7）。
     *
     * 与「小码精读」同一套机制：立即生效、不重建相机（分析器读的是 `@Volatile` 字段）。
     */
    fun setZxingFallbackEnabled(enabled: Boolean) {
        _uiState.update { it.copy(zxingFallbackEnabled = enabled) }
        if (enabled) {
            _events.tryEmit(UiEvent.Message("已开启双引擎补扫：ML Kit 解不出时交给 ZXing 再试"))
        }
        schedulePersist()
    }

    /**
     * 重复码顶部轻提示开关（v1.7）。
     *
     * 关闭时顺手把当前正在显示的提示收掉——用户刚点完「关掉提醒」，
     * 屏幕上还留着一个提示会让人怀疑按钮没生效。
     */
    fun setDuplicateAlertEnabled(enabled: Boolean) {
        _uiState.update {
            it.copy(
                duplicateAlertEnabled = enabled,
                duplicateAlert = if (enabled) it.duplicateAlert else null
            )
        }
        _events.tryEmit(
            UiEvent.Message(
                if (enabled) "扫到重复码时会顶部轻提示（单码 3 秒内最多一次）"
                else "已关闭重复码提醒"
            )
        )
        schedulePersist()
    }

    fun openSheet() = _uiState.update { it.copy(sheetOpen = true) }
    fun closeSheet() = _uiState.update { it.copy(sheetOpen = false) }

    fun setPersistEnabled(enabled: Boolean) {
        _uiState.update { it.copy(persistEnabled = enabled) }
        if (enabled) {
            schedulePersist()
        } else {
            persistJob?.cancel()
            viewModelScope.launch(Dispatchers.IO) { store.clear() }
            _events.tryEmit(UiEvent.Message("已关闭会话保存，退出后数据不再保留"))
        }
    }

    // ──────────────────────────── 会话恢复 ────────────────────────────

    /** 采用上次未收尾的会话 */
    fun restorePending() {
        val pending = _uiState.value.pendingRestore ?: return
        _uiState.update { state ->
            state.copy(
                // 旧版本存下来的快照可能带断号（v1.5 的「删除不重排」留下的），
                // 恢复时统一补齐，保证「序号 = 行号」这条不变量在任何入口都成立
                items = SequenceRenumber.renumber(pending.items),
                dedupeMode = pending.mode,
                codeRule = pending.rule,
                pendingRestore = null,
                duplicateAlert = null
            )
        }
        dedupe.reset()
        duplicateMonitor.reset()
        CodeFilter.reset()
        valueIndex.clear()
        _uiState.value.items.forEach { valueIndex[it.value] = (valueIndex[it.value] ?: 0) + 1 }
        AppLog.i(TAG, "恢复上次会话 ${pending.items.size} 条")
        _events.tryEmit(UiEvent.Message("已恢复上次未完成的 ${pending.items.size} 条"))
        schedulePersist()
    }

    /** 丢弃上次未收尾的会话 */
    fun discardPending() {
        if (_uiState.value.pendingRestore == null) return
        _uiState.update { it.copy(pendingRestore = null) }
        viewModelScope.launch(Dispatchers.IO) { store.clear() }
        AppLog.i(TAG, "丢弃上次会话")
        _events.tryEmit(UiEvent.Message("已丢弃上次的记录"))
    }

    // ──────────────────────────── 相机控制 ────────────────────────────

    fun toggleTorch() {
        _uiState.update { it.copy(torchOn = !it.torchOn) }
    }

    /** UI 绑定相机成功后回填能力信息，用于禁用不支持的按钮 */
    fun onCameraCapabilities(maxZoom: Float, hasFlashUnit: Boolean) {
        _uiState.update {
            it.copy(
                maxZoomRatio = maxZoom.coerceAtLeast(1f),
                hasFlashUnit = hasFlashUnit,
                zoomRatio = it.zoomRatio.coerceIn(1f, maxZoom.coerceAtLeast(1f))
            )
        }
    }

    fun setZoom(ratio: Float) {
        _uiState.update { it.copy(zoomRatio = ratio.coerceIn(1f, it.maxZoomRatio)) }
    }

    /** 双击缩放：1x ↔ 2x 循环（超出设备能力的部分被 coerce 掉） */
    fun toggleDoubleTapZoom() {
        _uiState.update {
            val target = if (it.zoomRatio > 1.05f) 1f else 2f
            it.copy(zoomRatio = target.coerceIn(1f, it.maxZoomRatio))
        }
    }

    fun reportNoFlashUnitWhileTurningOn() {
        _uiState.update { it.copy(torchOn = false, hasFlashUnit = false) }
        _events.tryEmit(UiEvent.Message("当前镜头没有闪光灯"))
    }

    // ──────────────────────────── 相册识别 ────────────────────────────

    fun decodeFromGallery(uri: Uri) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true) }
            AppLog.i(TAG, "开始识别相册图片")
            val result = runCatching {
                ImageDecoder.decode(getApplication(), uri, galleryScanner)
            }
            _uiState.update { it.copy(busy = false) }

            result.onSuccess { codes ->
                val decodedCount = codes.count { !it.potentialOnly }
                AppLog.i(TAG, "相册识别完成，解码 $decodedCount 个")
                // 相册结果同样走去重与规则过滤，防止一张图里同款商品被重复计入
                onCodesDetected(codes)
                if (decodedCount == 0) {
                    _events.tryEmit(UiEvent.Message("图片里没有识别到条码，换一张更清晰、码占画面更大的图片"))
                } else {
                    _events.tryEmit(UiEvent.Message("图片解析完成，共发现 $decodedCount 个条码"))
                }
            }.onFailure { e ->
                AppLog.e(TAG, "相册图片读取失败", e)
                _events.tryEmit(UiEvent.Message("图片读取失败：${e.message ?: e.javaClass.simpleName}"))
            }
        }
    }

    // ──────────────────────────── 持久化 ────────────────────────────

    /**
     * 安排一次写盘（防抖 500ms）。
     *
     * 为什么防抖而不是每条都写：连续扫描时每秒可能变动十几次，每条都做一次
     * JSON 序列化 + 文件写入会明显占用 IO。500ms 是「用户停手就落盘」与
     * 「不至于频繁写」之间的平衡点；崩溃时最多丢最后 500ms 的扫描。
     *
     * 进程被杀（不会走 onCleared）也靠它兜底，配合 [flushPersist] 覆盖切后台场景。
     */
    private fun schedulePersist() {
        if (!_uiState.value.persistEnabled) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PERSIST_DEBOUNCE_MS)
            writeSnapshot()
        }
    }

    /**
     * 立即写盘（切后台 / 退出前调用）。
     * 取消掉还在等待的防抖任务，避免它稍后用一个已经过期的状态覆盖刚写的内容。
     */
    fun flushPersist() {
        if (!_uiState.value.persistEnabled) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) { writeSnapshot() }
    }

    private fun writeSnapshot() {
        val state = _uiState.value
        runCatching {
            if (state.items.isEmpty()) {
                store.clear()       // 空列表不留文件：下次启动不该弹「恢复 0 条」
            } else {
                store.save(
                    items = state.items,
                    // 派生而非自增（v1.6）：与 SequenceRenumber 同一口径，
                    // 恢复时不会出现「新扫的码撞上旧序号」
                    nextSeq = SequenceRenumber.nextSeqOf(state.items),
                    mode = state.dedupeMode,
                    rule = state.codeRule
                )
            }
        }.onFailure { AppLog.w(TAG, "写会话快照失败", it) }
    }

    // ──────────────────────────── 运行日志 / 崩溃报告（v1.6）────────────────────────────

    /** 重新统计日志规模，写入状态供设置面板与横幅显示 */
    fun refreshLogSummary() {
        refreshLogSummaryInternal()
    }

    private fun refreshLogSummaryInternal() {
        runCatching {
            val reports = AppLog.crashReports(appContext).size
            val summary = AppLog.summary(appContext)
            _uiState.update { it.copy(crashReportCount = reports, logSummary = summary) }
        }
    }

    /** 用户在横幅上点「忽略」：本次启动不再打扰（日志文件仍然保留） */
    fun dismissCrashNotice() {
        _uiState.update { it.copy(crashNoticeDismissed = true) }
    }

    /**
     * 把运行日志 + 崩溃报告合并成一个 txt，走系统分享面板发出去。
     *
     * 复用导出那条 `UiEvent.ShareExport` 通路：同样是「写文件 → FileProvider 授权 →
     * ACTION_SEND」，不申请任何权限、不联网，用户直接发给开发者即可。
     */
    fun requestExportLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            val file = runCatching { AppLog.exportBundle(appContext) }
                .onFailure { AppLog.w(TAG, "导出日志失败", it) }
                .getOrNull()
            if (file == null) {
                _events.tryEmit(UiEvent.Message("还没有日志可导出"))
                return@launch
            }
            val uri = runCatching {
                androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    "${appContext.packageName}.fileprovider",
                    file
                )
            }.getOrNull()
            if (uri == null) {
                _events.tryEmit(UiEvent.Message("日志文件准备失败，请重试"))
                return@launch
            }
            val reports = _uiState.value.crashReportCount
            _events.tryEmit(
                UiEvent.ShareExport(
                    uri = uri,
                    mime = "text/plain",
                    fileName = file.name,
                    summary = if (reports > 0) "含 $reports 份崩溃报告" else "仅运行日志"
                )
            )
        }
    }

    /** 清空全部日志与崩溃报告 */
    fun clearLogs() {
        AppLog.clearAll(appContext)
        refreshLogSummaryInternal()
        _events.tryEmit(UiEvent.Message("日志已清空"))
    }

    // ──────────────────────────── 内部工具 ────────────────────────────

    /** 预格式化时间，避免列表重组时反复创建 SimpleDateFormat（API 24 无 java.time） */
    private fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

    override fun onCleared() {
        // Activity 真正销毁（不是旋转）时走这里：协程作用域即将取消，
        // 必须同步补写一次，否则最后 500ms 内的扫描会丢。
        persistJob?.cancel()
        if (_uiState.value.persistEnabled) writeSnapshot()
        AppLog.i(TAG, "ViewModel 销毁，列表 ${_uiState.value.items.size} 条")
        feedback.close()
        runCatching { galleryScanner.close() }
        super.onCleared()
    }

    private companion object {
        const val TAG = "Scan"

        /** 「疑似条码，再靠近一点」提示的最小间隔：低于此值直接丢弃，不触发状态更新 */
        const val POTENTIAL_HINT_INTERVAL_MS = 300L

        /** 写盘防抖窗口 */
        const val PERSIST_DEBOUNCE_MS = 500L

        /**
         * 识别帧异常写日志的最小间隔。
         *
         * 镜头切换瞬间可能连续失败好几帧，每帧写一行日志既没有额外信息量，
         * 又会把日志文件刷满、把真正有用的那次失败挤掉。
         */
        const val ANALYZER_ERROR_LOG_INTERVAL_MS = 3_000L

        /** 「太近会失焦」提示的冷却时间：15 秒。用户调整姿势需要时间，别一直催 */
        const val FOCUS_HINT_INTERVAL_MS = 15_000L
    }
}
