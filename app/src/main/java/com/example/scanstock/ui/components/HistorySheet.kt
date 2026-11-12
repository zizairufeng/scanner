package com.example.scanstock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scanstock.data.ScanHistoryStore
import com.example.scanstock.domain.HistoryQuery
import com.example.scanstock.domain.HistoryRange
import com.example.scanstock.domain.RecordKind
import com.example.scanstock.domain.RecordSource
import com.example.scanstock.domain.ScanRecord
import com.example.scanstock.export.Exporter
import com.example.scanstock.ui.HistoryViewModel
import java.util.Locale

/**
 * 扫描历史面板（v1.8.9）：搜索本机记录过的所有扫码事件。
 *
 * ## 为什么是弹层而不是新页面
 *
 * 与本 App 其他所有二级界面同一个理由（设置、相册、导出全走浮层）：
 * 主屏的相机预览一旦销毁重建，用户就要重新对准目标，动作被打断的代价远大于
 * 多一个页面。弹层盖在相机上，底色不透明，相机仍在下面运行——关掉即可继续扫。
 *
 * ## 高度为什么钉死
 *
 * 与 [SettingsSheet] 同一套教训（见那里的 KDoc）：`wrapContent` 会让面板高度
 * 随搜索结果条数跳动，键盘一弹就顶出屏幕。这里钉成屏幕高度的 85%，
 * 结果列表在剩余空间内滚动，输入时再加键盘高度。
 *
 * @param onCopyValue 点一行 → 复制该码值（复用主屏的剪贴板通路与提示）
 * @param onExport    导出（搜索结果），走主 ViewModel 的分享通路
 * @param onMessage   普通提示，回主屏的状态栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    onCopyValue: (String) -> Unit,
    onExport: (List<ScanRecord>, Exporter.Kind) -> Unit,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: HistoryViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirmClear by remember { mutableStateOf(false) }

    // 每次打开都重新搜一次：面板关着的这段时间里可能又扫了新东西
    LaunchedEffect(Unit) { viewModel.refresh() }

    LaunchedEffect(state.clearTick) {
        if (state.clearTick == 0) return@LaunchedEffect
        onMessage(if (state.clearOk) "扫描历史已清空" else "清空未完成，请稍后重试")
    }

    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    val screenLimit = LocalConfiguration.current.screenHeightDp * 0.85f

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(screenLimit.dp + imeBottom)
                .padding(horizontal = 16.dp)
        ) {
            // ── 标题 + 总量 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "扫描历史",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = summaryText(state.stats),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }

            // ── 搜索框 ──
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                singleLine = true,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline
                    )
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "清除搜索词")
                        }
                    }
                },
                label = { Text("搜索码值（可输片段，空格分隔多个词）") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            // ── 时间范围 + 只看本批 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                HistoryRange.entries.forEach { range ->
                    FilterChip(
                        selected = range == state.range,
                        onClick = { viewModel.setRange(range) },
                        label = { Text(range.label) }
                    )
                }
                FilterChip(
                    selected = state.sessionOnly,
                    onClick = { viewModel.toggleSessionOnly() },
                    label = { Text("本次运行") }
                )
            }

            if (state.loading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }

            Text(
                text = resultText(state.records.size, state.truncated, state.scannedDays),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
            )

            // ── 结果列表 ──
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (state.records.isEmpty()) {
                    EmptyHistory(
                        hasQuery = state.query.isNotBlank() || state.sessionOnly,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items = state.records) { record ->
                            HistoryRow(
                                record = record,
                                onClick = { onCopyValue(record.value) }
                            )
                        }
                    }
                }
            }

            // ── 底部操作 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HistoryExportButton(
                    enabled = state.records.isNotEmpty(),
                    onExport = { kind -> onExport(state.records, kind) },
                    modifier = Modifier.weight(1.4f)
                )
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !(state.stats?.isEmpty ?: true),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("清空")
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部历史？") },
            text = {
                Text(
                    "会删掉本机记录的全部扫码历史（${state.stats?.records ?: 0} 条），" +
                        "清空后无法恢复。当前的结果列表不受影响。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearAll()
                }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            }
        )
    }
}

/** 导出历史的下拉按钮，与主屏的导出按钮同一套交互 */
@Composable
private fun HistoryExportButton(
    enabled: Boolean,
    onExport: (Exporter.Kind) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Button(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        ) { Text("导出这些") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Exporter.Kind.entries.forEach { kind ->
                DropdownMenuItem(
                    text = { Text(kind.label) },
                    onClick = {
                        expanded = false
                        onExport(kind)
                    }
                )
            }
        }
    }
}

/**
 * 一行历史。
 *
 * 时间在最上面、码值最大：搜索结果是被**扫读**的（用户在心里比对一串数字），
 * 码值必须是最容易瞟到的那一行；类型和码制是次要信息，压成一行小字。
 */
@Composable
private fun HistoryRow(record: ScanRecord, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = HistoryQuery.formatTimestamp(record.timeMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
            Text(
                text = "· ${record.kind.label}",
                style = MaterialTheme.typography.labelSmall,
                color = kindColor(record.kind),
                modifier = Modifier.padding(start = 4.dp)
            )
            if (record.source != RecordSource.CAMERA) {
                Text(
                    text = "· ${record.source.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Box(modifier = Modifier.weight(1f))
            if (record.formatName.isNotEmpty()) {
                Text(
                    text = record.formatName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
            }
        }
        Text(
            text = record.value,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

@Composable
private fun EmptyHistory(hasQuery: Boolean, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = if (hasQuery) {
                "没有符合条件的记录\n换一段码值片段，或把时间范围放宽到「全部」"
            } else {
                "还没有历史记录\n扫过的每一个码都会实时存到本机，清空列表也不会删掉"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun kindColor(kind: RecordKind): Color = when (kind) {
    RecordKind.NEW -> MaterialTheme.colorScheme.primary
    RecordKind.INC -> MaterialTheme.colorScheme.tertiary
    RecordKind.DUP -> MaterialTheme.colorScheme.outline
}

/** 顶部总量文案：多少天 / 多少条 / 占多大 */
private fun summaryText(stats: ScanHistoryStore.Stats?): String {
    if (stats == null) return "正在统计…"
    if (stats.isEmpty) return "还没有记录"
    return "${stats.days} 天 · ${stats.records} 条 · ${sizeText(stats.bytes)}"
}

/** 结果概要：命中多少、是否被截断、本次读了多少天 */
private fun resultText(count: Int, truncated: Boolean, scannedDays: Int): String {
    if (count == 0) return "没有命中"
    val base = "命中 $count 条"
    val tail = buildString {
        append(" · 读了 $scannedDays 天")
        if (truncated) append("（只显示最新的一批，缩小范围或加关键词能看全）")
    }
    return base + tail
}

private fun sizeText(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
}
