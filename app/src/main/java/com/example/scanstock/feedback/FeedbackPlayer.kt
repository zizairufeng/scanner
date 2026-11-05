package com.example.scanstock.feedback

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.Closeable

/**
 * 识别成功反馈：震动 30ms + 短提示音。
 *
 * 兼容性说明：
 * - API 31+ 用 VibratorManager 取默认马达；24~30 用旧的 VIBRATOR_SERVICE。
 * - API 26+ 用 VibrationEffect 控制时长为 30ms；24/25 只能退回已废弃的 vibrate(ms)。
 * - ToneGenerator 在部分机型（尤其模拟器 / 无扬声器设备）构造会抛 RuntimeException，
 *   所以整体包 runCatching，失败时静默降级，绝不影响扫码主链路。
 * - 用 STREAM_MUSIC 而非 STREAM_NOTIFICATION：盘点环境常静音通知，
 *   但仍希望扫描枪有声音确认（且不干扰系统勿扰模式下的通话）。
 */
class FeedbackPlayer(context: Context) : Closeable {

    companion object {
        private const val VIBRATE_MS = 30L
        private const val TONE_MS = 40
        private const val TONE_VOLUME = 90

        /** 「嗡—嗡」两下：25ms 震、70ms 停、25ms 震。第一位 0 表示立即开始 */
        private val DUPLICATE_PATTERN = longArrayOf(0L, 25L, 70L, 25L)
    }

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            manager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }.getOrNull()

    private var toneGenerator: ToneGenerator? = null

    /** 连续扫码时限制提示音频率，避免「哒哒哒」连成噪音；震动不受限制 */
    @Volatile
    private var lastBeepAt = 0L

    fun play(vibrate: Boolean, beep: Boolean) {
        if (vibrate) vibrateOnce()
        if (beep) beepOnce()
    }

    private fun vibrateOnce() {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(
                    VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(VIBRATE_MS)
            }
        }
    }

    /**
     * 重复码提示（v1.7）：**两下短震、不出声**。
     *
     * 为什么不用提示音：连续盘点时「成功」和「重复」两声调子接近，靠听根本分不清；
     * 而「两下」这个节奏是可辨认的，用户不看屏幕就知道该抬手去扫下一个了。
     *
     * 关掉震动时不做事——重复码提醒本身还有提示，不会因为没震动就漏掉信息。
     */
    fun playDuplicate(vibrate: Boolean) {
        if (!vibrate) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(DUPLICATE_PATTERN, -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(DUPLICATE_PATTERN, -1)
            }
        }
    }

    private fun beepOnce() {        val now = SystemClock.elapsedRealtime()
        if (now - lastBeepAt < 60L) return
        lastBeepAt = now
        runCatching {
            val generator = toneGenerator ?: ToneGenerator(
                AudioManager.STREAM_MUSIC,
                TONE_VOLUME
            ).also { toneGenerator = it }
            generator.startTone(ToneGenerator.TONE_PROP_BEEP, TONE_MS)
        }.onFailure {
            // 设备没有可用音频通道：释放并放弃提示音，不影响识别
            runCatching { toneGenerator?.release() }
            toneGenerator = null
        }
    }

    override fun close() {
        runCatching { toneGenerator?.release() }
        toneGenerator = null
    }
}
