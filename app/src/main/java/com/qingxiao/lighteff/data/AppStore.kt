package com.qingxiao.lighteff.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 使用一个本地 JSON 文件保存全部数据，数据量很小，读写开销可以忽略 */
class AppStore(filesDir: File) {

    private val dataFile = File(filesDir, "lighteff_state.json")

    /** 旧版本把计时状态单独存一个文件，升级后迁移进主文件 */
    private val legacyTimerFile = File(filesDir, "lighteff_timer.json")

    fun load(): AppState {
        val state = if (dataFile.exists()) {
            runCatching { decode(dataFile.readText()) }.getOrNull()
        } else {
            null
        }
        // 主文件解析失败时留一份副本，避免下一次保存把原始数据彻底覆盖掉
        if (state == null && dataFile.exists()) {
            runCatching {
                dataFile.copyTo(File(dataFile.parentFile, "lighteff_state.broken.json"), overwrite = true)
            }
        }
        val legacyTimer = if (state?.timerSnapshot == null) loadLegacyTimer() else null
        if (legacyTimer != null) runCatching { legacyTimerFile.delete() }
        return (state ?: AppState()).copy(timerSnapshot = legacyTimer)
    }

    fun save(state: AppState) {
        runCatching { writeAtomic(dataFile, encode(state)) }
    }

    /** 导出备份用：把当前数据序列化成 JSON 文本 */
    fun encode(state: AppState): String {
        val root = JSONObject()
        root.put("tasks", JSONArray().apply {
            state.tasks.forEach { put(it.toJson()) }
        })
        root.put("countdowns", JSONArray().apply {
            state.countdowns.forEach { put(it.toJson()) }
        })
        root.put("habits", JSONArray().apply {
            state.habits.forEach { put(it.toJson()) }
        })
        root.put("sessions", JSONArray().apply {
            state.sessions.forEach { put(it.toJson()) }
        })
        root.put("taskRecords", JSONArray().apply {
            state.taskRecords.forEach { put(it.toJson()) }
        })
        root.put("timer", state.timerSnapshot?.toJson() ?: JSONObject.NULL)
        return root.toString()
    }

    /** 解析主数据或备份文件，兼容旧版本数据 */
    fun decode(raw: String): AppState {
        val root = JSONObject(raw)
        val tasks = root.optJSONArray("tasks").toTasks()
        val records = root.optJSONArray("taskRecords").toTaskRecords().toMutableList()
        // 旧数据迁移：把已完成待办的完成时间补成独立记录，之后取消勾选/删除也不会丢失历史
        tasks.forEach { task ->
            val completedAt = task.completedAt ?: return@forEach
            if (records.none { it.taskId == task.id && it.completedAt == completedAt }) {
                records += TaskRecord("legacy-${task.id}-$completedAt", task.id, task.title, completedAt)
            }
        }
        return AppState(
            tasks = tasks,
            countdowns = root.optJSONArray("countdowns").toCountdowns(),
            habits = root.optJSONArray("habits").toHabits(),
            sessions = root.optJSONArray("sessions").toSessions(),
            taskRecords = records,
            timerSnapshot = root.optJSONObject("timer")?.toStoredTimer()
        )
    }

    private fun loadLegacyTimer(): StoredTimer? {
        if (!legacyTimerFile.exists()) return null
        return runCatching { JSONObject(legacyTimerFile.readText()).toStoredTimer() }.getOrNull()
    }

    /** 先写临时文件再替换，避免写到一半被杀进程导致文件损坏 */
    private fun writeAtomic(file: File, text: String) {
        val tmpFile = File(file.parentFile, "${file.name}.tmp")
        tmpFile.writeText(text)
        if (!tmpFile.renameTo(file)) {
            file.writeText(text)
            tmpFile.delete()
        }
    }
}

private fun TaskItem.toJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("priority", priority)
    put("done", done)
    put("createdAt", createdAt)
    put("completedAt", completedAt ?: JSONObject.NULL)
}

private fun CountdownItem.toJson() = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("epochDay", epochDay)
    put("repeatYearly", repeatYearly)
}

private fun HabitItem.toJson() = JSONObject().apply {
    put("id", id)
    put("name", name)
    put("createdAt", createdAt)
    put("checkedDates", JSONArray().apply { checkedDates.forEach { put(it) } })
    put("archived", archived)
}

private fun TimerSession.toJson() = JSONObject().apply {
    put("id", id)
    put("mode", mode.name)
    put("seconds", seconds)
    put("completedAt", completedAt)
    put("label", label)
}

private fun TaskRecord.toJson() = JSONObject().apply {
    put("id", id)
    put("taskId", taskId)
    put("title", title)
    put("completedAt", completedAt)
}

private fun StoredTimer.toJson() = JSONObject().apply {
    put("mode", mode.name)
    put("targetMinutes", targetMinutes)
    put("elapsedMillis", elapsedMillis)
    put("lastElapsedMillis", lastElapsedMillis)
    put("running", running)
    put("anchorWallMillis", anchorWallMillis)
    put("label", label)
}

private fun JSONObject.toStoredTimer(): StoredTimer = StoredTimer(
    mode = runCatching { TimerMode.valueOf(optString("mode")) }
        .getOrDefault(TimerMode.Countdown),
    targetMinutes = optInt("targetMinutes", 25).coerceIn(1, 600),
    elapsedMillis = optLong("elapsedMillis", 0L).coerceAtLeast(0L),
    lastElapsedMillis = optLong("lastElapsedMillis", optLong("elapsedMillis", 0L)).coerceAtLeast(0L),
    running = optBoolean("running", false),
    anchorWallMillis = optLong("anchorWallMillis", 0L),
    label = optString("label")
)

private fun JSONArray?.toTasks(): List<TaskItem> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val title = obj.optString("title")
            if (id.isEmpty() || title.isEmpty()) continue
            val completedAt = if (obj.has("completedAt") && !obj.isNull("completedAt")) {
                obj.optLong("completedAt")
            } else {
                null
            }
            add(
                TaskItem(
                    id = id,
                    title = title,
                    priority = obj.optInt("priority", 1),
                    done = obj.optBoolean("done", false),
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    completedAt = completedAt
                )
            )
        }
    }
}

private fun JSONArray?.toCountdowns(): List<CountdownItem> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val title = obj.optString("title")
            if (id.isEmpty() || title.isEmpty()) continue
            add(
                CountdownItem(
                    id = id,
                    title = title,
                    epochDay = obj.optLong("epochDay", 0L),
                    repeatYearly = obj.optBoolean("repeatYearly", false)
                )
            )
        }
    }
}

private fun JSONArray?.toHabits(): List<HabitItem> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val name = obj.optString("name")
            if (id.isEmpty() || name.isEmpty()) continue
            val dates = mutableSetOf<String>()
            val array = obj.optJSONArray("checkedDates")
            if (array != null) {
                for (j in 0 until array.length()) {
                    val day = array.optString(j)
                    if (day.isNotEmpty()) dates.add(day)
                }
            }
            add(
                HabitItem(
                    id = id,
                    name = name,
                    createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                    checkedDates = dates,
                    archived = obj.optBoolean("archived", false)
                )
            )
        }
    }
}

private fun JSONArray?.toSessions(): List<TimerSession> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val seconds = obj.optInt("seconds", 0)
            if (id.isEmpty() || seconds <= 0) continue
            val mode = runCatching { TimerMode.valueOf(obj.optString("mode")) }
                .getOrDefault(TimerMode.Countdown)
            add(
                TimerSession(
                    id = id,
                    mode = mode,
                    seconds = seconds,
                    completedAt = obj.optLong("completedAt", System.currentTimeMillis()),
                    label = obj.optString("label")
                )
            )
        }
    }
}

private fun JSONArray?.toTaskRecords(): List<TaskRecord> {
    if (this == null) return emptyList()
    return buildList {
        for (i in 0 until length()) {
            val obj = optJSONObject(i) ?: continue
            val id = obj.optString("id")
            val taskId = obj.optString("taskId")
            val completedAt = obj.optLong("completedAt", 0L)
            if (id.isEmpty() || taskId.isEmpty() || completedAt <= 0L) continue
            add(
                TaskRecord(
                    id = id,
                    taskId = taskId,
                    title = obj.optString("title"),
                    completedAt = completedAt
                )
            )
        }
    }
}
