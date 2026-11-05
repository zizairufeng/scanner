package com.example.scanstock.camera

import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.datamatrix.DataMatrixWriter
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 第二引擎（ZXing）的端到端单测（v1.7）。
 *
 * ## 为什么这个测试必须存在
 *
 * `ZxingDecoder` 是**兜底路径**：它只在 ML Kit 解不出时才被调用。
 * 如果它因为混淆、依赖缺失、API 用法错误而失效，现场表现是「永远 0 命中」——
 * 没有任何报错，也没有任何人会发现。所以这里用「**现场编码一张码 → 再解回来**」
 * 的方式把它钉死，且完全跑在 JVM 上（ZXing core 是纯 Java，不需要真机）。
 */
class ZxingDecoderTest {

    /** BitMatrix → ARGB 像素数组（黑码白底）。宽高可能不相等，必须分别算 */
    private data class Rendered(val pixels: IntArray, val width: Int, val height: Int)

    private fun render(matrix: BitMatrix, scale: Int = 1): Rendered {
        val w = matrix.width * scale
        val h = matrix.height * scale
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val on = matrix[x / scale, y / scale]
                pixels[y * w + x] = if (on) BLACK else WHITE
            }
        }
        return Rendered(pixels, w, h)
    }

    @Test
    fun `二维码_编码后能解回来`() {
        val matrix = QRCodeWriter().encode("SN-JR5UCCB25B007JG", BarcodeFormat.QR_CODE, 200, 200)
        val image = render(matrix)

        val decoded = ZxingDecoder.decode(image.pixels, image.width, image.height)

        assertEquals("SN-JR5UCCB25B007JG", decoded?.value)
        assertEquals("QR Code", decoded?.formatName)
    }

    @Test
    fun `DataMatrix_编码后能解回来`() {
        // 这一条对应用户实际遇到的场景：硬盘标签上那颗很小的 Data Matrix
        val matrix = DataMatrixWriter().encode("JR5UCCB25B007JG", BarcodeFormat.DATA_MATRIX, 160, 160)
        val image = render(matrix)

        val decoded = ZxingDecoder.decode(image.pixels, image.width, image.height)

        assertEquals("JR5UCCB25B007JG", decoded?.value)
        assertEquals("Data Matrix", decoded?.formatName)
    }

    @Test
    fun `反色码也能解`() {
        // 金属壳蚀刻 / 深色标签上的浅色码：靠 DecodeHintType.ALSO_INVERTED
        val matrix = QRCodeWriter().encode("INVERTED-001", BarcodeFormat.QR_CODE, 200, 200)
        val image = render(matrix)
        val inverted = IntArray(image.pixels.size) { i ->
            if (image.pixels[i] == BLACK) WHITE else BLACK
        }

        val decoded = ZxingDecoder.decode(inverted, image.width, image.height)

        assertEquals("INVERTED-001", decoded?.value)
    }

    @Test
    fun `空白图返回null而不是抛异常`() {
        val pixels = IntArray(200 * 200) { WHITE }
        assertNull(ZxingDecoder.decode(pixels, 200, 200))
    }

    // ─────────────────── DPM（金属蚀刻码）通路（v1.8）───────────────────

    /**
     * DPM 通路的核心保证：**浅码深底的 Data Matrix 能解出来**。
     *
     * 这条对应用户实际扫不到的那颗码（海光 CPU 顶盖、激光蚀刻、约 2.5mm）。
     * 真实样本是「浅色模块 + 深色金属底」的反色码，[ZxingDecoder.decodeDpm]
     * 内部会先反色再补静区——这两步的顺序错了就解不出（见实现里的注释）。
     *
     * 模块边长给到 4px：真实样本约 6.5px/模块，缩到这个量级仍应能解，
     * 也就是说对手持距离的容忍度是够的。
     */
    @Test
    fun `DPM_浅码深底的DataMatrix能解出来`() {
        val matrix = DataMatrixWriter().encode(
            "HG62935B0166_C86-4G 7493", BarcodeFormat.DATA_MATRIX, 0, 0
        )
        val image = render(matrix, scale = 4)
        // 反色：变成「浅色模块 + 深色底」的 DPM 形态
        val dpmPolarity = IntArray(image.pixels.size) { i ->
            if (image.pixels[i] == BLACK) WHITE else BLACK
        }

        val decoded = ZxingDecoder.decodeDpm(dpmPolarity, image.width, image.height)

        assertEquals("HG62935B0166_C86-4G 7493", decoded?.value)
        assertEquals("Data Matrix", decoded?.formatName)
    }

    /**
     * DPM 通路也必须能解「正常印刷的深色码」。
     *
     * 现实里阶梯裁出来的块不一定都是蚀刻码——同一片区域也可能有印在标签上的
     * 普通 Data Matrix。这时如果只试反色就永远解不出，所以第三条组合是原极性。
     */
    @Test
    fun `DPM_正常极性的DataMatrix也能解出来`() {
        val matrix = DataMatrixWriter().encode("C86-4G", BarcodeFormat.DATA_MATRIX, 0, 0)
        val image = render(matrix, scale = 4)

        val decoded = ZxingDecoder.decodeDpm(image.pixels, image.width, image.height)

        assertEquals("C86-4G", decoded?.value)
    }

    /** DPM 通路只认 DataMatrix：一维条码不该被它「顺手」解出来（那是通用通路的活） */
    @Test
    fun `DPM_空白图返回null`() {
        assertNull(ZxingDecoder.decodeDpm(IntArray(120 * 120) { WHITE }, 120, 120))
        assertNull(ZxingDecoder.decodeDpm(IntArray(10), 0, 0))
        assertNull(ZxingDecoder.decodeDpm(IntArray(10), 100, 100))
    }

    @Test
    fun `尺寸非法时安全返回`() {
        assertNull(ZxingDecoder.decode(IntArray(10), 0, 0))
        assertNull(ZxingDecoder.decode(IntArray(10), 100, 100))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
