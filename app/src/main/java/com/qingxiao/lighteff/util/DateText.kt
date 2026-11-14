package com.qingxiao.lighteff.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val fullDateFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)

fun LocalDate.formatFull(): String = format(fullDateFormatter)

fun LocalDate.weekdayCn(): String = when (dayOfWeek.value) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}

fun LocalDate.weekdayShortCn(): String = when (dayOfWeek.value) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    else -> "日"
}

fun todayLabel(today: LocalDate = LocalDate.now()): String {
    return "${today.monthValue}月${today.dayOfMonth}日 · ${today.weekdayCn()}"
}

fun createdLabel(createdAt: Long): String {
    val date = Instant.ofEpochMilli(createdAt).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (date) {
        today -> "今天创建"
        today.minusDays(1) -> "昨天创建"
        else -> "${date.monthValue}月${date.dayOfMonth}日创建"
    }
}

/** 把秒数格式化为「1小时20分 / 2小时 / 25分钟 / 30秒」 */
fun formatDurationCn(seconds: Int): String = when {
    seconds >= 3600 -> {
        val minutes = (seconds % 3600) / 60
        if (minutes == 0) "${seconds / 3600}小时" else "${seconds / 3600}小时${minutes}分"
    }
    seconds >= 60 -> "${seconds / 60}分钟"
    seconds > 0 -> "${seconds}秒"
    else -> "0分钟"
}

/** 时间戳格式化为「今天 18:30 / 昨天 09:05 / 11月10日 21:00」 */
fun momentLabel(timestamp: Long): String {
    val moment = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
    val date = moment.toLocalDate()
    val time = String.format(Locale.CHINA, "%02d:%02d", moment.hour, moment.minute)
    return when (date) {
        LocalDate.now() -> "今天 $time"
        LocalDate.now().minusDays(1) -> "昨天 $time"
        else -> "${date.monthValue}月${date.dayOfMonth}日 $time"
    }
}

fun dateLabel(date: LocalDate): String = when (date) {
    LocalDate.now() -> "今天"
    LocalDate.now().minusDays(1) -> "昨天"
    else -> "${date.monthValue}月${date.dayOfMonth}日"
}

/** 以今天（或昨天）为终点计算连续打卡天数 */
fun habitStreak(checkedDates: Set<String>, today: LocalDate = LocalDate.now()): Int {
    var cursor = if (today.toString() in checkedDates) today else today.minusDays(1)
    var count = 0
    while (cursor.toString() in checkedDates) {
        count++
        cursor = cursor.minusDays(1)
    }
    return count
}
