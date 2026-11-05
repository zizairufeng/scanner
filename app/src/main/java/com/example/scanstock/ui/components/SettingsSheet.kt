package com.example.scanstock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.scanstock.domain.ChecksumPolicy
import com.example.scanstock.domain.CodeRule
import com.example.scanstock.domain.CopyFormat
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DedupeTracker
import com.example.scanstock.domain.ScanTuning
import com.example.scanstock.domain.SettingsPage
import kotlin.math.roundToLong

/**
 * 设置面板。
 *
 * ## 结构：高度钉死 + 分类分页 + 页内滚动兜底
 *
 * v1.4 → v1.4.3 的三版修法都不是终解，演进过程见 [SettingsPage] 的 KDoc。
 * 简单说：v1.4.1/1.4.2 输在「面板高度依赖内容」，v1.4.3 输在「钉死了但装不下」。
 *
 * 这一版同时具备三条：
 *  1. **面板高度完全钉死**（[panelHeight] = 固定开销 + 内容预算），不依赖内容；
 *  2. 设置项按用途分成 [SettingsPage.entries] 四页；
 *  3. **内容区在钉死的高度内可滚动**（v1.4.4 新增）。
 *
 * 第 3 点是这次的关键，也是「不要再相信任何一次高度估算」的制度化：
 * 内容超预算时**变成滚动**，而不是像 v1.4.3 那样直接盖住底部翻页条。
 *
 * 这里与 v1.4.1 那个失败的 `verticalScroll` **有本质区别**，别混淆：
 * v1.4.1 是「面板高度不钉死 + 滚动」→ 滚动容器把高度报成无限大，
 * sheet 的测量-摆放循环算不出可靠位置，最终仍按内容撑满并裁掉。
 * 现在是在**已经确定的固定高度内**滚动——父容器高度是常量，
 * 不存在「内容无穷高」这个坏前提，滚动是安全的。
 *
 * ## 为什么不做得更好看
 *
 * 没有引入 `HorizontalPager`（要额外依赖）也没有用 `TabRow`（标题会被压成单行省略号）。
 * 4 个 `FilterChip` + 一个底部按钮条，零新依赖，行为完全确定。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    feedbackEnabled: Boolean,
    vibrateEnabled: Boolean,
    beepEnabled: Boolean,
    copyFormat: CopyFormat,
    dedupeMode: DedupeMode,
    dedupeWindowMs: Long,
    codeRule: CodeRule,
    autoZoomEnabled: Boolean,
    analysisProfile: ScanTuning.AnalysisProfile,
    enhancedRecognition: Boolean,
    frameIntervalMs: Long,
    checksumPolicy: ChecksumPolicy,
    roiRefineEnabled: Boolean,
    zxingFallbackEnabled: Boolean,
    duplicateAlertEnabled: Boolean,
    persistEnabled: Boolean,
    logSummary: String,
    crashReportCount: Int,
    onFeedbackChanged: (Boolean) -> Unit,
    onVibrateChanged: (Boolean) -> Unit,
    onBeepChanged: (Boolean) -> Unit,
    onCopyFormatChanged: (CopyFormat) -> Unit,
    onDedupeModeChanged: (DedupeMode) -> Unit,
    onDedupeWindowChanged: (Long) -> Unit,
    onCodeRuleChanged: (CodeRule) -> Unit,
    onAutoZoomChanged: (Boolean) -> Unit,
    onAnalysisProfileChanged: (ScanTuning.AnalysisProfile) -> Unit,
    onEnhancedRecognitionChanged: (Boolean) -> Unit,
    onFrameIntervalChanged: (Long) -> Unit,
    onChecksumPolicyChanged: (ChecksumPolicy) -> Unit,
    onRoiRefineChanged: (Boolean) -> Unit,
    onZxingFallbackChanged: (Boolean) -> Unit,
    onDuplicateAlertChanged: (Boolean) -> Unit,
    onPersistChanged: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 当前分类页。用 rememberSaveable 而非 remember：键盘编辑正则时旋转屏幕，
    // 用户不该被弹回第一页。存序号而不是枚举本身，避免枚举改名后旧状态无法还原。
    var pageOrdinal by rememberSaveable { mutableStateOf(SettingsPage.DEFAULT.ordinal) }
    val page = SettingsPage.fromOrdinal(pageOrdinal)

    // 键盘弹起时面板要相应变矮，否则正则输入框会被键盘顶出可视区。
    // 这是本面板唯一允许改变高度的输入。
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    // 面板高度 = 固定开销 + 内容预算。**不是** wrapContent，也不是 fillMaxHeight。
    //   固定开销明细见 [SettingsPage.PANEL_FIXED_OVERHEAD_DP] 的注释（约 202，取整 210）。
    //
    //   再跟屏幕可用高度取一次 min：常量预算是按 927dp 高的屏算的，小屏（例如 800dp）
    //   上 sheet 自身的可视上限更低，钉死 730dp 会被 sheet 从底部裁掉一截。
    //   注意这里取的仍是**屏幕高度**这个常量，不是内容高度——不会把测量循环带回坏前提。
    val screenLimitDp = LocalConfiguration.current.screenHeightDp * 0.9f
    val budgetDp = SettingsPage.PANEL_TOTAL_HEIGHT_DP.toFloat()
    val panelHeight = (if (budgetDp > screenLimitDp) screenLimitDp else budgetDp).dp + imeBottom

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        // ───────────── 面板内容：固定高度，不滚动 ─────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(panelHeight)
                .padding(horizontal = 20.dp)
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = 2.dp)
            )

            // ── 分类标签条。四页并排，点谁去哪页 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SettingsPage.entries.forEach { candidate ->
                    FilterChip(
                        selected = candidate == page,
                        onClick = { pageOrdinal = candidate.ordinal },
                        label = { Text(candidate.label) }
                    )
                }
            }

            // ── 内容区：高度由 weight(1f) 确定（父 Column 高度已钉死，剩余量是常量），
            //    内部可滚动。溢出时表现为「多出来的滚一下」，而不是盖住下面的翻页条。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 4.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (page) {
                    SettingsPage.SCAN -> ScanPage(
                        feedbackEnabled = feedbackEnabled,
                        vibrateEnabled = vibrateEnabled,
                        beepEnabled = beepEnabled,
                        autoZoomEnabled = autoZoomEnabled,
                        copyFormat = copyFormat,
                        persistEnabled = persistEnabled,
                        onFeedbackChanged = onFeedbackChanged,
                        onVibrateChanged = onVibrateChanged,
                        onBeepChanged = onBeepChanged,
                        onAutoZoomChanged = onAutoZoomChanged,
                        onCopyFormatChanged = onCopyFormatChanged,
                        onPersistChanged = onPersistChanged
                    )

                    SettingsPage.ENGINE -> EnginePage(
                        analysisProfile = analysisProfile,
                        enhancedRecognition = enhancedRecognition,
                        roiRefineEnabled = roiRefineEnabled,
                        zxingFallbackEnabled = zxingFallbackEnabled,
                        frameIntervalMs = frameIntervalMs,
                        onAnalysisProfileChanged = onAnalysisProfileChanged,
                        onEnhancedRecognitionChanged = onEnhancedRecognitionChanged,
                        onRoiRefineChanged = onRoiRefineChanged,
                        onZxingFallbackChanged = onZxingFallbackChanged,
                        onFrameIntervalChanged = onFrameIntervalChanged
                    )

                    SettingsPage.ACCURACY -> AccuracyPage(
                        checksumPolicy = checksumPolicy,
                        rule = codeRule,
                        onChecksumPolicyChanged = onChecksumPolicyChanged,
                        onRuleChange = onCodeRuleChanged
                    )

                    SettingsPage.DATA -> DataPage(
                        dedupeMode = dedupeMode,
                        dedupeWindowMs = dedupeWindowMs,
                        duplicateAlertEnabled = duplicateAlertEnabled,
                        logSummary = logSummary,
                        crashReportCount = crashReportCount,
                        onDedupeModeChanged = onDedupeModeChanged,
                        onDedupeWindowChanged = onDedupeWindowChanged,
                        onDuplicateAlertChanged = onDuplicateAlertChanged,
                        onExportLogs = onExportLogs,
                        onClearLogs = onClearLogs
                    )
                }
            }

            // ── 翻页条。放在 sheet 内容的最底部，但整条 bar 的高度是固定的，
            //    所以它可以被当作「面板固定开销」的一部分精确预算 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { pageOrdinal = SettingsPage.next(page).ordinal }) {
                    Text("下一类")
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = "${page.ordinal + 1} / ${SettingsPage.entries.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Button(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 各分类页内容
// ──────────────────────────────────────────────────────────────

/** 扫码页：识别反馈 + 自动变焦 + 复制格式 + 会话保存 */
@Composable
private fun ScanPage(
    feedbackEnabled: Boolean,
    vibrateEnabled: Boolean,
    beepEnabled: Boolean,
    autoZoomEnabled: Boolean,
    copyFormat: CopyFormat,
    persistEnabled: Boolean,
    onFeedbackChanged: (Boolean) -> Unit,
    onVibrateChanged: (Boolean) -> Unit,
    onBeepChanged: (Boolean) -> Unit,
    onAutoZoomChanged: (Boolean) -> Unit,
    onCopyFormatChanged: (CopyFormat) -> Unit,
    onPersistChanged: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = "识别反馈",
            subtitle = "成功时震动 + 提示音，连续扫码不连震",
            checked = feedbackEnabled,
            onCheckedChange = onFeedbackChanged
        )
        SwitchRow(
            title = "震动 30ms",
            subtitle = null,
            checked = vibrateEnabled,
            enabled = feedbackEnabled,
            onCheckedChange = onVibrateChanged
        )
        SwitchRow(
            title = "提示音",
            subtitle = null,
            checked = beepEnabled,
            enabled = feedbackEnabled,
            onCheckedChange = onBeepChanged
        )

        SectionDivider()

        SwitchRow(
            title = "自动变焦",
            subtitle = "码太远解不出时自动放大（手动双击过则以手动值为准）",
            checked = autoZoomEnabled,
            onCheckedChange = onAutoZoomChanged
        )

        SectionDivider()

        // 「复制全部」的格式：三个单选压成一行 chip。
        // 原先每种格式一个 RadioRow（带两行副标题）占 210dp，是扫描页最大的开销，
        // 压成一行后整页只需要 ~330dp。含义写在标题下面一行，不丢信息。
        RowLabel(
            title = "「复制全部」的格式",
            hint = "单条复制始终只复制码值本身"
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CopyFormat.entries.forEach { format ->
                FilterChip(
                    selected = format == copyFormat,
                    onClick = { onCopyFormatChanged(format) },
                    label = { Text(format.label) }
                )
            }
        }
        Text(
            text = copyFormat.hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 4.dp)
        )

        SectionDivider()

        SwitchRow(
            title = "记住未导出的记录",
            subtitle = "退出或进程被回收后下次启动提示恢复（只存本机，不联网）",
            checked = persistEnabled,
            onCheckedChange = onPersistChanged
        )
    }
}

/**
 * 识别页：让它更容易解出来。
 *
 * 五项按「先一键、再细调、最后省电」的顺序排：
 * 增强识别（一键提分辨率+变焦）→ 小码精读 → 双引擎补扫 → 分析分辨率（细调）→ 识别间隙（省电）。
 *
 * 「小码精读」是 v1.5 新增，「双引擎补扫」是 v1.7 新增——两者都**不改相机绑定**，
 * 都是「全帧解不出时才多花一次解码」，区别在于：前者解决「码太小」，
 * 后者解决「像素够但 ML Kit 这套算法就是解不出」（两个引擎失败模式互补）。
 *
 * v1.8 把「金属蚀刻码（DPM）阶梯」也挂进了「双引擎补扫」——它同样只跑 ZXing，
 * 而且只在 ZXing 开着时才有意义。不再单开一个开关，是因为识别页已经到面板高度上限，
 * 再多一行就要滚动（见 [SettingsPage.PAGE_HEIGHT_BUDGET_DP]）。
 */
@Composable
private fun EnginePage(
    analysisProfile: ScanTuning.AnalysisProfile,
    enhancedRecognition: Boolean,
    roiRefineEnabled: Boolean,
    zxingFallbackEnabled: Boolean,
    frameIntervalMs: Long,
    onAnalysisProfileChanged: (ScanTuning.AnalysisProfile) -> Unit,
    onEnhancedRecognitionChanged: (Boolean) -> Unit,
    onRoiRefineChanged: (Boolean) -> Unit,
    onZxingFallbackChanged: (Boolean) -> Unit,
    onFrameIntervalChanged: (Long) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = "增强识别（远距 / 小码）",
            subtitle = "自动抬到 1080p 并把变焦上限提到 4×，更吃电",
            checked = enhancedRecognition,
            onCheckedChange = onEnhancedRecognitionChanged
        )

        SectionDivider()

        SwitchRow(
            title = "小码精读（放大重扫）",
            subtitle = "解不出时把候选区域裁出来放大再解一次，对症远距小码与复杂码",
            checked = roiRefineEnabled,
            onCheckedChange = onRoiRefineChanged
        )

        SwitchRow(
            title = "双引擎补扫（ZXing）",
            subtitle = "解不出时交给 ZXing 再试；含金属蚀刻码的尺寸阶梯补扫（CPU 顶盖那种）",
            checked = zxingFallbackEnabled,
            onCheckedChange = onZxingFallbackChanged
        )

        SectionDivider()

        RowLabel(
            title = "分析分辨率",
            hint = "码占的像素越多越容易解出；切换会短暂重建相机画面"
        )
        ScanTuning.AnalysisProfile.entries.forEach { profile ->
            RadioRow(
                title = "${profile.label}  ·  ${"%.1f".format(profile.pixelScale)}×",
                subtitle = profile.hint,
                selected = profile == analysisProfile,
                onClick = { onAnalysisProfileChanged(profile) }
            )
        }

        SectionDivider()

        RowLabel(
            title = "识别间隙：${ScanTuning.frameIntervalLabel(frameIntervalMs)}" +
                "（约 ${ScanTuning.estimatedFramesPerSecond(frameIntervalMs)} 帧/秒）",
            hint = "调大省电降温，代价是响应变慢；与「结果」页的同码间隔是两回事"
        )
        StepSlider(
            value = frameIntervalMs,
            min = ScanTuning.MIN_FRAME_INTERVAL_MS,
            max = ScanTuning.MAX_FRAME_INTERVAL_MS,
            step = ScanTuning.FRAME_INTERVAL_STEP_MS,
            onValueChange = onFrameIntervalChanged
        )
    }
}

/** 结果页：重复码口径 + 同码重复识别间隔 */
/**
 * 数据页：重复码口径 + 同码间隔 + **运行日志（v1.6）**。
 *
 * 日志放在这一页而不是单开一页：面板高度是按「4 页 × 内容预算」钉死的，
 * 加第 5 页要把 [SettingsPage.PANEL_TOTAL_HEIGHT_DP] 整套重新标定（v1.4.3 的坑）。
 * 而日志本身就是「本机数据」的一部分，与去重口径同属「数据怎么落地」这个话题。
 */
@Composable
private fun DataPage(
    dedupeMode: DedupeMode,
    dedupeWindowMs: Long,
    duplicateAlertEnabled: Boolean,
    logSummary: String,
    crashReportCount: Int,
    onDedupeModeChanged: (DedupeMode) -> Unit,
    onDedupeWindowChanged: (Long) -> Unit,
    onDuplicateAlertChanged: (Boolean) -> Unit,
    onExportLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        RowLabel(
            title = "重复码口径",
            hint = "三种口径下时间窗防刷屏都生效，持续对准一个码不会一秒记几十条"
        )
        DedupeMode.entries.forEach { mode ->
            RadioRow(
                title = mode.label,
                subtitle = mode.hint,
                selected = mode == dedupeMode,
                onClick = { onDedupeModeChanged(mode) }
            )
        }

        SectionDivider()

        RowLabel(
            title = "同码重复识别间隔：${dedupeWindowMs} ms",
            hint = "离场多久后才允许重新计入：100ms 抬手就能补扫，5000ms 以上可压住整垛同款"
        )
        StepSlider(
            value = dedupeWindowMs,
            min = DedupeTracker.MIN_WINDOW_MS,
            max = DedupeTracker.MAX_WINDOW_MS,
            step = DedupeTracker.WINDOW_STEP_MS,
            onValueChange = onDedupeWindowChanged
        )

        SectionDivider()

        // 重复码轻提示（v1.7 引入，v1.8.1 由提示改为顶部浮条）：放在口径与间隔之后——
        // 它只影响「怎么提醒」，不影响「怎么算」，顺序上属于补充说明而不是主开关。
        SwitchRow(
            title = "重复码轻提示",
            subtitle = "扫到已有的码时在顶部浮一行（第几行、第几次、算没算），2.5 秒自动消失，不挡扫描；单码 3 秒内最多一次",
            checked = duplicateAlertEnabled,
            onCheckedChange = onDuplicateAlertChanged
        )

        SectionDivider()

        // ── 运行日志（v1.6）──
        RowLabel(
            title = "运行日志",
            hint = if (crashReportCount > 0) {
                "已记录 $crashReportCount 次闪退，点「发送日志」把现场发出去"
            } else {
                "只在出问题时才有用；日志里不含扫码内容"
            }
        )
        Text(
            text = if (logSummary.isBlank()) "尚无日志" else "当前：$logSummary",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onExportLogs, modifier = Modifier.weight(1.4f)) {
                Text("发送日志")
            }
            OutlinedButton(onClick = onClearLogs, modifier = Modifier.weight(1f)) {
                Text("清空")
            }
        }
    }
}

/**
 * 准确页（v1.5）：两道「哪些码该被接受」的关卡。
 *
 * 上面是**码制自带**的数学约束（校验位），下面是**用户自定**的白名单（规则）。
 * 两者机制不同，但用户要解决的问题是同一个——「别让解错的 / 不该要的码进列表」，
 * 所以按问题而不是按实现放在同一页。
 *
 * 校验位做成开关而不是两个 radio：它的取值只有「开 / 关」两种，
 * 摆两个单选行反而比一个开关更难认。枚举本身保留两态，
 * 是为了将来若要加「只记录不丢弃」时才不用改数据模型。
 */
@Composable
private fun AccuracyPage(
    checksumPolicy: ChecksumPolicy,
    rule: CodeRule,
    onChecksumPolicyChanged: (ChecksumPolicy) -> Unit,
    onRuleChange: (CodeRule) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = "校验位检查",
            subtitle = "EAN / UPC 的校验位算不对就直接丢弃，挡掉解错一位的误识",
            checked = checksumPolicy == ChecksumPolicy.ENFORCE,
            onCheckedChange = { on ->
                onChecksumPolicyChanged(if (on) ChecksumPolicy.ENFORCE else ChecksumPolicy.OFF)
            }
        )
        Text(
            text = "只作用于 EAN-13 / EAN-8 / UPC-A / UPC-E；" +
                "Code-128、二维码等本身没有可校验的校验位，一律放行。" +
                "另外 mod-10 对某些相邻位互换是无感的，所以它是大幅降低误识，不是杜绝。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        SectionDivider()

        RuleEditor(rule = rule, onRuleChange = onRuleChange)
    }
}

/**
 * 准入规则编辑区。
 *
 * 输入是**本地状态 + 显式保存**：正则这种东西一边输一边校验会把用户打断
 * （打到一半的 `^[0-9` 必然非法），所以只在点「应用」时才提交给 ViewModel 校验。
 *
 * `remember(rule)` 让外部（例如恢复会话）改了规则后本地输入能跟着刷新，
 * 不会出现面板显示的和实际生效的不一致。
 *
 * v1.4.4：这里**不再**自己带 verticalScroll。滚动统一交给外层内容区——
 * 两层竖向滚动容器嵌套时，外层会在 pre-scroll 阶段先吃掉滑动量，
 * 表现为「里面滚不动、外面滑一点」。单一滚动容器是唯一可控的写法。
 */
@Composable
private fun RuleEditor(
    rule: CodeRule,
    onRuleChange: (CodeRule) -> Unit
) {
    var enabled by remember(rule) { mutableStateOf(rule.enabled) }
    var prefix by remember(rule) { mutableStateOf(rule.prefix) }
    var length by remember(rule) {
        mutableStateOf(if (rule.length > 0) rule.length.toString() else "")
    }
    var regex by remember(rule) { mutableStateOf(rule.regex) }

    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            title = "条码规则过滤",
            subtitle = "只计入符合条件的码，挡掉画面里混进来的其它码",
            checked = enabled,
            onCheckedChange = { enabled = it }
        )
        Text(
            text = "三条都填则必须同时满足；留空的条件不参与判断。全留空 = 不限制。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )

        OutlinedTextField(
            value = prefix,
            onValueChange = { prefix = it },
            label = { Text("前缀（如 69，留空不限）") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = length,
            onValueChange = { if (it.length <= 4) length = it.filter { ch -> ch.isDigit() } },
            label = { Text("长度（如 13，留空不限）") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )
        OutlinedTextField(
            value = regex,
            onValueChange = { regex = it },
            label = { Text("正则（如 ^69\\d{11}$，留空不限）") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        )

        Button(
            onClick = {
                onRuleChange(
                    CodeRule(
                        enabled = enabled,
                        prefix = prefix.trim(),
                        length = length.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        regex = regex.trim()
                    )
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, bottom = 4.dp)
        ) {
            Text("应用规则")
        }
    }
}

// ──────────────────────────────────────────────────────────────
// 通用小组件
// ──────────────────────────────────────────────────────────────

/**
 * 通用步进滑杆。
 *
 * 拖动中只更新本地 Float（不回调），松手才吸附到 [step] 的整数倍并提交——
 * 否则每拖动一像素都写一次 ViewModel，既抖动又会产生一堆无意义的中间值。
 *
 * v1.4.3 修了两个渲染问题：
 *  1. **宽度溢出**：滑杆被套在 `fillMaxWidth()` 的 Column 里时，
 *     Slider 内部轨道会按 `居中 + 固定拇指间距` 反向算出大于父级约束的宽度，
 *     表现为轨道左右伸出面板、圆角与上方的 radio 对不齐。
 *     这里改成用 `padding` 把滑杆的可用宽度**收窄到比文字正文更小**，
 *     从约束层面消除溢出，而不是靠裁剪掩盖。
 *  2. **竖直错位**：给外侧固定 `height(64.dp)`，让滑杆占位确定，
 *     不再随拇指高度与文字基线互相挤压。
 */
@Composable
private fun StepSlider(
    value: Long,
    min: Long,
    max: Long,
    step: Long,
    onValueChange: (Long) -> Unit,
    enabled: Boolean = true
) {
    // Slider 内部用 Float 表示，本地缓存一份避免每次重组把 Long 转来转去抖动
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = {
                val snapped = ((local / step.toFloat()).roundToLong() * step)
                onValueChange(snapped.coerceIn(min, max))
            },
            valueRange = min.toFloat()..max.toFloat(),
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 不能用固定 height(56.dp)：副标题在窄屏上会折成 2~3 行，固定高度装不下
            //   会被**静默裁掉**，表现为文字被切一半——这就是 v1.4.1「排版乱」的直接来源。
            //   改用 heightIn(min = 56.dp)，让行高跟着文字实际行数长。
            .heightIn(min = 52.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.outline
                }
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    // 副标题不再限制行数：宁可这一行变高，也不能把文字切掉
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        // 开关和文字之间留出间距，窄屏上文字贴到开关会显得很挤
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun RadioRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 同 SwitchRow：档位说明有两三行长，固定 56dp 会把文字切掉
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

/** 区块小标题 + 一行说明。分页后每页的垂直空间紧张，标题与说明合并成一个组件便于核算高度 */
@Composable
private fun RowLabel(title: String, hint: String) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = hint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

/**
 * 区块分隔线。上下各留 8dp（v1.4.4 从 12dp 收窄：一页最多 4 条分隔线，
 * 每条 24dp 的留白合计近 100dp，是页高的主要开销之一）。
 * 收窄后仍是页内的呼吸感来源，也让「一页有哪几个区块」一眼可数。
 */
@Composable
private fun SectionDivider() {
    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
}

/** 预留：分页内的空态提示（当前未使用，保留以避免后续加页时再引一次 TextAlign） */
@Suppress("unused")
@Composable
private fun EmptyPageHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
    )
}
