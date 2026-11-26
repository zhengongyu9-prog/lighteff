package com.qingxiao.lighteff.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberDateRangePickerState
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
import com.qingxiao.lighteff.util.todayLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

@Composable
fun TodoScreen(
    tasks: List<TaskItem>,
    onAdd: (String, Int, Int, Long?, Long?) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    var input by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableIntStateOf(1) }
    var slot by rememberSaveable { mutableIntStateOf(0) }
    var dueDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var endDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sort by rememberSaveable { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<TaskItem?>(null) }
    val focusManager = LocalFocusManager.current

    // 昨天及以前完成的事第二天不再显示；没完成的继续留着，当天完成的照常显示
    val today = LocalToday.current
    val todayStart = remember(today) {
        today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
    val visibleTasks = remember(tasks, todayStart) {
        tasks.filter { task ->
            !task.done || (task.completedAt ?: Long.MAX_VALUE) >= todayStart
        }
    }

    val pendingCount = visibleTasks.count { !it.done }
    val filtered = remember(visibleTasks, filter) {
        when (filter) {
            1 -> visibleTasks.filter { !it.done }
            2 -> visibleTasks.filter { it.done }
            else -> visibleTasks
        }
    }

    // 按优先级排序时拆成高/中/低三段；按时段排序时按早上/下午/晚上分组；其余只有一段
    val groups = remember(filtered, sort) {
        when (sort) {
            0 -> listOf(2 to "高优先级", 1 to "中优先级", 0 to "低优先级")
                .map { (level, name) ->
                    name to filtered
                        .filter { it.priority == level }
                        .sortedWith(compareBy<TaskItem> { it.done }.thenByDescending { it.createdAt })
                }
            1 -> listOf("" to filtered.sortedByDescending { it.createdAt })
            2 -> listOf("" to filtered.sortedBy { it.createdAt })
            4 -> listOf(
                "" to filtered.sortedWith(
                    compareBy<TaskItem> { it.done }
                        .thenBy { it.dueEpochDay ?: Long.MAX_VALUE }
                        .thenByDescending { it.createdAt }
                )
            )
            else -> listOf(1 to "上午", 2 to "下午", 3 to "晚上", 0 to "未分时段")
                .map { (value, name) ->
                    name to filtered
                        .filter { it.slot == value }
                        .sortedWith(compareBy<TaskItem> { it.done }.thenByDescending { it.createdAt })
                }
        }
    }

    fun submit() {
        if (input.isNotBlank()) {
            onAdd(input, priority, slot, dueDay, endDay)
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
                dueDay = dueDay,
                endDay = endDay,
                onInputChange = { input = it },
                onPriorityChange = { priority = it },
                onSlotChange = { slot = it },
                onDateChange = { start, end ->
                    dueDay = start
                    endDay = end
                },
                onSubmit = { submit() }
            )
        }
        item {
            FilterRow(
                selected = filter,
                tasks = visibleTasks,
                sort = sort,
                onSelect = { filter = it },
                onSortSelect = { sort = it }
            )
        }
        if (filtered.isEmpty()) {
            item {
                EmptyState(
                    iconRes = R.drawable.ic_tab_todo,
                    title = if (visibleTasks.isEmpty()) "还没有待办" else "这里空空如也",
                    hint = if (visibleTasks.isEmpty()) "在上方输入框写下第一件事吧" else "换一个筛选条件看看"
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddTaskCard(
    input: String,
    priority: Int,
    slot: Int,
    dueDay: Long?,
    endDay: Long?,
    onInputChange: (String) -> Unit,
    onPriorityChange: (Int) -> Unit,
    onSlotChange: (Int) -> Unit,
    onDateChange: (Long?, Long?) -> Unit,
    onSubmit: () -> Unit
) {
    // 默认收起日期/时段/优先级，避免没输入时也占掉一大块屏幕
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showRangePicker by remember { mutableStateOf(false) }
    val today = LocalToday.current
    val todayDay = today.toEpochDay()
    val quickDays = listOf(todayDay to "今天", todayDay + 1 to "明天", todayDay + 2 to "后天")
    val priorityLabel = listOf("低", "中", "高")[priority.coerceIn(0, 2)]
    val slotLabel = listOf("不限", "上午", "下午", "晚上")[slot.coerceIn(0, 3)]
    val dateSummary = when {
        dueDay == null -> "不限日期"
        endDay != null -> "阶段 ${shortDate(dueDay)}–${shortDate(endDay)}"
        else -> planDateLabel(dueDay, today)
    }

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
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "$dateSummary · $slotLabel · 优先级 $priorityLabel",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (expanded) "收起" else "更多选项",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            // 日期：今天/明天/后天快捷选，也可以翻日历或选一个阶段
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "日期",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilterChip(
                    selected = dueDay == null,
                    onClick = { onDateChange(null, null) },
                    label = { Text("不限") }
                )
                quickDays.forEach { (day, label) ->
                    FilterChip(
                        selected = dueDay == day && endDay == null,
                        onClick = { onDateChange(day, null) },
                        label = { Text(label) }
                    )
                }
                val customDay = dueDay?.takeIf { endDay == null && it !in quickDays.map { pair -> pair.first } }
                FilterChip(
                    selected = customDay != null,
                    onClick = { showDatePicker = true },
                    label = { Text(customDay?.let { planDateLabel(it, today) } ?: "选日期") }
                )
                FilterChip(
                    selected = endDay != null,
                    onClick = { showRangePicker = true },
                    label = { Text("阶段") }
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "时段",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf("不限", "上午", "下午", "晚上").forEachIndexed { index, label ->
                    FilterChip(
                        selected = slot == index,
                        onClick = { onSlotChange(index) },
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
        }
    }

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = dueDay?.let { epochDayToUtcMillis(it) }
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        dateState.selectedDateMillis?.let {
                            onDateChange(utcMillisToEpochDay(it), null)
                        }
                        showDatePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("取消") }
            }
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showRangePicker) {
        val rangeState = rememberDateRangePickerState(
            initialSelectedStartDateMillis = dueDay?.let { epochDayToUtcMillis(it) },
            initialSelectedEndDateMillis = endDay?.let { epochDayToUtcMillis(it) }
        )
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val start = rangeState.selectedStartDateMillis
                        val end = rangeState.selectedEndDateMillis
                        if (start != null) {
                            val startDay = utcMillisToEpochDay(start)
                            val finish = end?.let { utcMillisToEpochDay(it) }?.takeIf { it > startDay }
                            onDateChange(startDay, finish)
                        }
                        showRangePicker = false
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showRangePicker = false }) { Text("取消") }
            }
        ) {
            DateRangePicker(state = rangeState, modifier = Modifier.weight(1f))
        }
    }
}

private val sortLabels = listOf("优先级", "最新", "最早", "时段", "日期")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(
    selected: Int,
    tasks: List<TaskItem>,
    sort: Int,
    onSelect: (Int) -> Unit,
    onSortSelect: (Int) -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    val doneCount = tasks.count { it.done }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
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
        // 排序收进一个下拉，和筛选用同一行，省出一整行高度
        Box {
            FilterChip(
                selected = false,
                onClick = { showSortMenu = true },
                label = {
                    Text(
                        text = "排序 · ${sortLabels.getOrElse(sort) { sortLabels[0] }}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            )
            DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                sortLabels.forEachIndexed { index, label ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSortSelect(index)
                            showSortMenu = false
                        }
                    )
                }
            }
        }
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

@Composable
private fun SlotPill(slot: Int) {
    val (label, background, foreground) = when (slot) {
        1 -> Triple("上午", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        2 -> Triple("下午", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        else -> Triple("晚上", MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
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
                // 已完成的任务只留标题，不再堆日期/优先级/时段这些没用的信息
                if (!task.done) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (task.dueEpochDay != null) DatePill(task)
                        if (task.slot != 0) SlotPill(task.slot)
                        PriorityPill(task.priority)
                    }
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

@Composable
private fun DatePill(task: TaskItem) {
    val start = task.dueEpochDay
    if (start != null) {
        val end = task.endEpochDay
        val today = LocalToday.current
        val todayDay = today.toEpochDay()
        val overdue = if (end != null) end < todayDay else start < todayDay
        val label = if (end != null && end > start) {
            "阶段 ${shortDate(start)}–${shortDate(end)}"
        } else {
            planDateLabel(start, today)
        }
        val background = if (overdue) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        val foreground = if (overdue) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
        Surface(shape = RoundedCornerShape(6.dp), color = background) {
            Text(
                text = if (overdue) "逾期 · $label" else label,
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = foreground
            )
        }
    }
}

private fun planDateLabel(day: Long, today: LocalDate): String {
    val date = LocalDate.ofEpochDay(day)
    return when (date) {
        today -> "今天"
        today.plusDays(1) -> "明天"
        today.plusDays(2) -> "后天"
        else -> "${date.monthValue}月${date.dayOfMonth}日"
    }
}

private fun shortDate(day: Long): String {
    val date = LocalDate.ofEpochDay(day)
    return "${date.monthValue}/${date.dayOfMonth}"
}

private fun epochDayToUtcMillis(day: Long): Long =
    LocalDate.ofEpochDay(day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToEpochDay(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
