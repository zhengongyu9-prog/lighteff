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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.qingxiao.lighteff.FocusGuard
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.TimerState
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.data.TimerMode
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun TimerScreen(viewModel: AppViewModel) {
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val appState by viewModel.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    var showPicker by rememberSaveable { mutableStateOf(false) }
    var showWhitelist by remember { mutableStateOf(false) }
    val whitelistVersion by viewModel.whitelistVersion.collectAsStateWithLifecycle()
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
            // 「任务是否完成」的弹窗由 ViewModel 统一发出，切到别的页面也能弹
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
            viewModel.finishTimer()
            Toast.makeText(context, "本次专注已记录", Toast.LENGTH_SHORT).show()
        },
        notifyDenied = notifyDenied,
        onGuardChange = viewModel::setFocusGuardEnabled,
        whitelistVersion = whitelistVersion,
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
            whitelistCount = FocusGuard.whitelist(context).size,
            onOpenWhitelist = { showWhitelist = true },
            onDismiss = { showPicker = false },
            onConfirm = { label, taskId ->
                showPicker = false
                viewModel.startWithLabel(label, taskId)
            }
        )
    }

    if (showWhitelist) {
        WhitelistDialog(
            selected = FocusGuard.whitelist(context),
            onDismiss = { showWhitelist = false },
            onConfirm = { packages ->
                FocusGuard.setWhitelist(context, packages)
                viewModel.notifyWhitelistChanged()
                showWhitelist = false
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
    whitelistCount: Int,
    onOpenWhitelist: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String, String?) -> Unit
) {
    var text by rememberSaveable { mutableStateOf("") }
    // 选中待办时记住 id，结束时才能准确同步那一条；手动改文字后就当成自建事项
    var pickedTaskId by rememberSaveable { mutableStateOf<String?>(null) }

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
                                selected = pickedTaskId == task.id,
                                onClick = {
                                    text = task.title
                                    pickedTaskId = task.id
                                }
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
                    onValueChange = {
                        text = it
                        pickedTaskId = null
                    },
                    placeholder = { Text("比如：读《人类简史》30 页") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                // 开始前就能直接设置白名单，不用去下面的卡片里找
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                        .clickable(onClick = onOpenWhitelist)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "学霸模式白名单",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = if (whitelistCount == 0) "未选择，点此设置" else "$whitelistCount 个应用 ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text, pickedTaskId) }) {
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
    onOpenNotifySettings: () -> Unit = {},
    onGuardChange: (Boolean) -> Unit = {},
    whitelistVersion: Int = 0
) {
    val targetMillis = timer.targetMinutes * 60_000L
    val overtimeMillis = (timer.elapsedMillis - targetMillis).coerceAtLeast(0L)
    // 倒计时走完后继续按正计时显示超时时间
    val runningOvertime = timer.mode == TimerMode.Countdown && timer.completed
    val displayMillis = when {
        runningOvertime -> overtimeMillis
        timer.mode == TimerMode.Countdown -> max(targetMillis - timer.elapsedMillis, 0L)
        else -> timer.elapsedMillis
    }
    val timeText = if (runningOvertime) "+${formatClock(displayMillis)}" else formatClock(displayMillis)
    val progress = if (timer.mode == TimerMode.Countdown) {
        if (runningOvertime || targetMillis <= 0L) 0f
        else (1f - timer.elapsedMillis.toFloat() / targetMillis).coerceIn(0f, 1f)
    } else {
        ((timer.elapsedMillis % 60_000L).toFloat() / 60_000f).coerceIn(0f, 1f)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ScreenHeader(
            title = "计时",
            subtitle = "读书、专注，把时间花在重要的事上"
        )
        ModeRow(mode = timer.mode, onSelect = onSelectMode, enabled = !timer.running)
        // 圆环区域占据标题和下方控件之间的剩余高度，让计时主体在屏幕中间
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val ringSize = minOf(maxWidth * 0.72f, maxHeight * 0.95f, 300.dp)
                TimerRing(
                    progress = progress,
                    timeText = timeText,
                    caption = timerCaption(timer),
                    modifier = Modifier.size(ringSize)
                )
            }
        }
        if (timer.mode == TimerMode.Countdown) {
            DurationPicker(timer = timer, onSelect = onSelectMinutes)
        }
        ControlButtons(
            timer = timer,
            onToggle = onToggle,
            onReset = onReset,
            onFinish = onFinish
        )
        FocusGuardCard(refreshKey = whitelistVersion, onEnabledChange = onGuardChange)
        if (notifyDenied) {
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
        Text(
            text = if (timer.mode == TimerMode.Countdown) {
                "倒计时结束会自动记录一次专注；提前点「结束」也会记下实际专注的时长"
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

/** 滑杆上的刻度数字，点一下直接跳到这个分钟数 */
private val durationMarks = listOf(5, 15, 25, 30, 45, 60)

private const val MAX_MINUTES = 60

/** 拖动滑杆最多 60 分钟，自定义可以输入到 600 分钟 */
private const val MAX_CUSTOM_MINUTES = 600

/** 时长选择：滑杆和刻度数字合成一个控件，1 ~ 60 分钟随便拖，点数字直接跳过去 */
@Composable
private fun DurationPicker(timer: TimerState, onSelect: (Int) -> Unit) {
    val enabled = !timer.running && timer.elapsedMillis == 0L
    val current = timer.targetMinutes.coerceIn(1, MAX_MINUTES)
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    var dragging by remember(current) { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var customText by rememberSaveable { mutableStateOf("") }

    fun apply(minutes: Int) {
        if (!enabled) return
        val clamped = minutes.coerceIn(1, MAX_CUSTOM_MINUTES)
        // 滑杆只显示到 60，自定义超过 60 时滑块停在最右边
        value = clamped.coerceAtMost(MAX_MINUTES).toFloat()
        onSelect(clamped)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "时长",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${if (dragging) value.roundToInt() else timer.targetMinutes} 分钟",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "自定义",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) {
                            customText = timer.targetMinutes.toString()
                            showCustom = true
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = {
                if (enabled) {
                    dragging = true
                    value = it
                }
            },
            // 拖动过程中只改显示，松手才真正应用，避免每动一下都写盘
            onValueChangeFinished = {
                dragging = false
                apply(value.roundToInt())
            },
            valueRange = 1f..MAX_MINUTES.toFloat(),
            steps = MAX_MINUTES - 2,
            enabled = enabled
        )
        // 刻度数字按滑杆的比例摆放，点数字等于把滑块拖到那个位置
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        ) {
            val inset = 10.dp
            val usable = maxWidth - inset * 2
            durationMarks.forEach { minutes ->
                val fraction = (minutes - 1f) / (MAX_MINUTES - 1)
                val centerX = inset + usable * fraction
                val selected = value.roundToInt() == minutes
                Text(
                    text = "$minutes",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        selected -> MaterialTheme.colorScheme.primary
                        enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.outline
                    },
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(x = centerX - 16.dp)
                        .width(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled) { apply(minutes) }
                        .padding(vertical = 4.dp)
                )
            }
        }
    }

    if (showCustom) {
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义时长") },
            text = {
                OutlinedTextField(
                    value = customText,
                    onValueChange = { input ->
                        if (input.length <= 3 && input.all { it.isDigit() }) customText = input
                    },
                    label = { Text("分钟数") },
                    supportingText = { Text("可输入 1 ~ $MAX_CUSTOM_MINUTES 分钟") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val minutes = customText.toIntOrNull()
                        if (minutes != null && minutes in 1..MAX_CUSTOM_MINUTES) {
                            apply(minutes)
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
private fun TimerRing(
    progress: Float,
    timeText: String,
    caption: String,
    modifier: Modifier = Modifier
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val ringColor = MaterialTheme.colorScheme.primary

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 描边和字号跟着圆环直径缩放，避免放大后变细、文字变挤
        val strokeWidth = maxWidth * 0.085f
        val baseFont = (maxWidth.value * 0.24f).sp
        val timeFontSize = if (timeText.length > 5) baseFont * 0.72f else baseFont
        val captionFontSize = (maxWidth.value * 0.068f).coerceIn(12f, 16f).sp

        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = strokeWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = timeText,
                fontSize = timeFontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = caption,
                fontSize = captionFontSize,
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)
            ) {
                Text("重置")
            }
            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1.4f),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)
            ) {
                Icon(
                    painter = painterResource(if (timer.running) R.drawable.ic_pause else R.drawable.ic_play),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (timer.running) "暂停" else "开始")
            }
            // 倒计时也要能主动收尾：结束这一轮并问任务完成没有
            if (timer.mode == TimerMode.Countdown) {
                OutlinedButton(
                    onClick = onFinish,
                    modifier = Modifier.weight(1.1f),
                    enabled = timer.elapsedMillis > 0L,
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp)
                ) {
                    Text("结束")
                }
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
        timer.completed -> withTag("已完成 · 超时计时")
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


