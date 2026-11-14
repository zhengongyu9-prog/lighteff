package com.qingxiao.lighteff.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qingxiao.lighteff.CloudUiState
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.data.HabitItem
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.data.TaskRecord
import com.qingxiao.lighteff.data.TimerMode
import com.qingxiao.lighteff.data.TimerSession
import com.qingxiao.lighteff.util.dateLabel
import com.qingxiao.lighteff.util.formatDurationCn
import com.qingxiao.lighteff.util.habitStreak
import com.qingxiao.lighteff.util.momentLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

private data class StatItem(val label: String, val value: String, val sub: String)

private data class CompletionEvent(
    val timestamp: Long,
    val iconRes: Int,
    val title: String,
    val subtitle: String
)

private data class DaySummary(
    val seconds: Int = 0,
    val sessionCount: Int = 0,
    val tasksDone: Int = 0,
    val checkIns: Int = 0,
    val events: List<CompletionEvent> = emptyList()
) {
    val hasActivity: Boolean get() = seconds > 0 || tasksDone > 0 || checkIns > 0
}

private data class FocusItem(
    val label: String,
    val seconds: Int,
    val count: Int
)

private data class StatsSnapshot(
    val cards: List<StatItem>,
    val days: Map<LocalDate, DaySummary>,
    val items: List<FocusItem>,
    val events: List<CompletionEvent>
)

@Composable
fun StatsScreen(
    tasks: List<TaskItem>,
    habits: List<HabitItem>,
    sessions: List<TimerSession>,
    taskRecords: List<TaskRecord>,
    cloud: CloudUiState,
    onBackupExport: () -> String,
    onBackupImport: (String) -> Boolean,
    onCloudLogin: (String, String, Boolean) -> Unit,
    onCloudSendReset: (String) -> Unit,
    onCloudLogout: () -> Unit,
    onCloudUpload: () -> Unit,
    onCloudRestore: () -> Unit,
    onCloudMessageShown: () -> Unit
) {
    var showCloudDialog by rememberSaveable { mutableStateOf(false) }
    var cloudRegisterMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(cloud.loggedIn) {
        if (cloud.loggedIn) showCloudDialog = false
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(onBackupExport().toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) "备份已导出" else "导出失败", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val ok = raw != null && onBackupImport(raw)
            Toast.makeText(context, if (ok) "备份已恢复" else "备份文件无法识别", Toast.LENGTH_SHORT).show()
        }
    }
    val today = LocalToday.current
    var selectedEpochDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    // 用户手动选过日期后就不再跟着「今天」自动跳
    var followsToday by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(today) {
        if (followsToday) {
            selectedEpochDay = today.toEpochDay()
            monthKey = YearMonth.from(today).toString()
        }
    }
    val selectedDate = LocalDate.ofEpochDay(selectedEpochDay)
    val stats = remember(tasks, habits, sessions, taskRecords, selectedEpochDay, today) {
        buildStats(tasks, habits, sessions, taskRecords, selectedDate, today)
    }
    val month = remember(monthKey) {
        runCatching { YearMonth.parse(monthKey) }.getOrDefault(YearMonth.now())
    }
    val day = stats.days[selectedDate] ?: DaySummary()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            ScreenHeader(
                title = "统计",
                subtitle = "每一份投入，都有记录"
            )
        }
        if (stats.items.isEmpty()) {
            item {
                EmptyState(
                    iconRes = R.drawable.ic_tab_stats,
                    title = "还没有专注记录",
                    hint = "完成一次计时后，这里会按事项统计时长"
                )
            }
        } else {
            item {
                FocusItemCard(items = stats.items, events = stats.events)
            }
        }
        item {
            StatGrid(cards = stats.cards)
        }
        item {
            CalendarCard(
                month = month,
                days = stats.days,
                selected = selectedDate,
                summary = day,
                onSelect = { date ->
                    selectedEpochDay = date.toEpochDay()
                    monthKey = YearMonth.from(date).toString()
                    followsToday = false
                },
                onPrevMonth = {
                    monthKey = month.minusMonths(1).toString()
                    followsToday = false
                },
                onNextMonth = {
                    monthKey = month.plusMonths(1).toString()
                    followsToday = false
                }
            )
        }
        item {
            AppCard {
                Text(text = "账号与云同步", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                if (!cloud.loggedIn) {
                    Text(
                        text = "登录后数据会自动备份到云端，换手机登录同一账号即可恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            cloudRegisterMode = false
                            onCloudMessageShown()
                            showCloudDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("登录 / 注册")
                    }
                } else {
                    Text(text = cloud.email ?: "", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = cloud.lastSyncAt?.let { "上次同步：${momentLabel(it)}" } ?: "尚未同步",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (cloud.needsAttention) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "云端已有备份：可以恢复云端，或用本机数据覆盖",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onCloudUpload,
                            modifier = Modifier.weight(1f),
                            enabled = !cloud.busy,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("立即同步")
                        }
                        OutlinedButton(
                            onClick = onCloudRestore,
                            modifier = Modifier.weight(1f),
                            enabled = !cloud.busy,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("从云端恢复")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (cloud.busy) {
                            Text(
                                text = "同步中…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onCloudLogout, enabled = !cloud.busy) {
                            Text("退出登录")
                        }
                    }
                }
                cloud.message?.let { message ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
        item {
            AppCard {
                Text(text = "数据备份", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "导出后可以存到网盘或电脑，换机、重装后导入恢复",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { exportLauncher.launch("lighteff-backup.json") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("导出备份")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Text("导入备份")
                    }
                }
            }
        }
    }

    if (showCloudDialog && !cloud.loggedIn) {
        CloudLoginDialog(
            registerMode = cloudRegisterMode,
            busy = cloud.busy,
            message = cloud.message,
            onModeChange = { register ->
                cloudRegisterMode = register
                onCloudMessageShown()
            },
            onDismiss = { showCloudDialog = false },
            onConfirm = { email, password ->
                onCloudLogin(email, password, cloudRegisterMode)
            },
            onSendReset = { email -> onCloudSendReset(email) }
        )
    }
}

@Composable
private fun CloudLoginDialog(
    registerMode: Boolean,
    busy: Boolean,
    message: String?,
    onModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
    onSendReset: (String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (registerMode) "注册账号" else "登录账号") },
        text = {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = { onModeChange(false) }, enabled = registerMode) {
                        Text("登录")
                    }
                    TextButton(onClick = { onModeChange(true) }, enabled = !registerMode) {
                        Text("注册")
                    }
                    Spacer(Modifier.weight(1f))
                    if (!registerMode) {
                        TextButton(onClick = { onSendReset(email) }, enabled = !busy) {
                            Text("忘记密码？", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                if (message != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(email, password) }, enabled = !busy) {
                Text(
                    when {
                        busy -> "请稍候…"
                        registerMode -> "注册"
                        else -> "登录"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun StatGrid(cards: List<StatItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        cards.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowItems.forEach { item ->
                    StatCard(item = item, modifier = Modifier.weight(1f))
                }
                if (rowItems.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun StatCard(item: StatItem, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = item.value,
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = item.sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CalendarCard(
    month: YearMonth,
    days: Map<LocalDate, DaySummary>,
    selected: LocalDate,
    summary: DaySummary,
    onSelect: (LocalDate) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val today = LocalToday.current
    // 当月最长专注时长，用作颜色深浅的基准
    val maxSeconds = days
        .filterKeys { YearMonth.from(it) == month }
        .values
        .maxOfOrNull { it.seconds } ?: 0

    AppCard {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "专注日历",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onPrevMonth,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("‹", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                text = "${month.year} 年 ${month.monthValue} 月",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            TextButton(
                onClick = onNextMonth,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text("›", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
        Text(
            text = "点日期看记录 · 颜色越深专注越久",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("一", "二", "三", "四", "五", "六", "日").forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }

        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = buildList<LocalDate?> {
            repeat(offset) { add(null) }
            for (dayOfMonth in 1..month.lengthOfMonth()) add(month.atDay(dayOfMonth))
            while (size % 7 != 0) add(null)
        }
        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { date ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (date != null) {
                            DayCell(
                                date = date,
                                selected = date == selected,
                                seconds = days[date]?.seconds ?: 0,
                                maxSeconds = maxSeconds,
                                hasActivity = days[date]?.hasActivity == true,
                                isToday = date == today,
                                onClick = { onSelect(date) }
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            DayStat("专注", formatDurationCn(summary.seconds), "共 ${summary.sessionCount} 次", Modifier.weight(1f))
            DayStat("待办完成", "${summary.tasksDone} 件", "当天", Modifier.weight(1f))
            DayStat("打卡", "${summary.checkIns} 次", "当天", Modifier.weight(1f))
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    selected: Boolean,
    seconds: Int,
    maxSeconds: Int,
    hasActivity: Boolean,
    isToday: Boolean,
    onClick: () -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val fraction = if (maxSeconds <= 0) 0f else (seconds.toFloat() / maxSeconds).coerceIn(0f, 1f)
    // 专注越久，底色越深；只有待办/打卡的日子用最浅的一档
    val background = when {
        selected -> primary
        seconds > 0 -> primary.copy(alpha = 0.14f + 0.56f * fraction)
        hasActivity -> primary.copy(alpha = 0.12f)
        else -> Color.Transparent
    }
    val textColor = when {
        selected || (seconds > 0 && fraction >= 0.5f) -> MaterialTheme.colorScheme.onPrimary
        hasActivity -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ring = if (isToday && !selected) {
        Modifier.border(1.dp, primary.copy(alpha = 0.6f), CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .then(ring)
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "${date.dayOfMonth}",
            style = MaterialTheme.typography.labelLarge,
            color = textColor
        )
    }
}

@Composable
private fun DayStat(label: String, value: String, sub: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun FocusItemCard(items: List<FocusItem>, events: List<CompletionEvent>) {
    var showChart by rememberSaveable { mutableStateOf(true) }
    val totalSeconds = items.sumOf { it.seconds }

    AppCard {
        Column {
            Text(text = "专注记录", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "合计 ${formatDurationCn(totalSeconds)} · ${items.size} 件事 · ${events.size} 条记录",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf(true to "分布图", false to "明细").forEachIndexed { index, entry ->
                    SegmentedButton(
                        selected = showChart == entry.first,
                        onClick = { showChart = entry.first },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                        label = { Text(entry.second) }
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (showChart) {
            FocusChart(items = items, totalSeconds = totalSeconds)
        } else {
            EventList(events = events)
        }
    }
}

/** 圆环图上的一条引线标签 */
private class ChartLabel(
    val color: Color,
    val p1: Offset,
    val p2: Offset,
    val isRight: Boolean,
    val nameLayout: TextLayoutResult,
    val valueLayout: TextLayoutResult,
    val blockWidth: Float,
    val blockHeight: Float,
    val y: Float,
    var blockTop: Float
)

/** 圆环分布图：带引线标注 + 图例 */
@Composable
private fun FocusChart(items: List<FocusItem>, totalSeconds: Int) {
    val total = totalSeconds.coerceAtLeast(1).toFloat()
    val textMeasurer = rememberTextMeasurer()
    val nameStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
    val valueStyle = MaterialTheme.typography.labelSmall
    val nameColor = MaterialTheme.colorScheme.onSurface
    val valueColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.outline

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(228.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = 22.dp.toPx()
                val radius = 50.dp.toPx()
                val center = Offset(size.width / 2f, size.height / 2f)
                val arcTopLeft = Offset(center.x - radius, center.y - radius)
                val arcSize = Size(radius * 2f, radius * 2f)
                val outer = radius + stroke / 2f
                var startAngle = -90f
                val labels = mutableListOf<ChartLabel>()

                items.forEachIndexed { index, item ->
                    val sweep = 360f * item.seconds / total
                    drawArc(
                        color = focusPalette[index % focusPalette.size],
                        startAngle = startAngle,
                        sweepAngle = (sweep - 2f).coerceAtLeast(0.6f),
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke)
                    )

                    val midAngle = startAngle + sweep / 2f
                    val rad = Math.toRadians(midAngle.toDouble())
                    val cosA = cos(rad).toFloat()
                    val sinA = sin(rad).toFloat()
                    val p1 = Offset(center.x + outer * cosA, center.y + outer * sinA)
                    val p2 = Offset(
                        center.x + (outer + 12.dp.toPx()) * cosA,
                        center.y + (outer + 12.dp.toPx()) * sinA
                    )
                    val shortName = if (item.label.length > 6) item.label.take(6) + "…" else item.label
                    val nameLayout = textMeasurer.measure(AnnotatedString(shortName), nameStyle, maxLines = 1)
                    val valueLayout = textMeasurer.measure(
                        AnnotatedString(formatDurationCn(item.seconds)),
                        valueStyle,
                        maxLines = 1
                    )
                    labels += ChartLabel(
                        color = focusPalette[index % focusPalette.size],
                        p1 = p1,
                        p2 = p2,
                        isRight = cosA >= 0f,
                        nameLayout = nameLayout,
                        valueLayout = valueLayout,
                        blockWidth = max(nameLayout.size.width, valueLayout.size.width).toFloat(),
                        blockHeight = (nameLayout.size.height + valueLayout.size.height + 2.dp.toPx()).toFloat(),
                        y = p2.y,
                        blockTop = 0f
                    )
                    startAngle += sweep
                }

                // 左右两侧分别排布，避免文字互相压住
                val margin = 4.dp.toPx()
                val gapY = 3.dp.toPx()
                listOf(true, false).forEach { right ->
                    val side = labels.filter { it.isRight == right }.sortedBy { it.p2.y }
                    var cursor = margin
                    side.forEach { label ->
                        label.blockTop = max(label.p2.y - label.blockHeight / 2f, cursor)
                        cursor = label.blockTop + label.blockHeight + gapY
                    }
                    val last = side.lastOrNull()
                    if (last != null) {
                        val overflow = last.blockTop + last.blockHeight + margin - size.height
                        if (overflow > 0f) {
                            side.forEach { it.blockTop -= overflow }
                            side.forEach { it.blockTop = it.blockTop.coerceAtLeast(margin) }
                        }
                    }
                }

                labels.forEach { label ->
                    val lineX = label.p2.x + if (label.isRight) 12.dp.toPx() else -12.dp.toPx()
                    val lineY = label.blockTop + label.blockHeight / 2f
                    drawLine(label.color, label.p1, label.p2, strokeWidth = 1.2.dp.toPx())
                    drawLine(
                        label.color,
                        label.p2,
                        Offset(lineX, lineY),
                        strokeWidth = 1.2.dp.toPx()
                    )
                    val x = if (label.isRight) {
                        lineX + 4.dp.toPx()
                    } else {
                        lineX - 4.dp.toPx() - label.blockWidth
                    }
                    drawText(label.nameLayout, topLeft = Offset(x, label.blockTop), color = nameColor)
                    drawText(
                        label.valueLayout,
                        topLeft = Offset(x, label.blockTop + label.nameLayout.size.height + 2.dp.toPx()),
                        color = valueColor
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "累计",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDurationCn(totalSeconds),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(focusPalette[index % focusPalette.size])
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatDurationCn(item.seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "${(item.seconds * 100f / total).roundToInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private val focusPalette = listOf(
    Color(0xFF2F6B5A),
    Color(0xFFE0B184),
    Color(0xFF8FB3A6),
    Color(0xFFC97C5D),
    Color(0xFF7D93A8),
    Color(0xFFB6A57B)
)

@Composable
private fun EventList(events: List<CompletionEvent>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        events.forEachIndexed { index, event ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(event.iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(19.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = event.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (index != events.lastIndex) {
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
            }
        }
    }
}

private fun buildStats(
    tasks: List<TaskItem>,
    habits: List<HabitItem>,
    sessions: List<TimerSession>,
    taskRecords: List<TaskRecord>,
    selectedDate: LocalDate,
    today: LocalDate
): StatsSnapshot {
    val zone = ZoneId.systemDefault()

    fun dateOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate()

    val daySessions = sessions.filter { dateOf(it.completedAt) == selectedDate }
    val daySeconds = daySessions.sumOf { it.seconds }
    val totalSeconds = sessions.sumOf { it.seconds }
    // 本周从周一开始、本月从 1 号开始，到本周/本月为止，跨周期后自然重新累计
    val weekStart = today.with(DayOfWeek.MONDAY)
    val monthStart = today.withDayOfMonth(1)
    val weekSessions = sessions.filter {
        val date = dateOf(it.completedAt)
        !date.isBefore(weekStart) && !date.isAfter(today)
    }
    val monthSessions = sessions.filter {
        val date = dateOf(it.completedAt)
        !date.isBefore(monthStart) && !date.isAfter(today)
    }
    // 同一件待办在同一天反复勾选只算完成一次，避免完成率被刷高
    val uniqueRecords = taskRecords.distinctBy { it.taskId to dateOf(it.completedAt) }
    // 完成记录独立保存，删除或取消勾选待办都不影响历史
    val dayDoneTasks = uniqueRecords.count { dateOf(it.completedAt) == selectedDate }
    val dayCreatedOpen = tasks.count { !it.done && dateOf(it.createdAt) == selectedDate }
    val dayTaskTotal = dayDoneTasks + dayCreatedOpen
    val activeHabitCount = habits.count { !it.archived }
    val dayCheckIns = habits.count { selectedDate.toString() in it.checkedDates }
    val totalCheckIns = habits.sumOf { it.checkedDates.size }
    val maxStreak = habits.maxOfOrNull { habitStreak(it.checkedDates, today) } ?: 0
    val focusStreak = focusStreakDays(sessions, today)

    val isToday = selectedDate == today
    val dayTag = if (isToday) "今天" else "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日"
    // 当天既没完成也没新建待办时，不显示 0%，用「—」表示没有可统计的口径
    val dayRateText = if (dayTaskTotal == 0) "—" else "${dayDoneTasks * 100 / dayTaskTotal}%"
    val dayRateSub = if (dayTaskTotal == 0) {
        "$dayTag · 暂无待办"
    } else {
        "$dayTag · 完成 $dayDoneTasks / $dayTaskTotal 件"
    }

    val cards = listOf(
        StatItem("专注时长", formatDurationCn(daySeconds), "$dayTag · 共 ${daySessions.size} 次"),
        StatItem("累计专注", formatDurationCn(totalSeconds), "共 ${sessions.size} 次"),
        StatItem(
            "本周专注",
            formatDurationCn(weekSessions.sumOf { it.seconds }),
            "周一至今 · ${weekSessions.size} 次"
        ),
        StatItem(
            "本月专注",
            formatDurationCn(monthSessions.sumOf { it.seconds }),
            "1 号至今 · ${monthSessions.size} 次"
        ),
        StatItem("连续专注", "$focusStreak 天", if (focusStreak > 0) "保持节奏" else "今天开始"),
        StatItem("完成计时", "${sessions.size} 次", "倒计时 + 正计时"),
        StatItem("待办完成", "$dayDoneTasks 件", "$dayTag · 共 ${tasks.size} 件待办"),
        StatItem("待办完成率", dayRateText, dayRateSub),
        StatItem("打卡次数", "$dayCheckIns 次", "$dayTag · 共 $activeHabitCount 个习惯"),
        StatItem("累计打卡", "$totalCheckIns 次", "最长连续 $maxStreak 天")
    )

    val allEvents = buildList {
        sessions.forEach { session ->
            val modeText = if (session.mode == TimerMode.Countdown) "倒计时" else "正计时"
            val hasLabel = session.label.isNotBlank()
            add(
                CompletionEvent(
                    timestamp = session.completedAt,
                    iconRes = R.drawable.ic_tab_countdown,
                    title = if (hasLabel) session.label else "${modeText}专注",
                    subtitle = buildString {
                        if (hasLabel) {
                            append(modeText).append(" · ")
                        }
                        append(formatDurationCn(session.seconds))
                        append(" · ").append(momentLabel(session.completedAt))
                    }
                )
            )
        }
        uniqueRecords.forEach { record ->
            add(
                CompletionEvent(
                    timestamp = record.completedAt,
                    iconRes = R.drawable.ic_tab_todo,
                    title = record.title,
                    subtitle = "待办完成 · ${momentLabel(record.completedAt)}"
                )
            )
        }
        habits.forEach { habit ->
            habit.checkedDates
                .mapNotNull { day -> runCatching { LocalDate.parse(day) }.getOrNull() }
                .forEach { date ->
                    add(
                        CompletionEvent(
                            timestamp = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                            iconRes = R.drawable.ic_tab_habit,
                            title = habit.name,
                            subtitle = "习惯打卡 · ${dateLabel(date)}"
                        )
                    )
                }
        }
    }.sortedByDescending { it.timestamp }

    val focusByDate = sessions.groupBy { dateOf(it.completedAt) }
    val doneByDate = uniqueRecords.groupBy { dateOf(it.completedAt) }
    val checkInByDate = mutableMapOf<LocalDate, Int>()
    habits.forEach { habit ->
        habit.checkedDates.forEach { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()?.let { date ->
                checkInByDate[date] = (checkInByDate[date] ?: 0) + 1
            }
        }
    }
    val eventsByDate = allEvents.groupBy { dateOf(it.timestamp) }
    val days = (focusByDate.keys + doneByDate.keys + checkInByDate.keys).associateWith { date ->
        DaySummary(
            seconds = focusByDate[date].orEmpty().sumOf { it.seconds },
            sessionCount = focusByDate[date].orEmpty().size,
            tasksDone = doneByDate[date].orEmpty().size,
            checkIns = checkInByDate[date] ?: 0,
            events = eventsByDate[date].orEmpty()
        )
    }

    val dayEvents = allEvents.filter { dateOf(it.timestamp) == selectedDate }
    return StatsSnapshot(
        cards = cards,
        days = days,
        items = buildFocusItems(daySessions),
        events = dayEvents
    )
}

/** 按「这次做的事」汇总专注时长，没写事项的记录归到「未指定事项」 */
private fun buildFocusItems(sessions: List<TimerSession>): List<FocusItem> =
    sessions
        .groupBy { it.label.trim().ifEmpty { "未指定事项" } }
        .map { (label, list) -> FocusItem(label = label, seconds = list.sumOf { it.seconds }, count = list.size) }
        .sortedByDescending { it.seconds }

private fun focusStreakDays(sessions: List<TimerSession>, today: LocalDate): Int {
    val zone = ZoneId.systemDefault()
    val days = sessions
        .map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
        .toSet()
    var cursor = if (today in days) today else today.minusDays(1)
    var count = 0
    while (cursor in days) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}
