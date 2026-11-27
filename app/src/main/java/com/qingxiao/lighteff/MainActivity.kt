package com.qingxiao.lighteff

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingxiao.lighteff.data.AppState
import com.qingxiao.lighteff.data.TimerMode
import com.qingxiao.lighteff.ui.CountdownScreen
import com.qingxiao.lighteff.ui.FocusLockScreen
import com.qingxiao.lighteff.ui.HabitsScreen
import com.qingxiao.lighteff.ui.LocalToday
import com.qingxiao.lighteff.ui.StatsScreen
import com.qingxiao.lighteff.ui.TimerScreen
import com.qingxiao.lighteff.ui.TodoScreen
import com.qingxiao.lighteff.ui.rememberToday
import com.qingxiao.lighteff.ui.theme.LightEffTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LightEffTheme {
                val appViewModel: AppViewModel = viewModel()
                val state by appViewModel.state.collectAsStateWithLifecycle()
                val cloud by appViewModel.cloudState.collectAsStateWithLifecycle()
                val timer by appViewModel.timer.collectAsStateWithLifecycle()
                val focusLock by appViewModel.focusLock.collectAsStateWithLifecycle()
                val taskPrompt by appViewModel.taskPrompt.collectAsStateWithLifecycle()
                LightEffApp(
                    state = state,
                    cloud = cloud,
                    vm = appViewModel,
                    timer = timer,
                    focusLock = focusLock,
                    taskPrompt = taskPrompt
                )
            }
        }
    }
}

private data class TabItem(val label: String, val iconRes: Int, val accent: Boolean = false)

// 计时放在正中间，并作为带颜色的强调按钮
private val tabs = listOf(
    TabItem("待办", R.drawable.ic_tab_todo),
    TabItem("倒计日", R.drawable.ic_calendar),
    TabItem("计时", R.drawable.ic_tab_countdown, accent = true),
    TabItem("打卡", R.drawable.ic_tab_habit),
    TabItem("统计", R.drawable.ic_tab_stats)
)

@Composable
private fun LightEffApp(
    state: AppState,
    cloud: CloudUiState,
    vm: AppViewModel,
    timer: TimerState,
    focusLock: Boolean,
    taskPrompt: TimerTaskPrompt?
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()
    val today = rememberToday()

    // 退到后台时记一次计时进度，万一进程被清理也能停在离开时的位置
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 只是转屏导致的销毁重建不算离开 App，不记分心
                Lifecycle.Event.ON_STOP -> {
                    FocusGuard.appVisible = false
                    if ((context as? Activity)?.isChangingConfigurations != true) vm.onAppStopped()
                }
                // 回到前台时补传上次没同步成功的改动
                Lifecycle.Event.ON_START -> {
                    FocusGuard.appVisible = true
                    vm.retryPendingCloudUpload()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    CompositionLocalProvider(LocalToday provides today) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    LightEffBottomBar(selectedTab = selectedTab, onSelect = { selectedTab = it })
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    stateHolder.SaveableStateProvider(selectedTab) {
                        when (selectedTab) {
                            0 -> TodoScreen(
                                tasks = state.tasks,
                                onAdd = vm::addTask,
                                onToggle = vm::toggleTask,
                                onDelete = vm::deleteTask
                            )

                            1 -> CountdownScreen(
                                items = state.countdowns,
                                onAdd = vm::addCountdown,
                                onDelete = vm::deleteCountdown
                            )

                            2 -> TimerScreen(viewModel = vm)

                            3 -> HabitsScreen(
                                habits = state.habits,
                                onAdd = vm::addHabit,
                                onToggleToday = vm::toggleHabitToday,
                                onDelete = vm::deleteHabit
                            )

                            else -> StatsScreen(
                                tasks = state.tasks,
                                habits = state.habits,
                                sessions = state.sessions,
                                taskRecords = state.taskRecords,
                                distractions = state.distractions,
                                cloud = cloud,
                                onBackupExport = vm::encodeBackup,
                                onBackupImport = vm::applyBackup,
                                onCloudLogin = vm::cloudLogin,
                                onCloudSendReset = vm::cloudSendReset,
                                onCloudLogout = vm::cloudLogout,
                                onCloudUpload = vm::cloudUploadNow,
                                onCloudRestore = vm::cloudRestore,
                                onCloudMessageShown = vm::cloudDismissMessage
                            )
                        }
                    }
                }
            }

            // 计时期间的全屏专注页，盖住底部标签和系统栏
            if (focusLock && timer.running) {
                FocusLockScreen(
                    timer = timer,
                    onEmergencyExit = vm::dismissFocusLock,
                    onWhitelistChanged = vm::notifyWhitelistChanged,
                    onEnableGuard = { vm.setFocusGuardEnabled(true) }
                )
            }

            // 计时结束后的完成确认：不管当前停在哪个标签页都要弹出来
            taskPrompt?.let { prompt ->
                TimerTaskDoneDialog(
                    prompt = prompt,
                    onAnswer = vm::resolveTimerTaskOutcome,
                    onDismiss = vm::dismissTimerTaskPrompt
                )
            }
        }
    }
}

@Composable
private fun TimerTaskDoneDialog(
    prompt: TimerTaskPrompt,
    onAnswer: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (prompt.label.isBlank()) "这次任务完成了吗？"
                else "「${prompt.label}」完成了吗？"
            )
        },
        text = {
            Column {
                Text(
                    text = if (prompt.mode == TimerMode.Countdown) {
                        "倒计时部分已记入统计，超时时间还会继续累计。"
                    } else {
                        "本次计时已记入统计。"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "选「完成了」会同步勾选待办；选「还没完成」会继续留在待办里，自己写的事项会自动加进待办。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text("完成了") }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text("还没完成") }
        }
    )
}

@Composable
private fun LightEffBottomBar(selectedTab: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, tab ->
                if (tab.accent) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onSelect(index) }
                    ) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(y = (-16).dp)
                                .size(52.dp)
                                .shadow(6.dp, CircleShape)
                                .clip(CircleShape)
                                .background(if (selectedTab == index) colors.primary else colors.primary.copy(alpha = 0.85f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = tab.label,
                                tint = colors.onPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = tab.label,
                            color = colors.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 6.dp)
                        )
                    }
                } else {
                    val active = selectedTab == index
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelect(index) }
                            .padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            painter = painterResource(tab.iconRes),
                            contentDescription = tab.label,
                            tint = if (active) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            color = if (active) colors.primary else colors.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}
