package com.sopro.imagededup.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sopro.imagededup.model.ScanPhase
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel) {
    val context = LocalContext.current

    val phase by viewModel.phase.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val folderName by viewModel.folderName.collectAsStateWithLifecycle()
    val treeUri by viewModel.treeUri.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    val backupEnabled by viewModel.backupEnabled.collectAsStateWithLifecycle()
    val isDeleting by viewModel.isDeleting.collectAsStateWithLifecycle()

    val selectedCount = groups.sumOf { it.toDelete.size }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.setFolder(uri) }

    var showThresholdDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.message.collect { text ->
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "本地图片查重") },
                actions = {
                    TextButton(onClick = { showThresholdDialog = true }) {
                        Text("相似度 $threshold")
                    }
                }
            )
        },
        bottomBar = {
            DeleteBar(
                selectedCount = selectedCount,
                backupEnabled = backupEnabled,
                onBackupChange = { viewModel.setBackupEnabled(it) },
                onDeleteClick = { showDeleteDialog = true },
                enabled = selectedCount > 0 && !isDeleting
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                FolderCard(
                    folderName = folderName,
                    hasFolder = treeUri != null,
                    scanning = viewModel.isScanning,
                    onPick = { pickFolder.launch(null) },
                    onStart = { viewModel.startScan() }
                )
            }

            item {
                ProgressCard(
                    phase = phase,
                    paused = paused,
                    onPause = { viewModel.pauseScan() },
                    onResume = { viewModel.resumeScan() },
                    onCancel = { viewModel.cancelScan() }
                )
            }

            if (phase is ScanPhase.Finished) {
                item {
                    val finished = phase as ScanPhase.Finished
                    InfoCard(
                        text = "共扫描 ${finished.scanned} 张图片 · " +
                            "发现 ${finished.groups} 组疑似重复 · " +
                            "涉及 ${finished.involved} 张"
                    )
                }
            }

            items(items = groups, key = { it.id }) { group ->
                GroupCard(
                    group = group,
                    records = group.members.mapNotNull { records[it] },
                    onKeep = { viewModel.setKeep(group.id, it) },
                    onToggle = { viewModel.toggleDelete(group.id, it) },
                    onSelectAll = { viewModel.selectAllOthers(group.id) },
                    onClear = { viewModel.clearSelection(group.id) }
                )
            }

            if (phase is ScanPhase.Finished && groups.isEmpty()) {
                item { InfoCard(text = "没有发现重复图片。") }
            }
        }
    }

    if (showThresholdDialog) {
        ThresholdDialog(
            current = threshold,
            onDismiss = { showThresholdDialog = false },
            onConfirm = { viewModel.setThreshold(it); showThresholdDialog = false }
        )
    }

    if (showDeleteDialog) {
        DeleteConfirmDialog(
            count = selectedCount,
            backupEnabled = backupEnabled,
            onDismiss = { showDeleteDialog = false },
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteSelected()
            }
        )
    }
}

@Composable
private fun FolderCard(
    folderName: String,
    hasFolder: Boolean,
    scanning: Boolean,
    onPick: () -> Unit,
    onStart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("扫描文件夹", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (hasFolder) "已选择：$folderName" else "尚未选择文件夹（将递归扫描全部子文件夹）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onPick) { Text("选择文件夹") }
                Button(onClick = onStart, enabled = hasFolder && !scanning) { Text("开始扫描") }
            }
        }
    }
}

@Composable
private fun ProgressCard(
    phase: ScanPhase,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val active = phase is ScanPhase.Enumerating ||
        phase is ScanPhase.Analyzing ||
        phase is ScanPhase.Grouping

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(12.dp)) {
            when (phase) {
                is ScanPhase.Idle -> Text("尚未开始扫描", style = MaterialTheme.typography.bodyMedium)

                is ScanPhase.Enumerating -> {
                    Text("正在枚举文件… 已找到 ${phase.found} 张图片")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is ScanPhase.Analyzing -> {
                    Text("正在分析 ${phase.done} / ${phase.total} 张")
                    Spacer(Modifier.height(6.dp))
                    val progress = if (phase.total > 0) {
                        phase.done.toFloat() / phase.total.toFloat()
                    } else {
                        0f
                    }
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                is ScanPhase.Grouping -> {
                    Text("正在分组疑似重复图片…")
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is ScanPhase.Finished -> Text(
                    "扫描完成：${phase.groups} 组疑似重复",
                    style = MaterialTheme.typography.bodyMedium
                )

                is ScanPhase.Failed -> Text(
                    "扫描失败：${phase.message}",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (active) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = if (paused) onResume else onPause) {
                        Text(if (paused) "继续" else "暂停")
                    }
                    OutlinedButton(onClick = onCancel) { Text("取消") }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun DeleteBar(
    selectedCount: Int,
    backupEnabled: Boolean,
    onBackupChange: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
    enabled: Boolean
) {
    Surface(shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = backupEnabled, onCheckedChange = onBackupChange)
                Text("删除前备份到独立文件夹", style = MaterialTheme.typography.bodySmall)
            }
            if (!backupEnabled) {
                Text(
                    "未开启备份：删除后无法恢复",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onDeleteClick,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (selectedCount > 0) "删除已勾选的 $selectedCount 张" else "请先勾选要删除的图片")
            }
        }
    }
}

@Composable
private fun ThresholdDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var draft by remember { mutableFloatStateOf(current.toFloat()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("相似度严格程度") },
        text = {
            Column {
                Text("数值越小越严格（只找出几乎一样的图）；数值越大找回越多，但误判也会增加。所有结果都需要你人工复核。")
                Spacer(Modifier.height(8.dp))
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    valueRange = 0f..7f,
                    steps = 6
                )
                Text("当前：${draft.roundToInt()}")
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(draft.roundToInt()) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
private fun DeleteConfirmDialog(
    count: Int,
    backupEnabled: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认删除") },
        text = {
            Text(
                if (backupEnabled) {
                    "即将删除你勾选的 $count 张图片，删除前会先复制到备份文件夹。" +
                        "每组中标记为“保留”的图片不会被删除。"
                } else {
                    "即将删除你勾选的 $count 张图片。此操作不可恢复，" +
                        "每组中标记为“保留”的图片不会被删除。"
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("确认删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("再检查一下") }
        }
    )
}
