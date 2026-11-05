package com.example.scanstock.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import com.example.scanstock.domain.DetectedCode
import com.example.scanstock.util.ImageSampling
import com.example.scanstock.util.awaitResult
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「从相册图片识别」。
 *
 * 两个关键点：
 *
 * - **降采样**（内存）：不用 `InputImage.fromFilePath()` 直接吃整张原图——1200 万像素的
 *   照片解码出来约 46MB，是本项目最大的单点内存开销。改成先用
 *   `inJustDecodeBounds` 读尺寸、按 [ImageSampling] 算出采样率再解码，
 *   最长边压到 1600 以内，内存降到约 1/6，识别率不受影响。
 *
 * - **方向**（正确性）：`fromFilePath()` 会自动读 EXIF 摆正，降采样后这个便利没了，
 *   所以这里自己读 EXIF 的 orientation，换算成角度交给 `InputImage.fromBitmap`
 *   的 rotationDegrees —— **不改 Bitmap 本身**，避免再生成一份旋转后的大图副本。
 *
 * 整个解码放 Dispatchers.IO，避免大图解码卡住主线程。
 */
object ImageDecoder {

    /**
     * @return 解析出的候选码（含 potentialOnly 的，调用方自行过滤）
     * @throws IOException 图片无法读取时抛出，由调用方转成用户可见提示
     */
    suspend fun decode(
        context: Context,
        uri: Uri,
        scanner: BarcodeScanner
    ): List<DetectedCode> = withContext(Dispatchers.IO) {
        val bitmap = decodeSampled(context, uri)
            ?: throw IOException("图片无法读取")

        try {
            val rotation = exifRotationDegrees(context, uri)
            val inputImage = InputImage.fromBitmap(bitmap, rotation)
            val barcodes = scanner.process(inputImage).awaitResult()
            barcodes.map { barcode ->
                val raw = barcode.rawValue
                DetectedCode(
                    formatId = barcode.format,
                    formatName = BarcodeFormats.nameOf(barcode.format),
                    value = raw ?: "",
                    potentialOnly = raw.isNullOrEmpty()
                )
            }
        } finally {
            // 结果已经拿到，Bitmap 立即归还（不回收的话这张图会一直占着内存直到 GC）
            bitmap.recycle()
        }
    }

    /**
     * 先读边界、再按采样率解码。开两次流是刻意的选择：
     * 只有这样才能在不把整图读进内存的前提下拿到原图尺寸。
     */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // 边界都没读出来：可能是格式不支持，直接尝试原样解码一次
            return runCatching {
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.inSampleSize(bounds.outWidth, bounds.outHeight)
            // RGB_565 只有 5 位精度，会让浅色底的低对比度条码直接解不出来，这里必须用 8888
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    /**
     * 读 EXIF 方向并换算成角度。用框架自带的 `android.media.ExifInterface`
     * （API 24 起可用），不额外引入 androidx.exifinterface 依赖。
     * 读取失败一律按 0 度处理——宁可偶尔方向不对，也不能让识别直接失败。
     */
    private fun exifRotationDegrees(context: Context, uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (
                exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } ?: 0
    }.getOrDefault(0)
}
