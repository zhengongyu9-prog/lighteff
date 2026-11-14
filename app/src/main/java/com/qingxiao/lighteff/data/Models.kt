package com.qingxiao.lighteff.data

/** 单条待办事项。priority: 0 低 / 1 中 / 2 高；slot: 0 不限 / 1 早 / 2 中 / 3 晚 */
data class TaskItem(
    val id: String,
    val title: String,
    val priority: Int = 1,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val slot: Int = 0
)

/** 重要日子的倒计日，使用 epochDay 存储目标日期，避免时区偏移问题 */
data class CountdownItem(
    val id: String,
    val title: String,
    val epochDay: Long,
    /** 每年重复（生日这类），显示时自动滚动到下一次发生的日期 */
    val repeatYearly: Boolean = false
)

/** 待办完成记录，独立于待办本体，删除或取消勾选待办都不会抹掉历史 */
data class TaskRecord(
    val id: String,
    val taskId: String,
    val title: String,
    val completedAt: Long
)

/** 计时状态的持久化快照，用于进程被杀后恢复 */
data class StoredTimer(
    val mode: TimerMode,
    val targetMinutes: Int,
    val elapsedMillis: Long,
    /** 保存时屏幕上的真实进度，正计时被杀后按它暂停，不把离开期间算进去 */
    val lastElapsedMillis: Long,
    val running: Boolean,
    /** 本轮开始时的墙上时间，running 时用它补上进程被杀期间走过的时间 */
    val anchorWallMillis: Long,
    val label: String
)

/** 计时模式：倒计时 / 正计时 */
enum class TimerMode { Countdown, Stopwatch }

/** 一次完成的计时记录，label 是这次专注做的事 */
data class TimerSession(
    val id: String,
    val mode: TimerMode,
    val seconds: Int,
    val completedAt: Long,
    val label: String = ""
)

/** 习惯打卡，checkedDates 保存 yyyy-MM-dd 字符串集合 */
data class HabitItem(
    val id: String,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val checkedDates: Set<String> = emptySet(),
    /** 已删除的习惯只从列表隐藏，打卡历史继续保留在统计里 */
    val archived: Boolean = false
)

data class AppState(
    val tasks: List<TaskItem> = emptyList(),
    val countdowns: List<CountdownItem> = emptyList(),
    val habits: List<HabitItem> = emptyList(),
    val sessions: List<TimerSession> = emptyList(),
    val taskRecords: List<TaskRecord> = emptyList(),
    /** 计时进行中离开 App 的时间点，用来统计分心次数 */
    val distractions: List<Long> = emptyList(),
    /** 计时状态和主数据存在同一个文件里，一次写盘完成 */
    val timerSnapshot: StoredTimer? = null
)
