package com.example.scanstock.camera

import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode

/**
 * 码制白名单、扫描器工厂、以及「常量 → 展示名」映射。
 *
 * 这是全工程唯一依赖 ML Kit 类型的地方，其余层只拿到纯 Kotlin 数据。
 */
object BarcodeFormats {

    /**
     * 需求指定的 7 种码制，逐个显式列出。
     *
     * 这里刻意**不用** Barcode.FORMAT_ALL_FORMATS：
     * 那个常量会把 PDF417 / Aztec / Codabar / ITF / UPC-E 一并打开，
     * 多出来的码制会抬高误识率（货架背景文字常被误判成 Code-39），
     * 也会增加单帧耗时、拉低连续扫描帧率。盘点场景的真实码制集合就是这 7 种。
     */
    /**
     * @param onZoomSuggested 自动变焦回调（可为 null = 不启用）。
     *                        ML Kit 发现画面里的码都太远、解不出来时，会用它建议的倍率调这个回调；
     *                        回调返回 true 表示 App 已按建议调整了相机，ML Kit 会继续这一轮尝试。
     *                        回调由 ML Kit 在**主线程**调用，可以安全地更新 UI 状态。
     * @param maxZoomRatio    设备支持的最大变焦倍数。绑定相机前拿不到，可传 null ——
     *                        此时库给出的建议值可能超过硬件上限，调用方**必须自己 clamp**。
     */
    fun createScanner(
        onZoomSuggested: ((Float) -> Boolean)? = null,
        maxZoomRatio: Float? = null
    ): BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_DATA_MATRIX
            )
            // 打开「潜在条码」：暗光/残缺时用于提示「再靠近一点」，不计入结果列表
            .enableAllPotentialBarcodes()
            .apply {
                if (onZoomSuggested != null) {
                    val zoomOptions = ZoomSuggestionOptions.Builder(onZoomSuggested).apply {
                        if (maxZoomRatio != null && maxZoomRatio > 1f) {
                            setMaxSupportedZoomRatio(maxZoomRatio)
                        }
                    }.build()
                    setZoomSuggestionOptions(zoomOptions)
                }
            }
            .build()
    )

    /**
     * 码制常量 → 展示名。
     * 白名单之外的也一并映射，避免上游误开格式时显示成「未知(-1)」而无法排查。
     */
    fun nameOf(formatId: Int): String = when (formatId) {
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        Barcode.FORMAT_CODE_128 -> "Code-128"
        Barcode.FORMAT_CODE_39 -> "Code-39"
        Barcode.FORMAT_CODE_93 -> "Code-93"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_AZTEC -> "Aztec"
        else -> "未知($formatId)"
    }
}
