package com.example.scanstock

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.scanstock.logging.AppLog
import com.example.scanstock.ui.ScanScreen
import com.example.scanstock.ui.theme.ScanStockTheme

/**
 * 唯一 Activity。
 *
 * 屏幕方向：**锁定竖屏**（`AndroidManifest` 里 `screenOrientation="portrait"`）。
 *
 * v1.5 及以前是 `fullSensor`（跟随重力感应），当时的理由是「货架高位/低位横持更顺手」。
 * v1.6 实测后改掉了：盘点时手机单手握着对着货架，手腕自然转动就会触发旋转，
 * 而每次旋转都会重建 Activity → CameraX 解绑重绑 → 预览黑一下、这一两秒扫不到码，
 * 旋转瞬间还会偶发原生层 Surface 已释放的异常（闪退来源之一）。
 * 扫码是**取景框居中**的动作，横持时画面不跟着转并不影响对准。
 *
 * state 保存：结果列表由 ScanViewModel 持有（ViewModel 在配置变更时存活），
 * 所以即使将来又放开旋转，也不会丢数据、不会重新初始化去重器。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 起系统强制 edge-to-edge，这里显式开启并让 Compose 自行处理 insets
        enableEdgeToEdge()
        AppLog.i(TAG, "MainActivity onCreate（方向=${orientationName()}）")
        setContent {
            ScanStockTheme {
                ScanScreen()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.i(TAG, "onStart")
    }

    override fun onStop() {
        AppLog.i(TAG, "onStop（切后台/被遮挡，会话即将落盘）")
        super.onStop()
    }

    override fun onDestroy() {
        AppLog.i(TAG, "onDestroy（isFinishing=$isFinishing）")
        super.onDestroy()
    }

    /**
     * 记一句方向。锁定竖屏之后这里恒为 portrait，留着是为了将来放开旋转时，
     * 能直接从日志里看出「崩溃那次是不是恰好转了屏」——这是排查闪退时
     * 最容易被忽略、又最容易解释「偶发」的一个变量。
     */
    private fun orientationName(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            "landscape"
        } else {
            "portrait"
        }

    private companion object {
        const val TAG = "MainActivity"
    }
}
