package com.example.scanstock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 扫描页的**唯一一套提示规范**（v1.8.6，取代 v1.8.4 的浮层方案）。
 *
 * ## 为什么又改了一次
 *
 * v1.8.4 把 8 处浮层收成了 3 个落点，但**三个落点全都画在相机画面上**：
 * 顶部一条、底部一条、四角角标。用户的反馈是：
 *
 *   > 不要在镜头显示的地方放浮窗呀，都看不到是否清楚了。
 *
 * 这句话点到了根上——扫码的核心动作是「对准」，而判断有没有对准**只能看画面**。
 * 半透明胶囊叠在取景框里，恰好盖住用户唯一需要判断的那块像素。
 * 提示再统一、再好看，只要它压在画面上，就是在妨碍主任务。
 *
 * ## 现在的规则：画面里什么都不放
 *
 * 相机预览区域**只允许出现取景框本体**（四角细线 + 中心准星），
 * 那是瞄准器，不是提示。其余一切都进画面正下方的 [ScanStatusBar]：
 *
 * ```
 * ┌──────────────────────────┐
 * │                          │
 * │      相机画面（干净）      │   ← 只有取景框细线
 * │                          │
 * ├──────────────────────────┤   ← 状态栏上沿，固定 52dp
 * │ ● 太近会失焦 · 退到 15cm   [撤销] │
 * └──────────────────────────┘
 * ```
 *
 * ## 三条硬约束
 *
 *  1. **高度固定**（[ScanHintSpec.STATUS_BAR_HEIGHT_DP]）：内容切换时高度不变，
 *     相机画面不会被推得跳一下。空着的时候显示一句常驻引导，不浪费这块空间。
 *  2. **同一时刻只有一条**：优先级见 `ScanScreen` 里的 `statusOf`。
 *  3. **只有圆点带颜色**：文字统一白色。v1.8.4 的彩色胶囊在深色状态栏上反而
 *     比白字难读，而且六种颜色的老问题会顺着「每条一个底色」回来。
 */
object ScanHintSpec {

    /** 纯信息停留时长：2.5 秒。扫一眼就够，读不完的细节列表里都有 */
    const val INFO_MS = 2_500L

    /** 需要用户做动作时的停留时长：3.5 秒。调整距离、换个角度都需要反应时间 */
    const val ACTION_MS = 3_500L

    /**
     * 撤销窗口：4 秒。
     *
     * 它是全页唯一「错过就找不回来」的操作，所以比普通通知长；
     * 但也不能再长——按钮一直挂在那儿，用户会以为列表还没删干净。
     *
     * 这个值同时是 ViewModel 里删除快照的有效期：按钮还在 = 撤销还有效，
     * 两者必须严格同步（过期才调 `dismissUndo()`）。
     */
    const val UNDO_MS = 4_000L

    /**
     * 状态栏高度：52dp。
     *
     * 这个数字要同时装下：一行 14sp 文字（约 20dp）+ 上下各 16dp 呼吸，
     * 以及一个最小可点高度 36dp 的「撤销」按钮。再矮按钮就不好按了。
     */
    const val STATUS_BAR_HEIGHT_DP = 52

    /** 语义圆点直径 */
    const val DOT_SIZE_DP = 7

    /**
     * 进场位置引导（「方码压在中心」）的停留时间：8 秒。
     *
     * 比其它提示长得多，因为它要在用户还没开始扫的时候被读到；
     * 但**不能常驻**——v1.8.4 之前它一直挂在取景框下沿，那正是最该看清的位置。
     */
    const val POSITION_GUIDE_MS = 8_000L
}

/** 提示语义。**只允许这三种**，见 [ScanHintSpec] 的说明 */
enum class HintTone {
    /** 成功 / 已计入：绿 */
    SUCCESS,

    /** 需要你动一下：调距离、换角度、点对焦：琥珀 */
    ACTION,

    /** 中性信息：位置引导、缩放倍率：灰 */
    NEUTRAL
}

/**
 * 成功色。取景框闪一下用的也是它——
 * 同一个「扫到了」在全页面上只允许有一个绿。
 *
 * 比 v1.8.4 的 `1FA96F` 更亮一档：那版是画在浅色胶囊上的，这版画在近黑状态栏上，
 * 同一个绿在黑底上会显脏。
 */
val HintSuccess = Color(0xFF3DD68C)

/** 警示色：用户需要动手改点什么 */
val HintAction = Color(0xFFFFB020)

/** 状态栏底色。恒定深色，不跟随明暗主题——它长在相机卡片上，跟着主题变会很怪 */
private val StatusBarBackground = Color(0xFF121719)

/** 状态栏与画面之间的 1dp 分隔，让两块有层次但不抢眼 */
private val StatusBarDivider = Color(0x1FFFFFFF)

private val HintTone.dot: Color
    get() = when (this) {
        HintTone.SUCCESS -> HintSuccess
        HintTone.ACTION -> HintAction
        HintTone.NEUTRAL -> Color(0x66FFFFFF)
    }

/**
 * 画面正下方的状态栏：**扫描页所有提示的唯一落点**。
 *
 * 调用方负责挑出「现在最该说的那一句」，这里只管排版。
 * 别在相机画面上再叠任何东西——那是这一版存在的全部理由。
 *
 * @param text      主文案，单行，超长省略
 * @param tone      决定左侧圆点颜色
 * @param value     右侧补充值（码值等），等宽字体，最多占 40% 宽
 * @param actionLabel 右侧动作按钮文字（如「撤销」）
 * @param onAction  动作回调。为 null 时按钮不显示
 */
@Composable
fun ScanStatusBar(
    text: String,
    tone: HintTone,
    modifier: Modifier = Modifier,
    value: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(StatusBarDivider)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height((ScanHintSpec.STATUS_BAR_HEIGHT_DP - 1).dp)
                .background(StatusBarBackground)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 小圆点替代「警告 / 成功」二字：连续盘点时靠颜色识别比读字快
            Box(
                modifier = Modifier
                    .size(ScanHintSpec.DOT_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(tone.dot)
            )
            // 两行：第一行是「结论 / 该做什么」，第二行是码值。
            // 码值必须留在画面上——用户得能确认「刚才那个码到底是哪个」，
            // 而列表第一行可能已经被后面的扫描挤走了。
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (value != null) {
                    Text(
                        text = value,
                        color = Color.White.copy(alpha = 0.70f),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .heightIn(min = 36.dp)
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
