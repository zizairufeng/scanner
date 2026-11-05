package com.example.scanstock.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Reader
import com.google.zxing.Result
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.datamatrix.DataMatrixReader
import java.util.Arrays

/**
 * 第二识别引擎（ZXing core，纯 Java，Apache-2.0）。
 *
 * ## 为什么在 ML Kit 之外再加一套
 *
 * 两个引擎的**失败模式不一样**：ML Kit 是 CNN 检测 + 解码，对「画面里码占得太小」
 * 敏感，而且 Data Matrix 必须压住图像中心点；ZXing 是传统图像处理
 * （定位图案搜索 + 自适应二值化 + Reed-Solomon），只要**像素够、对比度够**
 * 就能解，不要求码在画面中心。
 *
 * 所以它们不是替代关系，而是互补：ML Kit 全帧快扫 → 失败后 ROI 精读 →
 * 精读也失败时，把**同一张已经裁好、已经放大的小块**再喂给 ZXing。
 * 像素数据只准备一次，多花的只是几十毫秒的 CPU。
 *
 * ## v1.8：从「一条管线」改成「一组组合」
 *
 * v1.7 只有一条管线（`HybridBinarizer` + 原极性 + `ALSO_INVERTED` 提示）。
 * 拿一颗海光 CPU 顶盖上的激光蚀刻 Data Matrix 做实验，结果很干脆：
 *
 * | 组合 | 结果 |
 * |------|------|
 * | 原图直接解（Hybrid / Global / ALSO_INVERTED 任一） | ✗ |
 * | 反色 + `HybridBinarizer` | ✗ |
 * | **反色 + `GlobalHistogramBinarizer`** | **✓ 稳定命中** |
 * | 反色 + 对比度拉伸 | 反而更差（放大后失败） |
 *
 * 三个结论直接决定了本类的写法：
 *
 * 1. **必须手动反色**。`ALSO_INVERTED` 这个提示项在这类码上一次都没命中过，
 *    不能指望它；手动 `255 - v` 一次遍历、代价可忽略。
 * 2. **`GlobalHistogramBinarizer` 才是这类码的正解**，`HybridBinarizer` 反而解不出。
 *    原因：蚀刻码的明暗差只有几十级，而 Hybrid 按 8×8 块算局部阈值，
 *    块内方差不够时会被判成纯色；全局直方图好歹能切在双峰之间。
 *    两个都要试——印刷码反过来更吃 Hybrid。
 * 3. **不要盲目做对比度拉伸**。蚀刻码的边缘是「凹坑阴影」的软过渡，
 *    2%/98% 分位拉伸会把这点过渡一起压平。
 *
 * ## 为什么不做 PURE_BARCODE
 *
 * 实测 `PURE_BARCODE`（把整图当成一个理想码位图）在带背景的裁块上从不命中，
 * 只在已经完美裁切且带静区时才有意义——那种情况普通路径本来就能过。
 *
 * ## 线程/依赖边界
 *
 * 本类**不 import 任何 Android 类型**（输入是 int[] 像素），
 * 因此可以在 JVM 单测里「编码一张码 → 反色 → 解码回来」端到端验证，不需要真机。
 */
object ZxingDecoder {

    /**
     * 只开本 App 需要的 7 种码制。
     *
     * 与 ML Kit 侧的口径保持一致：多开格式会抬高误识率
     * （尤其 Code-39 对背景文字非常敏感），也会让 TRY_HARDER 更慢。
     */
    private val FORMATS: List<BarcodeFormat> = Arrays.asList(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.CODE_128,
        BarcodeFormat.CODE_39,
        BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8,
        BarcodeFormat.UPC_A
    )

    /** DPM 路径只需要数据矩阵——蚀刻码在工业上基本只用这一种码制 */
    private val DATA_MATRIX_ONLY: List<BarcodeFormat> = Arrays.asList(BarcodeFormat.DATA_MATRIX)

    /**
     * TRY_HARDER 是这条路的关键：它让 ZXing 在常规定位失败后继续尝试
     * 更费时的路径（多方向、多阈值）。对本项目「一次只解一小块、晚几十毫秒无所谓」
     * 的场景，代价可接受，收益是能救回不少密集小码。
     */
    private fun hints(formats: List<BarcodeFormat>): Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to formats
    )

    /** 不透明白色 */
    private const val WHITE = -0x1

    /** 解码成功的结果。刻意不带 ML Kit 类型，保持本类可纯 JVM 测试 */
    data class Decoded(val value: String, val formatName: String)

    /**
     * 补白边（静区）的最小/最大像素数，以及它相对于图像短边的比例。
     *
     * ## 这个参数是被实测逼出来的，不是拍脑袋
     *
     * 用 ZXing 自己的 `DataMatrixWriter` 编一张码再喂回自己的 reader，
     * **不加白边时是 `NotFoundException`，加 4px 白边立刻就解出来了**（见单测）。
     * 原因：DataMatrix 的检测依赖四周的**静区**（quiet zone），
     * 而 writer 只留了 1 个模块的边距——刚好不够。
     *
     * 我们的裁块同样有这个问题：ROI 外扩 25% 通常够，但中心区补扫裁的是固定比例，
     * 码贴着裁块边缘时静区就没了。补一圈白边成本几乎为零，收益却是「能解 / 不能解」。
     */
    const val QUIET_ZONE_RATIO = 0.06f
    const val MIN_QUIET_ZONE_PX = 6
    const val MAX_QUIET_ZONE_PX = 48

    /**
     * DPM 路径的静区比例。
     *
     * 实测反色 + 全局直方图对静区宽度不挑（2%~16% 全都能解），
     * 这里取 10% 让「码贴边」的情况也留足余量。
     */
    const val DPM_QUIET_ZONE_RATIO = 0.10f

    /**
     * 通用补扫：**四种组合全试一遍**，命中即返回。
     *
     * 顺序按「实测命中率 × 代价」排：
     * 原极性 + Hybrid（印刷码最常见，最便宜的路径）→ 反色 + Hybrid →
     * 原极性 + Global → 反色 + Global。
     *
     * @return 解出的第一个码；解不出返回 `null`（**不抛异常**——解码失败是常态）
     */
    fun decode(pixels: IntArray, width: Int, height: Int): Decoded? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null

        val pad = (minOf(width, height) * QUIET_ZONE_RATIO).toInt()
            .coerceIn(MIN_QUIET_ZONE_PX, MAX_QUIET_ZONE_PX)
        // 顺序很重要：先反色、再补白边。
        //   反过来（先补白再反色）白边会被反成黑框，包在码外面的那圈黑框
        //   会干扰定位图案搜索——实测同一条码就这样从「能解」变成「解不出」。
        val padded = padWithWhite(pixels, width, height, pad)
        val inverted = padWithWhite(invert(pixels), width, height, pad)

        return tryAll(padded, inverted, FORMATS, multiFormat = true)
    }

    /**
     * DPM（金属蚀刻 / 低对比度反色码）专用：只用 DataMatrix，且**反色优先**。
     *
     * 与 [decode] 的差别，是实测出来的那三条结论：
     *  1. 反色 + `GlobalHistogramBinarizer` 排第一（这条对海光 CPU 那颗码是唯一命中解）；
     *  2. 不做对比度拉伸；
     *  3. 静区给足 10%。
     *
     * 只跑 DataMatrix 一个码制，单次成本比 [decode] 低一个量级——
     * 因为阶梯要连跑九级，每级省几十毫秒，整体手感差很多。
     */
    fun decodeDpm(pixels: IntArray, width: Int, height: Int): Decoded? {
        if (width <= 0 || height <= 0) return null
        if (pixels.size < width * height) return null

        val pad = (minOf(width, height) * DPM_QUIET_ZONE_RATIO).toInt()
            .coerceIn(MIN_QUIET_ZONE_PX, MAX_QUIET_ZONE_PX)
        // 先反色、再补白边（理由见 decode 里的同款注释）
        val inverted = padWithWhite(invert(pixels), width, height, pad)

        // ① 反色 + 全局直方图：实测唯一命中过蚀刻码的组合，必须排第一
        decodeOnce(inverted.pixels, inverted.width, inverted.height,
            BinarizerKind.GLOBAL, DATA_MATRIX_ONLY)?.let { return it }
        // ② 反色 + 局部（Hybrid）：反色是对的，但这块其实是印刷码的情况
        decodeOnce(inverted.pixels, inverted.width, inverted.height,
            BinarizerKind.HYBRID, DATA_MATRIX_ONLY)?.let { return it }

        // ③④ 原极性：阶梯裁出来的块不一定都是蚀刻码，
        //    同一片区域也可能贴着一张普通印刷的 Data Matrix，两种都要兜住
        val padded = padWithWhite(pixels, width, height, pad)
        decodeOnce(padded.pixels, padded.width, padded.height,
            BinarizerKind.HYBRID, DATA_MATRIX_ONLY)?.let { return it }
        return decodeOnce(
            padded.pixels, padded.width, padded.height, BinarizerKind.GLOBAL, DATA_MATRIX_ONLY
        )
    }

    private enum class BinarizerKind { HYBRID, GLOBAL }

    /**
     * 通用路径的组合枚举。
     *
     * 四个组合按「原极性 → 反色」交错排：先廉价地试常见情况，
     * 再看反色，最后才用两轮里更慢的组合。
     */
    private fun tryAll(
        padded: Padded,
        inverted: Padded,
        formats: List<BarcodeFormat>,
        multiFormat: Boolean
    ): Decoded? {
        decodeOnce(padded.pixels, padded.width, padded.height,
            BinarizerKind.HYBRID, formats, multiFormat)?.let { return it }
        decodeOnce(inverted.pixels, inverted.width, inverted.height,
            BinarizerKind.GLOBAL, formats, multiFormat)?.let { return it }
        decodeOnce(padded.pixels, padded.width, padded.height,
            BinarizerKind.GLOBAL, formats, multiFormat)?.let { return it }
        return decodeOnce(inverted.pixels, inverted.width, inverted.height,
            BinarizerKind.HYBRID, formats, multiFormat)
    }

    /**
     * 单次尝试。
     *
     * `multiFormat = false` 时直接用 [DataMatrixReader]：它比 [MultiFormatReader]
     * 少一层「逐个 Reader 试」的外壳，密集小码场景下能省掉一大截无效工作。
     */
    private fun decodeOnce(
        pixels: IntArray,
        width: Int,
        height: Int,
        binarizer: BinarizerKind,
        formats: List<BarcodeFormat>,
        multiFormat: Boolean = false
    ): Decoded? = runCatching {
        val source = RGBLuminanceSource(width, height, pixels)
        val bin: Binarizer = when (binarizer) {
            BinarizerKind.HYBRID -> HybridBinarizer(source)
            BinarizerKind.GLOBAL -> GlobalHistogramBinarizer(source)
        }
        val hintMap = hints(formats)
        val reader: Reader =
            if (multiFormat) MultiFormatReader().apply { setHints(hintMap) } else DataMatrixReader()

        val result: Result? = try {
            reader.decode(BinaryBitmap(bin), hintMap)
        } catch (e: Exception) {
            // NotFoundException / ChecksumException / FormatException 都是「这一帧没解出来」，
            // 属于正常路径，不是错误
            null
        } finally {
            // 复位内部状态：MultiFormatReader 会缓存上一轮的读者实例
            (reader as? MultiFormatReader)?.reset()
        }

        val text = result?.text.orEmpty()
        if (text.isEmpty()) null else Decoded(text, nameOf(result!!.barcodeFormat))
    }.getOrNull()

    private class Padded(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * 四周补白边。用绝对索引逐行拷，避免为每一行再建数组——
     * 这块在每帧的兜底路径上会被调用，分配越少越好。
     */
    private fun padWithWhite(pixels: IntArray, width: Int, height: Int, pad: Int): Padded {
        if (pad <= 0) return Padded(pixels, width, height)
        val w = width + pad * 2
        val h = height + pad * 2
        val out = IntArray(w * h)
        // IntArray 默认 0（黑色），必须显式填白，否则静区变成黑色反而更糟
        Arrays.fill(out, WHITE)
        for (y in 0 until height) {
            System.arraycopy(pixels, y * width, out, (y + pad) * w + pad, width)
        }
        return Padded(out, w, h)
    }

    /** 像素级反色，保留 alpha。一次遍历，小图上代价可忽略 */
    private fun invert(pixels: IntArray): IntArray {
        val out = IntArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = 255 - ((p shr 16) and 0xFF)
            val g = 255 - ((p shr 8) and 0xFF)
            val b = 255 - (p and 0xFF)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    /** ZXing 码制 → 展示名。与 ML Kit 侧 [BarcodeFormats.nameOf] 的口径保持一致 */
    fun nameOf(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.QR_CODE -> "QR Code"
        BarcodeFormat.DATA_MATRIX -> "Data Matrix"
        BarcodeFormat.CODE_128 -> "Code-128"
        BarcodeFormat.CODE_39 -> "Code-39"
        BarcodeFormat.EAN_13 -> "EAN-13"
        BarcodeFormat.EAN_8 -> "EAN-8"
        BarcodeFormat.UPC_A -> "UPC-A"
        BarcodeFormat.UPC_E -> "UPC-E"
        BarcodeFormat.PDF_417 -> "PDF417"
        BarcodeFormat.AZTEC -> "Aztec"
        else -> "未知($format)"
    }
}
