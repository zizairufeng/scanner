package com.example.scanstock.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ImageSampling 单元测试（纯 JVM，无 Android 依赖）。
 *
 * 测的是「采样率计算」这段纯逻辑：BitmapFactory 只能用 2 的幂作为 inSampleSize，
 * 所以这里必须保证返回值永远是 2 的幂，且缩放后最长边不超过目标值。
 */
class ImageSamplingTest {

    @Test
    fun `小图不缩放`() {
        assertEquals(1, ImageSampling.inSampleSize(800, 1200))
        assertEquals(1, ImageSampling.inSampleSize(1600, 1200))
    }

    @Test
    fun `略超上限的图只缩一档`() {
        // 1920 长边：/2 = 960 已合格，因此只缩到 2 而不是更多
        assertEquals(2, ImageSampling.inSampleSize(1080, 1920))
    }

    @Test
    fun `1200万像素照片降到1600以内`() {
        // 4032x3024（典型 12MP 竖拍）：/2 = 2016 仍超限，/4 = 1008 合格
        val sample = ImageSampling.inSampleSize(4032, 3024)
        assertEquals(4, sample)
        assertEquals(true, 4032 / sample <= ImageSampling.MAX_EDGE)
    }

    @Test
    fun `结果永远是2的幂`() {
        for (w in listOf(800, 2000, 4032, 6000, 12000)) {
            val s = ImageSampling.inSampleSize(w, w / 2)
            assertEquals("采样率必须是 2 的幂，实际 $s", 0, s and (s - 1))
        }
    }

    @Test
    fun `缩放后最长边不超过上限`() {
        val sizes = listOf(1200 to 1600, 3024 to 4032, 4000 to 3000, 8000 to 6000, 20000 to 10,
            40000 to 10)
        for ((w, h) in sizes) {
            val s = ImageSampling.inSampleSize(w, h)
            val longestAfter = maxOf(w, h) / s
            assertEquals(
                "${w}x${h} 采样 $s 后最长边 $longestAfter 仍超限",
                true,
                longestAfter <= ImageSampling.MAX_EDGE || s == 32
            )
        }
    }

    @Test
    fun `非法尺寸按不缩放处理`() {
        assertEquals(1, ImageSampling.inSampleSize(0, 0))
        assertEquals(1, ImageSampling.inSampleSize(-1, 100))
        assertEquals(1, ImageSampling.inSampleSize(100, -1))
    }

    @Test
    fun `极端长条图不会死循环且命中保护上限`() {
        // 20000px 长边：/16 = 1250 已合格，因此停在 16
        assertEquals(16, ImageSampling.inSampleSize(20000, 100))
        // 40000px：/16 = 2500 仍超限 → 继续到 /32 = 1250，命中 32 的保护上限后停止
        assertEquals(32, ImageSampling.inSampleSize(40000, 100))
    }
}
