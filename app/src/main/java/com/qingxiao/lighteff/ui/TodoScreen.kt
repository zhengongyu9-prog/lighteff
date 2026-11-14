package com.qingxiao.lighteff.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.util.createdLabel
import com.qingxiao.lighteff.util.todayLabel

@Composable
fun TodoScreen(
    tasks: List<TaskItem>,
    onAdd: (String, Int, Int) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableIntStateOf(1) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<TaskItem?>(null) }
    val focusManager = LocalFocusManager.current

    val pendingCount = tasks.count { !it.done }
    val filtered = remember(tasks, filter) {
        when (filter) {
            1 -> tasks.filter { !it.done }
            2 -> tasks.filter { it.done }
            else -> tasks
        }
    }

    // 按优先级排序时拆成高/中/低三段；其余排序方式只有一段
    val groups = remember(filtered, sort) {
        when (sort) {
            0 -> listOf(2 to "高优先级", 1 to "中优先级", 0 to "低优先级")
                .map { (level, name) ->
                    name to filtered
                        .filter { it.priority == level }
                        .sortedWith(compareBy<TaskItem> { it.done }.thenByDescending { it.createdAt })
                }
            1 -> listOf("" to filtered.sortedByDescending { it.createdAt })
            else -> listOf("" to filtered.sortedBy { it.createdAt })
        }
    }

    fun submit() {
        if (input.isNotBlank()) {
            onAdd(input, priority, slot)
            input = ""
            focusManager.clearFocus()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "待办",
                subtitle = if (pendingCount == 0) {
                    "${todayLabel(LocalToday.current)} · 今天的事情都完成啦"
                } else {
                    "${todayLabel(LocalToday.current)} · 还有 $pendingCount 件事没完成"
                }
            )
        }
        item {
            AddTaskCard(
                input = input,
                priority = priority,
                slot = slot,
                onInputChange = { input = it },
                onPriorityChange = { priority = it },
                onSlotChange = { slot = it },
                onSubmit = { submit() }
            )
        }
        item {
            FilterRow(selected = filter, tasks = tasks, onSelect = { filter = it })
        }
        item {
            SortRow(selected = sort, onSelect = { sort = it })
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    iconRes = R.drawable.ic_tab_todo,
                    title = if (tasks.isEmpty()) "还没有待办" else "这里空空如也",
                    hint = if (tasks.isEmpty()) "在上方输入框写下第一件事吧" else "换一个筛选条件看看"
                )
            }
        } else {
            groups.forEach { (name, list) ->
                if (list.isNotEmpty()) {
                    if (name.isNotEmpty()) {
                        item(key = "header-$name") {
                            PriorityHeader(name = name, count = list.size)
                        }
                    }
                    items(list, key = { it.id }) { task ->
                        TaskCard(
                            task = task,
                            onToggle = { onToggle(task.id) },
                            onDelete = { pendingDelete = task },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除待办") },
            text = { Text("确定要删除「${task.title}」吗？已经记录到统计里的完成记录会保留。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(task.id)
                        pendingDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddTaskCard(
    input: String,
    priority: Int,
    slot: Int,
    onInputChange: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onSlotChange: (Int) -> Unit,
    onSubmit: () -> Unit
) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("添加待办事项…") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() })
            )
            Spacer(Modifier.width(10.dp))
            FilledIconButton(
                onClick = onSubmit,
                enabled = input.isNotBlank(),
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_add),
                    contentDescription = "添加",
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "优先级",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf("低", "中", "高").forEachIndexed { index, label ->
                FilterChip(
                    selected = priority == index,
                    onClick = { onPriorityChange(index) },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "时段",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf("不限", "早", "中", "晚").forEachIndexed { index, label ->
                FilterChip(
                    selected = slot == index,
                    onClick = { onSlotChange(index) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: Int, tasks: List<TaskItem>, onSelect: (Int) -> Unit) {
    val doneCount = tasks.count { it.done }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = selected == 0,
            onClick = { onSelect(0) },
            label = { Text("全部 ${tasks.size}") }
        )
        FilterChip(
            selected = selected == 1,
            onClick = { onSelect(1) },
            label = { Text("未完成 ${tasks.size - doneCount}") }
        )
        FilterChip(
            selected = selected == 2,
            onClick = { onSelect(2) },
            label = { Text("已完成 $doneCount") }
        )
    }
}

@Composable
private fun PriorityHeader(name: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "排序",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        listOf("优先级", "最新", "最早").forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = { onSelect(index) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
        }
    }
}

@Composable
private fun SlotPill(slot: Int) {
    val (label, background, foreground) = when (slot) {
        1 -> Triple("早", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        2 -> Triple("中", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else -> Triple("晚", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
    }
    Surface(shape = RoundedCornerShape(6.dp), color = background) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = foreground
        )
    }
}

@Composable
private fun TaskCard(
    task: TaskItem,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(if (task.done) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(
                            width = 1.6.dp,
                            color = if (task.done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (task.done) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "已完成",
                            modifier = Modifier.size(15.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (task.done) FontWeight.Normal else FontWeight.Medium,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (task.slot != 0) SlotPill(task.slot)
                    PriorityPill(task.priority)
                    Text(
                        text = createdLabel(task.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    painter = painterResource(R.drawable.ic_delete),
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}
