package com.qingxiao.lighteff.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qingxiao.lighteff.CloudUiState
import com.qingxiao.lighteff.TimerState
import com.qingxiao.lighteff.data.CountdownItem
import com.qingxiao.lighteff.data.HabitItem
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.data.TimerMode
import com.qingxiao.lighteff.data.TimerSession
import com.qingxiao.lighteff.ui.theme.LightEffTheme
import java.time.LocalDate

private val previewNow = System.currentTimeMillis()
private val previewToday = LocalDate.now()

private val previewTasks = listOf(
    TaskItem("t1", "读完《人类简史》第三章", priority = 2, createdAt = previewNow),
    TaskItem(
        "t2",
        "整理这周的读书笔记",
        priority = 1,
        done = true,
        createdAt = previewNow - 86_400_000L,
        completedAt = previewNow - 3_600_000L
    ),
    TaskItem("t3", "把想读的书加入书单", priority = 0, createdAt = previewNow)
)

private val previewCountdowns = listOf(
    CountdownItem("c1", "约定的旅行", previewToday.plusDays(12).toEpochDay()),
    CountdownItem("c2", "朋友生日", previewToday.plusDays(45).toEpochDay()),
    CountdownItem("c3", "交稿日", previewToday.minusDays(5).toEpochDay())
)

private val previewHabits = listOf(
    HabitItem(
        "h1",
        "每天阅读 30 分钟",
        checkedDates = setOf(
            previewToday.toString(),
            previewToday.minusDays(1).toString(),
            previewToday.minusDays(3).toString(),
            previewToday.minusDays(4).toString()
        )
    ),
    HabitItem(
        "h2",
        "早起",
        checkedDates = setOf(previewToday.minusDays(2).toString())
    )
)

private val previewSessions = listOf(
    TimerSession("s1", TimerMode.Countdown, 25 * 60, previewNow - 2 * 3_600_000L),
    TimerSession("s2", TimerMode.Stopwatch, 18 * 60, previewNow - 26 * 3_600_000L),
    TimerSession("s3", TimerMode.Countdown, 45 * 60, previewNow - 50 * 3_600_000L),
    TimerSession("s4", TimerMode.Countdown, 30 * 60, previewNow - 74 * 3_600_000L)
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    LightEffTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            content()
        }
    }
}

@Preview(name = "待办", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun TodoPreview() {
    PreviewSurface {
        TodoScreen(tasks = previewTasks, onAdd = { _, _, _ -> }, onToggle = {}, onDelete = {})
    }
}

@Preview(name = "计时-倒计时", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun TimerCountdownPreview() {
    PreviewSurface {
        TimerContent(
            timer = TimerState(
                mode = TimerMode.Countdown,
                targetMinutes = 25,
                elapsedMillis = 8 * 60_000L,
                running = true
            ),
            onSelectMode = {},
            onSelectMinutes = {},
            onToggle = {},
            onReset = {},
            onFinish = {}
        )
    }
}

@Preview(name = "计时-正计时", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun TimerStopwatchPreview() {
    PreviewSurface {
        TimerContent(
            timer = TimerState(
                mode = TimerMode.Stopwatch,
                elapsedMillis = 22 * 60_000L + 35_000L,
                running = true
            ),
            onSelectMode = {},
            onSelectMinutes = {},
            onToggle = {},
            onReset = {},
            onFinish = {}
        )
    }
}

@Preview(name = "倒计日", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun CountdownPreview() {
    PreviewSurface {
        CountdownScreen(items = previewCountdowns, onAdd = { _, _, _ -> }, onDelete = {})
    }
}

@Preview(name = "打卡", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun HabitsPreview() {
    PreviewSurface {
        HabitsScreen(habits = previewHabits, onAdd = {}, onToggleToday = {}, onDelete = {})
    }
}

@Preview(name = "统计", showBackground = true, widthDp = 400, heightDp = 900)
@Composable
private fun StatsPreview() {
    PreviewSurface {
        StatsScreen(
            tasks = previewTasks,
            habits = previewHabits,
            sessions = previewSessions,
            taskRecords = emptyList(),
            distractions = emptyList(),
            cloud = CloudUiState(),
            onBackupExport = { "" },
            onBackupImport = { false },
            onCloudLogin = { _, _, _ -> },
            onCloudSendReset = {},
            onCloudLogout = {},
            onCloudUpload = {},
            onCloudRestore = {},
            onCloudMessageShown = {}
        )
    }
}
