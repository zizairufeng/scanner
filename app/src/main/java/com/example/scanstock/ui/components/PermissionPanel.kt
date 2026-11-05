package com.example.scanstock.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 相机权限降级页。
 *
 * 设计原则：**永远不留白屏**。无论处于哪种权限状态，用户都必须看到
 * 一段能看懂的说明 + 一个明确的下一步按钮。
 *
 * @param permanentlyDenied true 表示用户已拒绝过且系统不再弹窗
 *                          （shouldShowRequestPermissionRationale 返回 false），
 *                          此时请求按钮已经无效，必须引导去系统设置。
 */
@Composable
fun PermissionPanel(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "需要相机权限才能扫码",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (permanentlyDenied) {
                "你之前拒绝了相机权限，系统不会再弹出授权框。\n请点下面的按钮进入系统设置，在「权限 → 相机」里选择“允许”。"
            } else {
                "本 App 只在扫码时使用相机，画面全部在本机离线处理，\n不上传、不保存任何图像。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp, bottom = 20.dp)
        )

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (permanentlyDenied) {
                Button(onClick = onOpenSettings) { Text("去系统设置") }
            } else {
                Button(onClick = onRequest) { Text("授予相机权限") }
                OutlinedButton(onClick = onOpenSettings) { Text("去系统设置") }
            }
        }

        Text(
            text = "你也可以先用相册导入图片识别（无需相机权限）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 18.dp)
        )
    }
}
