package com.example.scanstock.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanstock.domain.DedupeMode
import com.example.scanstock.domain.DuplicateAlert
import com.example.scanstock.export.Exporter
import com.example.scanstock.logging.AppLog
import com.example.scanstock.ui.components.CameraPreviewCard
import com.example.scanstock.ui.components.HintTone
import com.example.scanstock.ui.components.PermissionPanel
import com.example.scanstock.ui.components.ResultListPanel
import com.example.scanstock.ui.components.ScanHintSpec
import com.example.scanstock.ui.components.ScanStatusBar
import com.example.scanstock.ui.components.SettingsSheet
import kotlinx.coroutines.delay

// 提示的时长、落点、配色统一走 [ScanHintSpec]/[HintTone]（v1.8.4）。
// 这里不再自己定义常量——正是「每处一条私有 const」让上一版出现了四档时长。

private const val TAG = "ScanScreen"

/**
 * 主屏：单页承载「预览 → 列表 → 复制 / 导出」主链路，不设导航图。
 *
 * 盘点场景的核心体验是「单手、连续、不中断」，任何页面跳转都是干扰，
 * 所以设置用 BottomSheet、相册用系统 Photo Picker、导出用系统分享面板，
 * 全都以浮层形式出现，主屏始终停在同一处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(viewModel: ScanViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // ───────────── 状态栏通知（v1.8.7）─────────────
    //
    // 这里**不能再用 Material 的 SnackbarHostState**。它的 showSnackbar 会一直挂起，
    // 直到有人调用 dismiss() 或 performAction()——而那个「到点儿调 dismiss」的定时器
    // 写在 SnackbarHost 这个 Composable 里。v1.8.6 为了让提示离开相机画面，删掉了
    // SnackbarHost，改成直接读 currentSnackbarData 自己画，于是再没人调 dismiss：
    //
    //   第一次 showSnackbar 永久挂起 → events.collect 整个协程卡死
    //   → 第一次复制还能成功，之后点多少次都没反应，
    //     撤销 / 导出 / 设置回执也全部静默失效。
    //
    // 所以改成自己持有状态 + 显式定时器，不再依赖任何 Material 组件的内部实现。
    var notice by remember { mutableStateOf<Notice?>(null) }
    // 撤销单独一槽：它不能被普通通知顶掉（窗口一消失就真找不回来了）
    var undoNotice by remember { mutableStateOf<Notice?>(null) }
    var noticeSeq by remember { mutableIntStateOf(0) }

    /** 推一条通知到状态栏。id 递增，保证「连续两条相同文案」也会重新计时 */
    fun pushNotice(
        text: String,
        tone: HintTone,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        noticeSeq += 1
        notice = Notice(noticeSeq, text, tone, actionLabel, onAction)
    }

    // ───────────── 权限状态 ─────────────
    var granted by rememberSaveable {
        mutableStateOf(context.hasCameraPermission())
    }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        requestedOnce = true
        granted = isGranted
        if (!isGranted) {
            pushNotice("未获得相机权限，你仍可用相册导入图片识别", HintTone.ACTION)
        }
    }

    // 从系统设置返回时重新校验权限；切到后台时立刻落盘（不等 500ms 防抖窗口）
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    granted = context.hasCameraPermission()
                    // 每次回到前台重算一次日志规模：崩溃可能就发生在上一次后台期间，
                    // 重新进来要能立刻看到横幅（不重新统计的话要等下次冷启动）
                    viewModel.refreshLogSummary()
                }
                Lifecycle.Event.ON_STOP -> viewModel.flushPersist()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首次进入自动请求一次；若已被永久拒绝，按钮会变成「去系统设置」
    LaunchedEffect(Unit) {
        if (!granted && !requestedOnce) {
            requestedOnce = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val permanentlyDenied = !granted &&
        requestedOnce &&
        (context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            ?: false).not()

    // ───────────── 相册选择（Photo Picker，无需存储权限）─────────────
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) viewModel.decodeFromGallery(uri)
    }

    // ───────────── 一次性事件消费 ─────────────
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            // 单条事件处理失败不能打死整个事件流：协程一旦被取消，
            // 后面所有提示都会静默失效，表现出来就是「按了没反应」，极难查。
            try {
                when (event) {
                    is UiEvent.CopyToClipboard -> {
                        val ok = context.copyToClipboard(event.text)
                        val message = when {
                            !ok -> "剪贴板不可用，复制失败"
                            // 单条复制永远只有码值，与「复制全部」的格式设置无关，提示语不并列格式名
                            event.isSingle -> "已复制该条码值"
                            else -> "已复制 ${event.itemCount} 条（${event.formatLabel}）"
                        }
                        pushNotice(message, if (ok) HintTone.SUCCESS else HintTone.ACTION)
                    }

                    is UiEvent.ShareExport -> {
                        // 交给系统分享面板：微信 / QQ / 邮件 / 网盘 / 蓝牙都能收，零权限零联网
                        val sent = context.shareFile(event.uri, event.mime)
                        pushNotice(
                            text = if (sent) "已导出 ${event.fileName}（${event.summary}）"
                            else "没有可接收文件的应用",
                            tone = if (sent) HintTone.SUCCESS else HintTone.ACTION
                        )
                    }

                    is UiEvent.UndoableMessage -> {
                        noticeSeq += 1
                        undoNotice = Notice(
                            id = noticeSeq,
                            text = event.text,
                            tone = HintTone.NEUTRAL,
                            actionLabel = "撤销",
                            onAction = {
                                // 先收掉按钮再执行：undoDelete 会回一条「已恢复」，
                                // 不先清空的话两条会抢同一格
                                undoNotice = null
                                viewModel.undoDelete(event.token)
                            }
                        )
                    }

                    is UiEvent.Message -> pushNotice(event.text, HintTone.NEUTRAL)
                }
            } catch (t: Throwable) {
                AppLog.e(TAG, "处理界面事件失败：$event", t)
            }
        }
    }

    // 通知自动消失。撤销窗口单独计时：**它过期时才作废 ViewModel 里的快照**——
    // 按钮还在就表示撤销还有效，这两件事必须严格同步。
    LaunchedEffect(notice?.id) {
        val current = notice ?: return@LaunchedEffect
        delay(if (current.tone == HintTone.ACTION) ScanHintSpec.ACTION_MS else ScanHintSpec.INFO_MS)
        if (notice?.id == current.id) notice = null
    }

    LaunchedEffect(undoNotice?.id) {
        val current = undoNotice ?: return@LaunchedEffect
        delay(ScanHintSpec.UNDO_MS)
        if (undoNotice?.id == current.id) {
            undoNotice = null
            viewModel.dismissUndo()
        }
    }

    // ───────────── 状态栏内容（v1.8.6）─────────────
    //
    // v1.8.4 把提示收成三个落点，但三个落点**全都在相机画面上**：顶部一条、
    // 底部一条、四角角标。用户的反馈一针见血——
    //
    //   > 不要在镜头显示的地方放浮窗呀，都看不到是否清楚了。
    //
    // 扫码时唯一需要判断的就是「对准没有、清不清楚」，而那块像素恰好被胶囊盖住。
    // 提示再怎么统一，只要压在画面上就是在妨碍主任务。
    //
    // 现在画面里只剩取景框本体（瞄准器），其余全部进画面正下方那条固定 52dp 的
    // 状态栏。高度固定 → 内容切换时画面不会跟着跳；空着时显示常驻引导，不浪费空间。
    //
    // 优先级（从高到低）：
    //   1. 撤销   —— 带按钮，用户得能立刻点到，4 秒后作废
    //   2. 重复码 —— 「这次算没算」只有它会说，列表里看不到
    //   3. 太近失焦 —— 用户正在做错一件事
    //   4. 疑似有码 —— 方向性引导
    //   5. 识别回执 —— 只是回执，列表第一行就是它
    //   6. 位置引导 —— 新手常识，谁都能打断
    //   7. 识别吞吐 —— 诊断信息
    //   8. 常驻引导 —— 什么都没发生时的底噪
    var guide by remember { mutableStateOf<Guide?>(null) }

    // 进场位置引导：8 秒后自己收。以前它常驻在取景框下沿，正是最该看清的位置。
    LaunchedEffect(Unit) {
        guide = Guide.POSITION
        delay(ScanHintSpec.POSITION_GUIDE_MS)
        if (guide == Guide.POSITION) guide = null
    }

    LaunchedEffect(state.potentialTick) {
        if (state.potentialTick == 0) return@LaunchedEffect
        // 失焦提示更具体（它说清了「怎么改」），不被这条顶掉
        if (guide == Guide.FOCUS) return@LaunchedEffect
        guide = Guide.POTENTIAL
        delay(ScanHintSpec.INFO_MS)
        if (guide == Guide.POTENTIAL) guide = null
    }

    LaunchedEffect(state.focusHintTick) {
        if (state.focusHintTick == 0) return@LaunchedEffect
        guide = Guide.FOCUS
        delay(ScanHintSpec.ACTION_MS)
        if (guide == Guide.FOCUS) guide = null
    }

    // 识别吞吐：由 CameraPreviewCard 每秒回调一次（null = 恢复正常）
    var perfText by remember { mutableStateOf<String?>(null) }

    // 识别成功回执：扫到一个显示 2.5 秒。值从 state.lastValue 取，
    // 用 flashTick 当触发沿（同一帧可能连扫多个，只要最后那个值）。
    var hitValue by remember { mutableStateOf<String?>(null) }
    var hitRow by remember { mutableStateOf(0) }
    LaunchedEffect(state.flashTick) {
        if (state.flashTick == 0) return@LaunchedEffect
        hitValue = state.lastValue
        hitRow = state.count
        delay(ScanHintSpec.INFO_MS)
        hitValue = null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            // 提示条不再挂在 Scaffold 上（v1.8.3）：Scaffold 的 snackbarHost 只会落在
            // 底部、浮在「复制全部 / 导出 / 清空」之上，正好盖住连续盘点时最常用的三个按钮。
            // 现在统一由外层 Box 的顶部提示槽承载，与重复码轻提示同一位置、同一套样式。
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("盘点扫描", style = MaterialTheme.typography.titleMedium)
                            Text(
                                // 数量合计只在计数聚合模式下才会与条数不同，平时不显示，避免噪音
                                text = when {
                                    state.isEmpty -> "等待识别"
                                    state.totalQty != state.count -> "已扫 ${state.count} 条 · 数量合计 ${state.totalQty}"
                                    else -> "已扫 ${state.count} 条"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    ),
                    actions = {
                        TextButton(onClick = {
                            // 相册入口始终可用：即使没有相机权限，也能靠图片完成识别
                            photoPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        }) { Text("相册") }
                        IconButton(onClick = { viewModel.openSheet() }) {
                            Icon(Icons.Filled.Settings, contentDescription = "设置")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.requestCopyAll() },
                            modifier = Modifier.weight(2f)
                        ) {
                            // 把当前格式直接写在按钮上：默认即纯文本，不必进设置面板确认
                            Text("复制全部 (${state.count}) · ${state.copyFormat.label}")
                        }
                        ExportMenuButton(
                            enabled = !state.isEmpty,
                            onExport = viewModel::requestExport,
                            modifier = Modifier.weight(1.2f)
                        )
                        FilledTonalButton(
                            onClick = { viewModel.clearAll() },
                            enabled = !state.isEmpty,
                            modifier = Modifier.weight(0.9f)
                        ) {
                            Text("清空")
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                // 上次未收尾的会话：只提示、不自动恢复，选错口径比多一步操作更烦。
                // 取局部变量：state 是 collectAsStateWithLifecycle 的委托属性，
                // 对它的成员做智能转换编译器不允许（多线程可见性问题）
                val pending = state.pendingRestore
                if (pending != null) {
                    RestoreCard(
                        count = pending.items.size,
                        onRestore = viewModel::restorePending,
                        onDiscard = viewModel::discardPending
                    )
                }

                // 崩溃报告提示（v1.6）：本机记到了闪退就主动说一声，并给一个「发日志」的入口。
                // 放在会话恢复卡下面：恢复会话是当务之急，日志可以稍后再发。
                if (state.crashReportCount > 0 && !state.crashNoticeDismissed) {
                    CrashNoticeCard(
                        count = state.crashReportCount,
                        summary = state.logSummary,
                        onSend = viewModel::requestExportLogs,
                        onDismiss = viewModel::dismissCrashNotice
                    )
                }

                if (granted) {
                    // 预览卡片占约 46% 高度（原 42%）。抬高这一档是因为卡片内部
                    // 固定分走 52dp 给状态栏——净给画面的高度和以前基本持平，
                    // 但画面里从此干干净净。用 weight 而非固定 dp，横竖屏都能自适应。
                    CameraPreviewCard(
                        torchOn = state.torchOn,
                        zoomRatio = state.zoomRatio,
                        flashTick = state.flashTick,
                        autoZoomEnabled = state.autoZoomEnabled,
                        analysisProfile = state.analysisProfile,
                        enhancedRecognition = state.enhancedRecognition,
                        frameIntervalMs = state.frameIntervalMs,
                        roiRefineEnabled = state.roiRefineEnabled,
                        zxingFallbackEnabled = state.zxingFallbackEnabled,
                        onDetected = viewModel::onCodesDetected,
                        onError = viewModel::onAnalyzerError,
                        onCameraReady = viewModel::onCameraCapabilities,
                        onDoubleTapZoom = viewModel::toggleDoubleTapZoom,
                        onZoomApplied = viewModel::setZoom,
                        onFocusHelp = viewModel::onFocusHelp,
                        onPerfText = { perfText = it },
                        statusBar = {
                            val content = statusOf(
                                undoNotice = undoNotice,
                                notice = notice,
                                duplicate = state.duplicateAlert,
                                guide = guide,
                                hitValue = hitValue,
                                hitRow = hitRow,
                                perfText = perfText
                            )
                            ScanStatusBar(
                                text = content.text,
                                tone = content.tone,
                                value = content.value,
                                actionLabel = content.actionLabel,
                                onAction = content.onAction
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.46f)
                            .padding(horizontal = 12.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 提示文字权重给小一些：小屏上 FilterChip + 闪光灯按钮会把
                        // 它挤到换行，导致整行高度跳动
                        Text(
                            text = "双击画面切换 1x / 2x",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        // 三态口径直接放主屏：盘到一半要换口径时单手就能切。
                        // 点一次换一种，chip 上永远显示当前口径，不必进设置面板看。
                        FilterChip(
                            selected = state.dedupeMode != DedupeMode.STRICT,
                            onClick = viewModel::cycleDedupeMode,
                            label = { Text(state.dedupeMode.label) }
                        )
                        TextButton(
                            onClick = {
                                if (!state.hasFlashUnit && !state.torchOn) {
                                    viewModel.reportNoFlashUnitWhileTurningOn()
                                } else {
                                    viewModel.toggleTorch()
                                }
                            }
                        ) {
                            Text(if (state.torchOn) "闪光灯 开" else "闪光灯 关")
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth()) {
                        PermissionPanel(
                            permanentlyDenied = permanentlyDenied,
                            onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            onOpenSettings = { context.openAppSettings() }
                        )
                    }
                }

                ResultListPanel(
                    items = state.items,
                    onItemClick = { viewModel.requestCopySingle(it) },
                    onItemDelete = { viewModel.deleteItem(it.seq) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }

    if (state.sheetOpen) {
        SettingsSheet(            feedbackEnabled = state.feedbackEnabled,
            vibrateEnabled = state.vibrateEnabled,
            beepEnabled = state.beepEnabled,
            copyFormat = state.copyFormat,
            dedupeMode = state.dedupeMode,
            dedupeWindowMs = state.dedupeWindowMs,
            codeRule = state.codeRule,
            autoZoomEnabled = state.autoZoomEnabled,
            analysisProfile = state.analysisProfile,
            enhancedRecognition = state.enhancedRecognition,
            frameIntervalMs = state.frameIntervalMs,
            checksumPolicy = state.checksumPolicy,
            roiRefineEnabled = state.roiRefineEnabled,
            zxingFallbackEnabled = state.zxingFallbackEnabled,
            duplicateAlertEnabled = state.duplicateAlertEnabled,
            persistEnabled = state.persistEnabled,
            logSummary = state.logSummary,
            crashReportCount = state.crashReportCount,
            onFeedbackChanged = viewModel::setFeedbackEnabled,
            onVibrateChanged = viewModel::setVibrateEnabled,
            onBeepChanged = viewModel::setBeepEnabled,
            onCopyFormatChanged = viewModel::setCopyFormat,
            onDedupeModeChanged = viewModel::setDedupeMode,
            onDedupeWindowChanged = viewModel::setDedupeWindow,
            onCodeRuleChanged = viewModel::setCodeRule,
            onAutoZoomChanged = viewModel::setAutoZoomEnabled,
            onAnalysisProfileChanged = viewModel::setAnalysisProfile,
            onEnhancedRecognitionChanged = viewModel::setEnhancedRecognition,
            onFrameIntervalChanged = viewModel::setFrameInterval,
            onChecksumPolicyChanged = viewModel::setChecksumPolicy,
            onRoiRefineChanged = viewModel::setRoiRefineEnabled,
            onPersistChanged = viewModel::setPersistEnabled,
            onExportLogs = viewModel::requestExportLogs,
            onClearLogs = viewModel::clearLogs,
            onZxingFallbackChanged = viewModel::setZxingFallbackEnabled,
            onDuplicateAlertChanged = viewModel::setDuplicateAlertEnabled,
            onDismiss = viewModel::closeSheet
        )
    }

    // ───────────── 重复码轻提示的自动收起（v1.8.1）─────────────
    //
    // 计时放在这里而不是提示条内部：提示条是纯展示组件，谁来决定它活多久
    // 应该由屏幕这一层掌握，否则组件一旦被挪到别处就会不受控地一直挂着。
    // 连扫时用户不会去点任何东西，所以只靠超时收起，没有「知道了」按钮。
    val duplicateAlert = state.duplicateAlert
    LaunchedEffect(duplicateAlert) {
        if (duplicateAlert != null) {
            delay(ScanHintSpec.INFO_MS)
            viewModel.dismissDuplicateAlert()
        }
    }
}

/**
 * 导出按钮 + 格式选择菜单。
 *
 * 三种格式做成下拉而不是三个按钮：底部栏横向空间有限，而导出不是高频操作，
 * 多点一次可以接受。菜单项按「纯文本 → CSV → Excel」从简到繁排列。
 */
@Composable
private fun ExportMenuButton(
    enabled: Boolean,
    onExport: (Exporter.Kind) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        FilledTonalButton(
            onClick = { expanded = true },
            enabled = enabled
        ) { Text("导出") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Exporter.Kind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        expanded = false
                        onExport(kind)
                    }
                )
            }
        }
    }
}

/** 上次未收尾会话的恢复提示卡 */
@Composable
private fun RestoreCard(
    count: Int,
    onRestore: () -> Unit,
    onDiscard: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "上次有 $count 条没导出的记录",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDiscard) { Text("丢弃") }
            Button(onClick = onRestore) { Text("恢复") }
        }
    }
}

/**
 * 崩溃报告提示卡（v1.6）。
 *
 * ## 为什么要把这件事摆到主屏，而不是藏在设置里
 *
 * 日志只要不主动提示就等于不存在 —— 用户不会想到去设置面板里翻。
 * 而闪退是「发生了但说不清」的问题，唯一的破局办法就是让用户**顺手点一下发过来**。
 *
 * 两条文案分开写：上面一句说事实（记到了几次），下面一句说这文件里有什么
 * （设备型号 / 崩溃堆栈 / 崩溃前的事件流），用户才知道自己发出去的到底是什么，
 * 不至于担心把盘点数据一起发出去 —— 日志里确实不含码值（见 AppLog 的说明）。
 */
@Composable
private fun CrashNoticeCard(
    count: Int,
    summary: String,
    onSend: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                text = "检测到 $count 次闪退记录",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "日志里有机型、系统版本和崩溃堆栈，不含扫码内容（$summary）。发给开发者能直接定位。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("忽略") }
                Button(onClick = onSend, modifier = Modifier.weight(1.4f)) { Text("发送日志") }
            }
        }
    }
}

/**
 * 状态栏里可能出现的「引导类」内容（v1.8.6）。
 *
 * **声明顺序即优先级**（后面的压前面的）。加新内容时想清楚它该排在谁前面——
 * 排在 [POSITION] 之前意味着它会盖掉新手指引，排在 [FOCUS] 之后则意味着
 * 用户正在犯的那个错优先级最高。
 */
internal enum class Guide { POSITION, POTENTIAL, FOCUS }

/**
 * 状态栏上的一条临时通知（v1.8.7）。
 *
 * 取代 Material 的 [androidx.compose.material3.SnackbarHostState]：
 * 那个类的生命周期由 SnackbarHost 这个 Composable 驱动，界面一旦不再渲染它，
 * `showSnackbar` 就永远不会返回（详见 [ScanScreen] 里的说明）。自己持有状态
 * 反而更简单——反正最终画的是我们自己的状态栏，本来也不需要 Snackbar 的壳。
 *
 * @param id        自增序号。用来区分「两条内容完全相同的通知」，
 *                  靠它让定时器重新计时，而不是启动定时器后就不再管
 * @param actionLabel 右侧按钮文字（目前只有「撤销」）
 * @param onAction  按钮回调
 */
internal data class Notice(
    val id: Int,
    val text: String,
    val tone: HintTone,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
) {
    /**
     * 转成状态栏内容。
     *
     * 没有按钮文字就把 [onAction] 一并清掉：状态栏的按钮渲染条件是
     * 「label 和回调都非空」，但留着一个用不上的回调，会在以后改渲染条件时
     * 变成一个点不动、或者点了报错的按钮。
     */
    internal fun toStatus(): StatusContent = StatusContent(
        text = text,
        tone = tone,
        actionLabel = actionLabel,
        onAction = if (actionLabel != null) onAction else null
    )
}

/**
 * 状态栏此刻要显示的那一条。
 *
 * 单独建模而不是往 Composable 里塞一串 if/else：优先级规则是这个页面最容易
 * 越改越乱的地方，集中到一个纯函数里，以后调顺序只改一处。
 */
internal data class StatusContent(
    val text: String,
    val tone: HintTone,
    val value: String? = null,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

/**
 * 从各路状态里挑出**此刻最该说的那一句**。
 *
 * 下面的 if 序列就是优先级本身，顺序别乱改：
 * 撤销（错过就找不回）→ 通知（复制/导出/设置回执）→ 重复码（只有它会说这次算没算）
 * → 失焦（用户正在犯错）→ 疑似有码 → 识别回执（列表里也有）→ 位置引导（新手常识）
 * → 识别吞吐（诊断）→ 底噪。
 */
internal fun statusOf(
    undoNotice: Notice?,
    notice: Notice?,
    duplicate: DuplicateAlert?,
    guide: Guide?,
    hitValue: String?,
    hitRow: Int,
    perfText: String?
): StatusContent {
    // 撤销永远第一条：它是唯一「错过有代价」的一条，不能被任何通知顶掉
    if (undoNotice != null) return undoNotice.toStatus()
    if (notice != null) return notice.toStatus()
    if (duplicate != null) {
        // 「算没算」+「落在第几行第几次」都必须说清楚：
        // 去重挡掉时画面毫无反应，用户只能靠这条判断该不该继续对准
        return StatusContent(
            text = "${duplicate.title()} · ${duplicate.detail()}",
            tone = HintTone.ACTION,
            value = duplicate.value
        )
    }
    if (guide == Guide.FOCUS) {
        return StatusContent("太近会失焦 · 退到 15cm 点屏对焦", HintTone.ACTION)
    }
    if (guide == Guide.POTENTIAL) {
        return StatusContent("疑似有码 · 再靠近一点稳住", HintTone.ACTION)
    }
    if (hitValue != null) {
        return StatusContent(
            text = if (hitRow > 0) "已计入 · 第 $hitRow 条" else "已计入",
            tone = HintTone.SUCCESS,
            value = hitValue
        )
    }
    if (guide == Guide.POSITION) {
        return StatusContent("方码压在中心 · 四边留白", HintTone.NEUTRAL)
    }
    if (perfText != null) {
        return StatusContent(perfText, HintTone.ACTION)
    }
    // 什么都没发生时也不留空：这条底噪本身有用，且让状态栏高度恒定
    return StatusContent("对准条码 · 自动识别", HintTone.NEUTRAL)
}


// ─────────────────────────── 平台侧小工具 ───────────────────────────

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** 写剪贴板。返回 false 表示系统剪贴板服务不可用（极少见，但必须兜住） */
private fun Context.copyToClipboard(text: String): Boolean {
    val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: return false
    return runCatching {
        manager.setPrimaryClip(ClipData.newPlainText("scan_result", text))
    }.isSuccess
}

/**
 * 拉起系统分享面板。
 *
 * 必须带 FLAG_GRANT_READ_URI_PERMISSION：content:// Uri 默认对接收方无权限，
 * 少了这一 flag 微信/QQ 会提示「文件不存在」。
 * 用 queryIntentActivities 先探一手，避免没有任何接收应用时抛 ActivityNotFoundException。
 */
private fun Context.shareFile(uri: Uri, mime: String): Boolean {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "导出盘点结果")
    return if (intent.resolveActivity(packageManager) != null) {
        runCatching { startActivity(chooser) }.isSuccess
    } else {
        false
    }
}

private fun Context.openAppSettings() {
    runCatching {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }
}

/** Android 11+ 起 shouldShowRequestPermissionRationale 只有 Activity 才有 */
private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
