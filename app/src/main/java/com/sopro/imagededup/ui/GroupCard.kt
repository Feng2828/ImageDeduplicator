package com.sopro.imagededup.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.sopro.imagededup.model.DuplicateGroup
import com.sopro.imagededup.model.ImageRecord

@Composable
fun GroupCard(
    group: DuplicateGroup,
    records: List<ImageRecord>,
    onKeep: (Long) -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 ${group.id + 1} 组 · ${group.members.size} 张相似图片",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (group.toDelete.isNotEmpty()) {
                    Text(
                        text = "已勾选 ${group.toDelete.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items = records, key = { it.id }) { record ->
                    ImageTile(
                        record = record,
                        isKept = record.id == group.keepId,
                        isChecked = group.toDelete.contains(record.id),
                        onKeep = { onKeep(record.id) },
                        onToggle = { onToggle(record.id) }
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onSelectAll) { Text("全选其余") }
                TextButton(onClick = onClear) { Text("清空本组勾选") }
            }
        }
    }
}

@Composable
private fun ImageTile(
    record: ImageRecord,
    isKept: Boolean,
    isChecked: Boolean,
    onKeep: (Long) -> Unit,
    onToggle: (Long) -> Unit
) {
    val containerColor = if (isKept) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.width(132.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(Modifier.padding(6.dp)) {
            Box {
                AsyncImage(
                    model = record.uri,
                    contentDescription = record.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(108.dp)
                )
                if (isKept) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                    ) {
                        Text(
                            text = "保留",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = isChecked,
                    onCheckedChange = { onToggle(record.id) },
                    enabled = !isKept,
                    modifier = Modifier.size(30.dp)
                )
                Text(
                    text = if (isKept) "原图" else "待删",
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Text(
                text = record.dateText.ifBlank { "日期未知" },
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (record.width > 0) "${record.width}×${record.height}" else "尺寸未知",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            TextButton(
                onClick = { onKeep(record.id) },
                enabled = !isKept,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (isKept) "当前保留" else "设为保留",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
