package com.example.scanstock.ui.components

import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.scanstock.camera.BarcodeAnalyzer
import com.example.scanstock.camera.BarcodeFormats
import com.example.scanstock.domain.DetectedCode
import com.example.scanstock.domain.ScanPerf
import com.example.scanstock.domain.ScanTuning
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

/**
 * 相机预览 + 图像分析绑定卡片。
 *
 * 生命周期：用 DisposableEffect 持有 ProcessCameraProvider / Executor / ML Kit Scanner，
 * 在 onDispose 中全部释放。旋转屏幕时 Activity 重建 → 本 Composable 销毁重建 →
 * 相机自动重新绑定到新的 PreviewView，不需要 configChanges 手动处理。
 *
 * 触控：单击对焦、双击切换 1x / 2x。pointerInput 只挂在**画面区**那层 Box 上，
 * PreviewView 本身不消费触摸事件；状态栏在它下方，按钮点击不会被手势吃掉。
 *
 * v1.4 调优点：
 * - [analysisProfile] 决定分析分辨率，默认 720p（与 v1.3 一致）；
 *   切到 1080p / 1440p 可显著提升**小二维码与复杂二维码**的识别率。
 * - [enhancedRecognition] 打开后会把变焦上限抬到至少 4x，并配合用户选的档位工作。
 * - [frameIntervalMs] 是「识别间隙」：分析器会按这个间隔节流送检帧。
 * - [roiRefineEnabled] 是「小码精读」（v1.5）：粗检只拿到候选框时，
 *   把那一小块裁出来放大再解一次。同样**不重建相机**，与帧间隔同一套机制。
 *
 * **画面里不许放任何提示**（v1.8.6）：唯一允许的叠层是取景框本体（瞄准器）。
 * 识别吞吐、缩放倍率、失焦提醒、识别回执全部交给调用方传进来的 [statusBar]，
 * 渲染在画面正下方那条固定高度的状态栏里——用户要能毫无遮挡地判断有没有对准。
 *
 * 组成本身是 `Column { 画面(weight=1f) ; statusBar() }`，卡片圆角裁在整体上，
 * 视觉上就是「取景窗 + 相机状态栏」一整块。
 *
 * **分辨率与变焦上限的变化需要重建相机绑定**，因此这两个值只作为
 * DisposableEffect 的 key 使用，不通过 LaunchedEffect 动态改——CameraX 的
 * `ImageAnalysis` 分辨率一旦绑定就不可变，只能解绑重绑。
 */
@Composable
fun CameraPreviewCard(
    torchOn: Boolean,
    zoomRatio: Float,
    flashTick: Int,
    autoZoomEnabled: Boolean,
    analysisProfile: ScanTuning.AnalysisProfile,
    enhancedRecognition: Boolean,
    frameIntervalMs: Long,
    roiRefineEnabled: Boolean,
    zxingFallbackEnabled: Boolean,
    onDetected: (List<DetectedCode>) -> Unit,
    onError: (Throwable) -> Unit,
    onCameraReady: (maxZoom: Float, hasFlashUnit: Boolean) -> Unit,
    onDoubleTapZoom: () -> Unit,
    onZoomApplied: (Float) -> Unit,
    onFocusHelp: () -> Unit,
    /** 识别吞吐变慢/恢复时回调（null = 恢复正常）。文案交给调用方显示在画面外 */
    onPerfText: (String?) -> Unit,
    /** 画面正下方的状态栏。**画面内不允许再叠任何提示**（v1.8.6） */
    statusBar: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }

    // 回调用 rememberUpdatedState 包一层，避免 lambda 引用变化触发相机重建
    val currentOnDetected by rememberUpdatedState(onDetected)
    val currentOnError by rememberUpdatedState(onError)
    val currentOnCameraReady by rememberUpdatedState(onCameraReady)
    // 自动变焦的开关与回调同样包一层：它们的 lambda 每次重组都是新对象，
    // 直接进 DisposableEffect 的依赖会导致相机反复解绑重绑（黑屏闪烁 + 识别中断）
    val currentAutoZoom by rememberUpdatedState(autoZoomEnabled)
    val currentOnZoomApplied by rememberUpdatedState(onZoomApplied)
    // v1.5：ROI 精读开关。它只进分析器的 @Volatile 字段，**不进 DisposableEffect 的 key**——
    // 切换它不需要重建相机（相机重建会黑屏闪一下，代价远大于收益）。
    val currentRoiRefine by rememberUpdatedState(roiRefineEnabled)
    // v1.7：第二引擎开关与「太近会失焦」回调。理由同上——不进 DisposableEffect 的 key
    val currentZxing by rememberUpdatedState(zxingFallbackEnabled)
    val currentOnFocusHelp by rememberUpdatedState(onFocusHelp)
    val currentOnPerfText by rememberUpdatedState(onPerfText)

    // 增强识别：把「关掉自动变焦」的情况也算进来——变焦上限只在相机绑定时读一次。
    // 打开增强识别时至少给到 BOOST_MIN_ZOOM，否则远距小码光靠分辨率依然解不出。
    val effectiveMaxZoomFloor = if (enhancedRecognition) ScanTuning.BOOST_MIN_ZOOM else 1f
    // 用 rememberUpdatedState 包住，避免每次重组都换对象导致 DisposableEffect 重建
    val currentMaxZoomFloor by rememberUpdatedState(effectiveMaxZoomFloor)

    val previewView = remember {
        PreviewView(context).apply {
            // TextureView 渲染模式：兼容性最好，且 Compose 浮层（取景框/提示）能稳定盖在上面
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var maxZoom by remember { mutableFloatStateOf(1f) }

    // 当前活跃的分析器实例。只用于在不重建相机的前提下改「识别间隙」——
    // 用 mutableStateOf 而非普通 var 是为了让 LaunchedEffect(frameIntervalMs)
    // 在实例就绪后能重新执行一次（首帧时 holder 还是 null，改了也推不进去）。
    var analyzerHolder by remember { mutableStateOf<BarcodeAnalyzer?>(null) }

    // 分辨率档位 / 增强识别 变化时必须重建整个相机绑定（见上方 KDoc 说明）。
    // frameIntervalMs 不在 key 里 —— 它是 @Volatile，直接改分析器字段即可，无需重绑。
    DisposableEffect(lifecycleOwner, analysisProfile, enhancedRecognition) {
        val analyzerExecutor = Executors.newSingleThreadExecutor()
        // 自动变焦：ML Kit 在「画面里有码但太远解不出」时回调建议倍率。
        // 这里不传 maxSupportedZoomRatio（创建扫描器时相机还没绑好，拿不到真实上限），
        // 改为在回调里 clamp 到 maxZoom —— 缺了这一步，库会给出超过硬件能力的倍率。
        val scanner = BarcodeFormats.createScanner(
            onZoomSuggested = { suggested ->
                if (!currentAutoZoom) {
                    false    // 用户关了自动变焦：回 false，ML Kit 不会重复建议
                } else {
                    val bound = camera
                    if (bound == null) {
                        false
                    } else {
                        // 变焦上限同样要兜住增强识别给出的下限，否则小码拉不近
                        val ceiling = maxOf(maxZoom, currentMaxZoomFloor)
                        val clamped = suggested.coerceIn(1f, ceiling)
                        bound.cameraControl.setZoomRatio(clamped)
                        // 同步回 UI：右下角的倍率提示要跟着变，否则用户不知道画面被放大了
                        currentOnZoomApplied(clamped)
                        true
                    }
                }
            }
        )
        val analyzer = BarcodeAnalyzer(
            scanner = scanner,
            onResult = { codes -> currentOnDetected(codes) },
            onError = { e -> currentOnError(e) },
            frameIntervalMs = frameIntervalMs,
            // v1.5：ROI 精读的同步等待必须跑在分析线程上。
            // 指定成 analyzerExecutor（而不是 ML Kit 默认的主线程回调），
            // 否则 Tasks.await() 会把 UI 线程直接卡住。
            roiRefineEnabled = currentRoiRefine,
            // v1.7：第二引擎补扫 + 「连续解不出」回调
            zxingFallbackEnabled = currentZxing,
            onFocusHelp = { currentOnFocusHelp() },
            workExecutor = analyzerExecutor
        )
        // 把实例挂到 holder 上，让 Composable 之外也能改它的帧间隔（不触发相机重绑）
        analyzerHolder = analyzer

        var provider: ProcessCameraProvider? = null
        var disposed = false

        val future = ProcessCameraProvider.getInstance(context)
        val bindRunnable = Runnable {
            if (disposed) return@Runnable
            val cameraProvider = runCatching { future.get() }.getOrElse { e ->
                currentOnError(e)
                return@Runnable
            }
            provider = cameraProvider

            // 分析分辨率由档位决定（默认 720p = v1.3 行为）：
            //  • 720p   —— 单帧耗时低，连续扫描帧率高，适合大码/近距
            //  • 1080p  —— 像素数 2.25 倍，小二维码和细密 DataMatrix 的解码率明显提升
            //  • 1440p  —— 高版本复杂码；设备不支持时由 FALLBACK 规则自动降档
            val resolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(analysisProfile.width, analysisProfile.height),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .build()
                // 显式调用 setter：Preview 只有 setSurfaceProvider 没有 getter，
                // Kotlin 不会为它合成 surfaceProvider 属性，写成属性赋值会编译失败
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                // 关键：只保留最新帧，配合分析器里的 imageProxy.close() 形成天然背压
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analyzerExecutor, analyzer) }

            runCatching {
                cameraProvider.unbindAll()
                val bound = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
                camera = bound
                maxZoom = bound.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                // 增强识别：把可用变焦上限抬到至少 BOOST_MIN_ZOOM，
                // 但要受硬件真实上限 maxZoom 约束，不能凭空放大。
                val usableZoom = maxOf(maxZoom, currentMaxZoomFloor)
                val hasFlash = bound.cameraInfo.hasFlashUnit()
                currentOnCameraReady(usableZoom, hasFlash)
            }.onFailure { e ->
                // 相机被占用 / 无后置镜头：给可读提示，不崩溃
                currentOnError(e)
            }
        }
        future.addListener(bindRunnable, mainExecutor)

        onDispose {
            disposed = true
            analyzerHolder = null
            runCatching { provider?.unbindAll() }
            analyzerExecutor.shutdown()
            runCatching { scanner.close() }
        }
    }

    // 识别间隙（帧间隔）：**不需要重建相机**，直接把新值推给当前活跃的分析器。
    // 分析器里的字段是 @Volatile 的，下帧 analyze() 就会读到新值。
    // 同时重置节流时钟，避免「刚改完间隔要等一整个新间隔才识别」的迟钝感。
    // 依赖里带上 analyzerHolder：相机重建后 holder 变成新实例，必须再推一次。
    LaunchedEffect(frameIntervalMs, analyzerHolder) {
        analyzerHolder?.let { a ->
            a.frameIntervalMs = frameIntervalMs
            a.resetThrottleClock()
        }
    }

    // ROI 精读开关（v1.5）：与帧间隔同一套机制——@Volatile 字段，立即生效、不重建相机。
    // 同样依赖 analyzerHolder：相机因换分辨率档而重建后，holder 是新实例，必须再推一次。
    LaunchedEffect(roiRefineEnabled, analyzerHolder) {
        analyzerHolder?.let { a -> a.roiRefineEnabled = roiRefineEnabled }
    }

    // 第二引擎开关（v1.7）：同上，@Volatile 字段热更新
    LaunchedEffect(zxingFallbackEnabled, analyzerHolder) {
        analyzerHolder?.let { a -> a.zxingFallbackEnabled = zxingFallbackEnabled }
    }

    // 识别吞吐（v1.8.2，v1.8.6 改为回调给状态栏）：每秒问一次分析器「最近一秒跑了几帧、单帧多久」。
    //
    // 只在「慢 → 快」的切换沿上回调，而不是每秒都发：
    //  - 慢的时候文本会跟着刷新（数字要能反映当前情况）；
    //  - 快的时候要连续 [ScanPerf.FAST_STREAK_TO_HIDE] 个窗口才收掉，
    //    否则一两个快帧就让提示闪一下、太干扰。
    //
    // v1.8.6：这条原来画在预览左上角（12dp 角标），正压在取景画面上。
    // 现在整条链路改成回调，由调用方画到画面下方的状态栏里。
    // finally 里清空：离开扫描页时状态栏不该残留「识别 4fps」。
    LaunchedEffect(analyzerHolder) {
        val analyzer = analyzerHolder ?: return@LaunchedEffect
        var fastStreak = 0
        var last: String? = null
        try {
            while (true) {
                delay(PERF_POLL_MS)
                val snapshot = analyzer.perfSnapshot()
                if (ScanPerf.isSlow(snapshot)) {
                    fastStreak = 0
                    val text = ScanPerf.badgeText(snapshot)
                    if (text != last) {
                        last = text
                        currentOnPerfText(text)
                    }
                } else {
                    fastStreak++
                    if (fastStreak >= ScanPerf.FAST_STREAK_TO_HIDE && last != null) {
                        last = null
                        currentOnPerfText(null)
                    }
                }
            }
        } finally {
            if (last != null) currentOnPerfText(null)
        }
    }

    // 闪光灯跟随状态
    LaunchedEffect(torchOn, camera) {
        camera?.cameraControl?.enableTorch(torchOn)
    }

    // 缩放跟随状态
    LaunchedEffect(zoomRatio, camera, maxZoom) {
        camera?.cameraControl?.setZoomRatio(zoomRatio.coerceIn(1f, maxZoom))
    }

    // 识别成功：预览边框闪一下
    var flashOn by remember { mutableStateOf(false) }
    LaunchedEffect(flashTick) {
        if (flashTick == 0) return@LaunchedEffect
        flashOn = true
        delay(260)
        flashOn = false
    }

    /**
     * 点击画面对焦（v1.7）。
     *
     * 用的是 PreviewView 自己的 `meteringPointFactory`：它会把**预览视图坐标**
     * 换算成传感器坐标（考虑旋转、裁剪、镜像）。用 SurfaceOrientedMeteringPointFactory
     * 手算的话，横竖屏切换时对焦点会偏——那是这类代码最常见的错。
     *
     * 自动取消 3 秒：之后相机会自己回到连续对焦，
     * 用户不会「因为误点了一下就永远锁在某个距离上」。
     */
    val focusAt: (Offset) -> Unit = { offset ->
        val bound = camera
        if (bound != null) {
            runCatching {
                val factory = previewView.meteringPointFactory
                val point = factory.createPoint(offset.x, offset.y)
                bound.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                )
            }
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
    ) {
        // 取景画面：这一块里**除了取景框不允许出现任何叠层**（v1.8.6）。
        // 用户判断「有没有对准、清不清楚」只能看这块像素，任何胶囊/角标都在添乱。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(zoomRatio, maxZoom, camera) {
                    detectTapGestures(
                        // 单击对焦。双击缩放仍然保留：两个手势不冲突（Compose 会先等双击超时）
                        onTap = { offset -> focusAt(offset) },
                        onDoubleTap = { onDoubleTapZoom() }
                    )
                }
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )

            // 取景框（四个角标 + 中心对准点）
            //
            // 注意：取景框必须**严格居中**。它的中心点画的就是图像中心点，
            // 而 ML Kit 要求 Data Matrix 压住图像中心才会识别——把取景框挪偏、
            // 或者在同一个 Column 里塞别的东西把它顶上去，准星就不再可信了。
            // 所以画面是 Box(weight) 独立一块，下面的状态栏不参与它的布局。
            ScanReticle(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(width = 300.dp, height = 170.dp),
                strokeColor = if (flashOn) HintSuccess else Color(0xCCFFFFFF)
            )
        }

        // 状态栏：所有提示的唯一出口，由调用方决定显示哪条。
        // 高度固定，内容切换时画面不会跟着跳。
        statusBar()
    }
}

/** 四角取景框 + 中心对准点。纯 Canvas 绘制，无额外依赖。 */
@Composable
private fun ScanReticle(
    modifier: Modifier = Modifier,
    strokeColor: Color
) {
    Canvas(modifier = modifier) {
        val len = size.minDimension * 0.18f
        val stroke = 3.dp.toPx()
        val w = size.width
        val h = size.height
        // 左上
        drawLine(strokeColor, Offset(0f, 0f), Offset(len, 0f), stroke, StrokeCap.Round)
        drawLine(strokeColor, Offset(0f, 0f), Offset(0f, len), stroke, StrokeCap.Round)
        // 右上
        drawLine(strokeColor, Offset(w, 0f), Offset(w - len, 0f), stroke, StrokeCap.Round)
        drawLine(strokeColor, Offset(w, 0f), Offset(w, len), stroke, StrokeCap.Round)
        // 左下
        drawLine(strokeColor, Offset(0f, h), Offset(len, h), stroke, StrokeCap.Round)
        drawLine(strokeColor, Offset(0f, h), Offset(0f, h - len), stroke, StrokeCap.Round)
        // 右下
        drawLine(strokeColor, Offset(w, h), Offset(w - len, h), stroke, StrokeCap.Round)
        drawLine(strokeColor, Offset(w, h), Offset(w, h - len), stroke, StrokeCap.Round)

        // 中心对准点（v1.8.2）：空心小圆 + 中心点。
        // 它对应的是 ML Kit 的硬约束「Data Matrix 必须压住图像中心点」——
        // 圆画得小（直径 18dp）是为了不盖住码本身，只当准星用。
        val cx = w / 2f
        val cy = h / 2f
        val radius = 9.dp.toPx()
        drawCircle(
            color = strokeColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 1.5.dp.toPx())
        )
        drawCircle(color = strokeColor, radius = 1.5.dp.toPx(), center = Offset(cx, cy))
    }
}

/**
 * 诊断角标的轮询间隔：1 秒。
 *
 * 与 `FrameCostMeter` 的统计窗口（1000ms）对齐——问得太快会读到同一个窗口的重复数据，
 * 问得太慢则跟不上「这一秒卡了」的即时感。
 */
private const val PERF_POLL_MS = 1_000L
