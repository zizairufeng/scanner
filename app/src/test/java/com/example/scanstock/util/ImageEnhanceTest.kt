package com.example.scanstock.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 裁块对比度拉伸的单测（v1.7）。
 *
 * 它只做一件事：把被压在 90~150 这种窄灰阶里的裁块拉开到 0~255。
 * 用例覆盖三种情况：**该拉**、**不该拉**（本来就没信息）、**不该动**（已经很宽）。
 */
class ImageEnhanceTest {

    private fun gray(value: Int, count: Int): IntArray =
        IntArray(count) { (0xFF shl 24) or (value shl 16) or (value shl 8) or value }

    @Test
    fun `窄灰阶被拉开到接近全量程`() {
        // 一半 90、一半 150：跨度 60，二值化很难找到干净阈值
        val pixels = gray(90, 500) + gray(150, 500)
        val before = ImageEnhance.spanOf(pixels)

        val changed = ImageEnhance.contrastStretchInPlace(pixels)

        assertEquals("有 1000 个像素被处理", 1000, changed)
        assertTrue("拉伸后跨度必须显著变大", ImageEnhance.spanOf(pixels) > before + 40)
        // 暗端落到 0、亮端落到 255
        assertEquals(0, pixels[0] and 0xFF)
        assertEquals(255, pixels[500] and 0xFF)
    }

    @Test
    fun `几乎没有信息时拒绝拉伸`() {
        // 全部 128（±2 的噪点）：跨度过小，拉了只是放大噪点
        val pixels = gray(128, 1000)
        assertEquals(0, ImageEnhance.contrastStretchInPlace(pixels))
        assertEquals(128, pixels[0] and 0xFF)
    }

    @Test
    fun `已经是全量程的图不会被改坏`() {
        val pixels = gray(0, 500) + gray(255, 500)
        ImageEnhance.contrastStretchInPlace(pixels)
        // 极值不变：0 还是 0，255 还是 255
        assertEquals(0, pixels[0] and 0xFF)
        assertEquals(255, pixels[500] and 0xFF)
    }

    @Test
    fun `输出仍是灰度且保留alpha`() {
        val pixels = IntArray(4) { i ->
            // 彩色：偏红 / 偏绿，拉伸后应被压成灰度（R=G=B）
            if (i % 2 == 0) 0xFF204060.toInt() else 0xFFA0C0E0.toInt()
        }
        ImageEnhance.contrastStretchInPlace(pixels)
        pixels.forEach { px ->
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            assertEquals(r, g)
            assertEquals(g, b)
            assertEquals(0xFF, (px ushr 24) and 0xFF)
        }
    }

    @Test
    fun `空数组安全返回`() {
        assertEquals(0, ImageEnhance.contrastStretchInPlace(IntArray(0)))
        assertEquals(0, ImageEnhance.spanOf(IntArray(0)))
    }
}
