package com.example.scanstock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.scanstock.domain.ScanItem

/**
 * 结果列表。
 *
 * 性能要点：
 * - LazyColumn + items(key = seq)：key 用稳定序号，插入/删除时不会整表重建。
 * - 列表很长（连续盘点轻松上千条）时，key 还能保证滚动位置不乱跳。
 * - 自动滚到底部：只在 items.size 增加时触发，用户手动上滑查看历史时
 *   会因 size 未变而不会被强行拽回底部。
 */
@Composable
fun ResultListPanel(
    items: List<ScanItem>,
    onItemClick: (ScanItem) -> Unit,
    onItemDelete: (ScanItem) -> Unit,
    modifier: Modifier = Modifier,
    autoScroll: Boolean = true
) {
    val listState = rememberLazyListState()

    LaunchedEffect(items.size) {
        if (autoScroll && items.isNotEmpty()) {
            // scrollToItem 而非 animateScrollToItem：高频追加时动画会互相打断、反而卡
            listState.scrollToItem(items.lastIndex)
        }
    }

    if (items.isEmpty()) {
        EmptyHint(modifier)
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 12.dp, end = 12.dp, top = 6.dp, bottom = 96.dp
        ),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(items = items, key = { it.seq }) { item ->
            ResultRow(
                item = item,
                onClick = { onItemClick(item) },
                onDelete = { onItemDelete(item) }
            )
        }
    }
}

@Composable
private fun ResultRow(
    item: ScanItem,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // 点整行 = 复制这一条（高频操作，做得最好按）
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 序号
        Box(
            modifier = Modifier
                .width(34.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = item.seq.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        ) {
            Text(
                text = item.value,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${item.formatName}   ${item.timeText}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }

        // 计数聚合模式下才可能出现 qty > 1：这一行代表多个同样的码。
        // 放在删除按钮左侧而不是塞进副标题，是因为它是这一行最重要的数字——
        // 用户扫完一眼要看的就是「这个几个」，不能藏在灰色小字里。
        if (item.qty > 1) {
            Text(
                text = "×${item.qty}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier.padding(end = 2.dp)
            )
        }

        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = "删除第 ${item.seq} 条",
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "还没有扫描记录\n把条码对准取景框，识别后会自动追加到这里",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
