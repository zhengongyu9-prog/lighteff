package com.qingxiao.lighteff.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.data.CountdownItem
import com.qingxiao.lighteff.util.formatFull
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountdownScreen(
    items: List<CountdownItem>,
    onAdd: (String, Long, Boolean) -> Unit,
    onDelete: (String) -> Unit
) {
    var title by rememberSaveable { mutableStateOf("") }
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(-1L) }
    var repeatYearly by rememberSaveable { mutableStateOf(false) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<CountdownItem?>(null) }
    val focusManager = LocalFocusManager.current

    // 从上到下按紧迫性排序：越临近今天越靠上，已经过去的日子统一排在最后
    val today = LocalToday.current
    val sorted = remember(items, today) {
        items.sortedBy { it.urgencyKey(today) }
    }

    fun submit() {
        if (title.isNotBlank() && selectedEpochDay >= 0) {
            onAdd(title, selectedEpochDay, repeatYearly)
            title = ""
            selectedEpochDay = -1L
            repeatYearly = false
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
                title = "倒计日",
                subtitle = "把期待的日子，一目了然地放在眼前"
            )
        }
        item {
            AppCard {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("要期待的日子，比如：生日 / 旅行") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() })
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { showDatePicker = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_calendar),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (selectedEpochDay >= 0) {
                                LocalDate.ofEpochDay(selectedEpochDay).formatFull()
                            } else {
                                "选择日期"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = { submit() },
                        enabled = title.isNotBlank() && selectedEpochDay >= 0,
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_add),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("添加")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("每年重复", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "生日这类日子，过了会自动滚到明年",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = repeatYearly, onCheckedChange = { repeatYearly = it })
                }
            }
        }
        if (sorted.isEmpty()) {
            item {
                EmptyState(
                    iconRes = R.drawable.ic_tab_countdown,
                    title = "还没有倒计日",
                    hint = "添加一个重要的日子，让它有个确切的数字"
                )
            }
        } else {
            items(sorted, key = { it.id }) { item ->
                CountdownCard(
                    item = item,
                    onDelete = { pendingDelete = item },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除倒计日") },
            text = { Text("确定要删除「${item.title}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(item.id)
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

    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = epochDayToUtcMillis(
                if (selectedEpochDay >= 0) selectedEpochDay else LocalDate.now().toEpochDay()
            )
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            selectedEpochDay = utcMillisToEpochDay(millis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("确定")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消")
                }
            }
        ) {
            DatePicker(state = pickerState, showModeToggle = false)
        }
    }
}

@Composable
private fun CountdownCard(
    item: CountdownItem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalToday.current
    val occurrence = item.nextOccurrence(today)
    val days = occurrence.toEpochDay() - today.toEpochDay()

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
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.width(78.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when {
                        days > 0 -> "$days"
                        days == 0L -> "今天"
                        else -> "${-days}"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    color = when {
                        days > 0 -> MaterialTheme.colorScheme.primary
                        days == 0L -> MaterialTheme.colorScheme.secondary
                        else -> MaterialTheme.colorScheme.outline
                    },
                    maxLines = 1
                )
                Text(
                    text = when {
                        days > 0 -> "天后"
                        days == 0L -> "就是今天"
                        else -> "天前"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (item.repeatYearly) {
                        // 显示下一次发生的日期，和上面的排序保持一致
                        "${occurrence.formatFull()} · 每年重复"
                    } else {
                        occurrence.formatFull()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

private fun epochDayToUtcMillis(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun utcMillisToEpochDay(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()

/** 每年重复的日子取下一次发生的日期；2 月 29 日在平年按 2 月 28 日计算 */
private fun CountdownItem.nextOccurrence(today: LocalDate): LocalDate {
    val base = LocalDate.ofEpochDay(epochDay)
    if (!repeatYearly) return base

    fun occurrenceIn(year: Int): LocalDate {
        val length = YearMonth.of(year, base.monthValue).lengthOfMonth()
        return LocalDate.of(year, base.monthValue, minOf(base.dayOfMonth, length))
    }

    val thisYear = occurrenceIn(today.year)
    return if (thisYear.isBefore(today)) occurrenceIn(today.year + 1) else thisYear
}

/** 排序用的紧迫性：未过期的取剩余天数，越近越小；已过期的整体排在最后，刚过的更靠前 */
private fun CountdownItem.urgencyKey(today: LocalDate): Long {
    val days = nextOccurrence(today).toEpochDay() - today.toEpochDay()
    return if (days >= 0) days else Long.MAX_VALUE / 2 + (-days)
}
