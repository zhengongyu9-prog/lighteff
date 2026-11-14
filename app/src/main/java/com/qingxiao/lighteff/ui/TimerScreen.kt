package com.qingxiao.lighteff.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qingxiao.lighteff.AppViewModel
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.TimerState
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.data.TimerMode
import java.util.Locale
import kotlin.math.max

@Composable
fun TimerScreen(viewModel: AppViewModel) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val appState by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var showCompletedDialog by rememberSaveable { mutableStateOf(false) }
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<TimerMode?>(null) }
    var seenTick by rememberSaveable { mutableLongStateOf(timer.completionTick) }

    // 计时期间通知栏要显示大字时间，Android 13+ 需要通知权限
    var notifyDenied by remember { mutableStateOf(isNotifyPermissionMissing(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notifyDenied = !granted }
    fun ensureNotifyPermission() {
        if (isNotifyPermissionMissing(context)) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // 从系统设置里改完权限再回来，重新判断一次
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) notifyDenied = isNotifyPermissionMissing(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(timer.completionTick) {
        if (timer.completionTick > seenTick) {
            seenTick = timer.completionTick
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            showCompletedDialog = true
        }
    }

    TimerContent(
        timer = timer,
        onSelectMode = { target ->
            // 暂停中已有进度时，直接切换会把进度清空，先让用户确认
            if (target != timer.mode && !timer.running && timer.elapsedMillis > 0L) {
                pendingMode = target
            } else {
                viewModel.setTimerMode(target)
            }
        },
        onSelectMinutes = viewModel::setCountdownMinutes,
        onToggle = {
            if (!timer.running) ensureNotifyPermission()
            // 倒计时已经走完时 elapsed 停在总时长上，也属于「新的一次计时」
            val countdownFinished = timer.mode == TimerMode.Countdown &&
                timer.elapsedMillis >= timer.targetMinutes * 60_000L
            when {
                timer.running -> viewModel.toggleTimer()
                // 新的一次计时，先让用户选/写这次做什么
                timer.elapsedMillis == 0L || countdownFinished -> showPicker = true
                else -> viewModel.toggleTimer()
            }
        },
        onReset = viewModel::resetTimer,
        onFinish = {
            viewModel.finishStopwatch()
            Toast.makeText(context, "本次专注已记录", Toast.LENGTH_SHORT).show()
        },
        notifyDenied = notifyDenied,
        onOpenNotifySettings = {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                )
            }
        }
    )

    if (showPicker) {
        FocusPickerDialog(
            tasks = appState.tasks.filter { !it.done },
            onDismiss = { showPicker = false },
            onConfirm = { label ->
                showPicker = false
                viewModel.startWithLabel(label)
            }
        )
    }

    if (showCompletedDialog) {
        AlertDialog(
            onDismissRequest = { showCompletedDialog = false },
            title = { Text("专注完成") },
            text = { Text("本次 ${timer.targetMinutes} 分钟已记录到统计中。") },
            confirmButton = {
                TextButton(onClick = { showCompletedDialog = false }) {
                    Text("好的")
                }
            }
        )
    }

    pendingMode?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingMode = null },
            title = { Text("切换计时模式？") },
            text = { Text("切换会清空这次还没结束的进度，已经记录到统计里的专注不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.setTimerMode(target)
                        pendingMode = null
                    }
                ) { Text("切换") }
            },
            dismissButton = {
                TextButton(onClick = { pendingMode = null }) { Text("取消") }
            }
        )
    }
}

/** 开始前选择这次要专注的事：可挑一条待办，也可以自己写 */
@Composable
private fun FocusPickerDialog(
    tasks: List<TaskItem>,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这次要做什么？") },
        text = {
            Column {
                if (tasks.isEmpty()) {
                    Text(
                        text = "待办里还没有未完成的事",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "从待办里选一个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                        items(tasks, key = { it.id }) { task ->
                            PickRow(
                                title = task.title,
                                selected = text == task.title,
                                onClick = { text = task.title }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "不在清单里？自己写一个",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("比如：读《人类简史》30 页") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("开始")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun PickRow(title: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(
                    if (selected) Modifier.background(MaterialTheme.colorScheme.primary)
                    else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 无状态的计时界面，方便在 Android Studio 里直接预览 */
@Composable
fun TimerContent(
    timer: TimerState,
    onSelectMode: (TimerMode) -> Unit,
    onSelectMinutes: (Int) -> Unit,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit,
    notifyDenied: Boolean = false,
    onOpenNotifySettings: () -> Unit = {}
) {
    val targetMillis = timer.targetMinutes * 60_000L
    val remainingMillis = max(targetMillis - timer.elapsedMillis, 0L)
    val displayMillis = if (timer.mode == TimerMode.Countdown) remainingMillis else timer.elapsedMillis
    val progress = if (timer.mode == TimerMode.Countdown) {
        if (targetMillis <= 0L) 0f
        else (1f - timer.elapsedMillis.toFloat() / targetMillis).coerceIn(0f, 1f)
    } else {
        ((timer.elapsedMillis % 60_000L).toFloat() / 60_000f).coerceIn(0f, 1f)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ScreenHeader(
                title = "计时",
                subtitle = "读书、专注，把时间花在重要的事上"
            )
        }
        item {
            ModeRow(mode = timer.mode, onSelect = onSelectMode, enabled = !timer.running)
        }
        item {
            TimerRing(
                progress = progress,
                timeText = formatClock(displayMillis),
                caption = timerCaption(timer)
            )
        }
        if (timer.mode == TimerMode.Countdown) {
            item {
                PresetRow(timer = timer, onSelect = onSelectMinutes)
            }
        }
        item {
            ControlButtons(
                timer = timer,
                onToggle = onToggle,
                onReset = onReset,
                onFinish = onFinish
            )
        }
        if (notifyDenied) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                ) {
                    Row(
                        modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "通知权限没开启：计时照常运行，但通知栏看不到倒计时",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onOpenNotifySettings) { Text("去开启") }
                    }
                }
            }
        }
        item {
            Text(
                text = if (timer.mode == TimerMode.Countdown) {
                    "倒计时结束会自动记录一次专注，可在「统计」里查看"
                } else {
                    "点「完成并记录」保存本次正计时，重置不会记录"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeRow(mode: TimerMode, onSelect: (TimerMode) -> Unit, enabled: Boolean) {
    val options = listOf(TimerMode.Countdown to "倒计时", TimerMode.Stopwatch to "正计时")
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = mode == option.first,
                onClick = { onSelect(option.first) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                label = { Text(option.second) }
            )
        }
    }
}

private val presetMinutes = listOf(5, 15, 25, 30, 45, 60)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PresetRow(timer: TimerState, onSelect: (Int) -> Unit) {
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 已经计时（暂停或完成）时先要求重置，避免切换时长把进度静默清空
        val canChangeDuration = !timer.running && timer.elapsedMillis == 0L
        presetMinutes.forEach { minutes ->
            FilterChip(
                selected = timer.targetMinutes == minutes,
                onClick = { onSelect(minutes) },
                enabled = canChangeDuration,
                label = { Text("$minutes 分") }
            )
        }
        FilterChip(
            selected = timer.targetMinutes !in presetMinutes,
            onClick = {
                customText = timer.targetMinutes.toString()
                showCustom = true
            },
            enabled = canChangeDuration,
            label = { Text("自定义") }
        )
    }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义倒计时") },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input ->
                        if (input.length <= 4 && input.all { it.isDigit() }) customText = input
                    },
                    label = { Text("分钟数") },
                    supportingText = { Text("可输入 1 ~ 600 分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes != null && minutes in 1..600) {
                            onSelect(minutes)
                            showCustom = false
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustom = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun TimerRing(progress: Float, timeText: String, caption: String) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.primary

    Box(modifier = Modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 14.dp.toPx()
            val inset = strokeWidth / 2
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ControlButtons(
    timer: TimerState,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    onFinish: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onReset,
                modifier = Modifier.weight(1f),
                enabled = timer.elapsedMillis > 0L || timer.running,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text("重置")
            }
            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1.5f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(
                    painter = painterResource(if (timer.running) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (timer.running) "暂停" else "开始")
            }
        }
        if (timer.mode == TimerMode.Stopwatch) {
            OutlinedButton(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth(),
                enabled = timer.elapsedMillis >= 1000L,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("完成并记录")
            }
        }
    }
}

private fun timerCaption(timer: TimerState): String {
    val raw = timer.label.trim()
    val tag = if (raw.length > 12) raw.take(12) + "…" else raw
    fun withTag(text: String) = if (tag.isEmpty()) text else "$tag · $text"
    return when {
        timer.mode == TimerMode.Countdown && timer.running -> withTag("专注中…")
        timer.mode == TimerMode.Countdown &&
            timer.elapsedMillis >= timer.targetMinutes * 60_000L -> withTag("已完成")
        timer.mode == TimerMode.Countdown && timer.elapsedMillis > 0L -> withTag("已暂停")
        timer.mode == TimerMode.Countdown -> "${timer.targetMinutes} 分钟专注"
        timer.running -> withTag("正计时中…")
        timer.elapsedMillis > 0L -> withTag("已暂停")
        else -> "准备好就开始"
    }
}

/** Android 13+ 没有通知权限时，前台服务仍在跑，但通知栏不会显示大字号计时 */
private fun isNotifyPermissionMissing(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED

internal fun formatClock(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.CHINA, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.CHINA, "%02d:%02d", minutes, seconds)
    }
}


