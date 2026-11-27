package com.qingxiao.lighteff

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingxiao.lighteff.data.AppState
import com.qingxiao.lighteff.data.AppStore
import com.qingxiao.lighteff.data.CloudResult
import com.qingxiao.lighteff.data.CloudSync
import com.qingxiao.lighteff.data.CountdownItem
import com.qingxiao.lighteff.data.HabitItem
import com.qingxiao.lighteff.data.StoredTimer
import com.qingxiao.lighteff.data.TaskItem
import com.qingxiao.lighteff.data.TaskRecord
import com.qingxiao.lighteff.data.TimerMode
import com.qingxiao.lighteff.data.TimerSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** 计时页的实时状态，放在 ViewModel 中，切换页面也不会中断 */
data class TimerState(
    val mode: TimerMode = TimerMode.Countdown,
    val targetMinutes: Int = 25,
    val elapsedMillis: Long = 0L,
    val running: Boolean = false,
    val completionTick: Long = 0L,
    /** 倒计时已经走完，之后按正计时继续往上记超时时间 */
    val completed: Boolean = false,
    /** 倒计时结束后已经记进统计的超时毫秒数，避免暂停/重置时重复补记 */
    val recordedOvertimeMillis: Long = 0L,
    val label: String = "",
    /** 从待办里选的那一条；自己临时写的事项为 null */
    val taskId: String? = null
)

/** 计时结束后询问「任务是否完成」，用于同步待办 */
data class TimerTaskPrompt(
    val mode: TimerMode,
    val label: String,
    val taskId: String?
)

/** 账号与云同步的界面状态 */
data class CloudUiState(
    val loggedIn: Boolean = false,
    val email: String? = null,
    val busy: Boolean = false,
    val lastSyncAt: Long? = null,
    /** 云端和本机都有数据，等用户选择恢复还是覆盖 */
    val needsAttention: Boolean = false,
    val message: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val store = AppStore(application.filesDir)
    private val saveMutex = Mutex()
    private val cloudSync = CloudSync(application.applicationContext)
    /** 记录「还有改动没传上去」「云端和本机有冲突」，进程被杀后重开也不会丢 */
    private val syncPrefs = application.getSharedPreferences("lighteff_sync", Context.MODE_PRIVATE)
    /** 记住上次选的计时模式和时长，下次打开直接沿用 */
    private val timerPrefs = application.getSharedPreferences("lighteff_timer", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val _cloud = MutableStateFlow(
        CloudUiState(
            loggedIn = cloudSync.loggedIn,
            email = cloudSync.email,
            lastSyncAt = cloudSync.lastSyncAt,
            needsAttention = syncPrefs.getBoolean(KEY_NEEDS_ATTENTION, false)
        )
    )
    val cloudState: StateFlow<CloudUiState> = _cloud.asStateFlow()

    private val _timer = MutableStateFlow(TimerState())
    val timer: StateFlow<TimerState> = _timer.asStateFlow()

    /** 计时期间是否显示全屏专注页：点开始后进入，紧急退出或计时结束时关闭 */
    private val _focusLock = MutableStateFlow(false)
    val focusLock: StateFlow<Boolean> = _focusLock.asStateFlow()

    /** 计时结束后待确认「任务是否完成」的弹窗，放在 ViewModel 里，切到别的页面也能弹出 */
    private val _taskPrompt = MutableStateFlow<TimerTaskPrompt?>(null)
    val taskPrompt: StateFlow<TimerTaskPrompt?> = _taskPrompt.asStateFlow()

    /** 白名单变更计数：全屏专注页改完白名单后，让计时页的卡片同步刷新 */
    private val _whitelistVersion = MutableStateFlow(0)
    val whitelistVersion: StateFlow<Int> = _whitelistVersion.asStateFlow()

    fun notifyWhitelistChanged() {
        _whitelistVersion.value++
    }

    private var tickerJob: Job? = null
    private var accumulatedMillis: Long = 0L
    private var anchorRealtime: Long = 0L
    private var anchorWallMillis: Long = 0L

    init {
        applyLastTimerChoice()
        restoreTimer()
        // 冲突状态写进本地，重开 App 后仍然要先让用户选，不会静默覆盖云端
        viewModelScope.launch {
            _cloud.collect { state ->
                syncPrefs.edit()
                    .putBoolean(KEY_NEEDS_ATTENTION, state.needsAttention)
                    .apply()
            }
        }
    }

    private fun mutate(transform: (AppState) -> AppState) {
        val before = _state.value
        val after = transform(before)
        // 数据没变就不写盘、不上传，避免启动恢复计时时把旧状态又推一次云端
        if (after == before) return
        _state.value = after
        // 写入时统一取最新状态，避免连续操作时旧快照覆盖新数据
        viewModelScope.launch(Dispatchers.IO) {
            saveMutex.withLock { store.save(_state.value) }
        }
        scheduleCloudUpload()
    }

    // ---------- 待办 ----------

    fun addTask(
        title: String,
        priority: Int,
        slot: Int = 0,
        dueEpochDay: Long? = null,
        endEpochDay: Long? = null
    ) {
        val text = title.trim()
        if (text.isEmpty()) return
        val start = dueEpochDay
        // 阶段任务的结束日必须晚于开始日，否则按单日任务处理
        val end = endEpochDay?.takeIf { start != null && it > start }
        mutate { current ->
            current.copy(
                tasks = listOf(
                    TaskItem(
                        id = UUID.randomUUID().toString(),
                        title = text,
                        priority = priority,
                        slot = slot.coerceIn(0, 3),
                        dueEpochDay = start,
                        endEpochDay = end
                    )
                ) + current.tasks
            )
        }
    }

    fun toggleTask(id: String) {
        mutate { current ->
            val target = current.tasks.firstOrNull { it.id == id } ?: return@mutate current
            val nextDone = !target.done
            val now = System.currentTimeMillis()
            current.copy(
                tasks = current.tasks.map { task ->
                    if (task.id != id) task
                    else task.copy(done = nextDone, completedAt = if (nextDone) now else null)
                },
                // 完成记录单独保存，之后取消勾选或删除待办都不影响历史统计
                taskRecords = if (nextDone) {
                    current.taskRecords + TaskRecord(
                        id = UUID.randomUUID().toString(),
                        taskId = target.id,
                        title = target.title,
                        completedAt = now
                    )
                } else {
                    current.taskRecords
                }
            )
        }
    }

    fun deleteTask(id: String) {
        mutate { current -> current.copy(tasks = current.tasks.filterNot { it.id == id }) }
    }

    // ---------- 倒计日 ----------

    fun addCountdown(title: String, epochDay: Long, repeatYearly: Boolean = false) {
        val text = title.trim()
        if (text.isEmpty()) return
        mutate { current ->
            current.copy(
                countdowns = current.countdowns + CountdownItem(
                    UUID.randomUUID().toString(), text, epochDay, repeatYearly
                )
            )
        }
    }

    fun deleteCountdown(id: String) {
        mutate { current -> current.copy(countdowns = current.countdowns.filterNot { it.id == id }) }
    }

    // ---------- 打卡 ----------

    fun addHabit(name: String) {
        val text = name.trim()
        if (text.isEmpty()) return
        mutate { current ->
            current.copy(habits = current.habits + HabitItem(id = UUID.randomUUID().toString(), name = text))
        }
    }

    fun toggleHabitToday(id: String) {
        val today = LocalDate.now().toString()
        mutate { current ->
            current.copy(
                habits = current.habits.map { habit ->
                    if (habit.id != id) habit
                    else habit.copy(
                        checkedDates = if (today in habit.checkedDates) habit.checkedDates - today
                        else habit.checkedDates + today
                    )
                }
            )
        }
    }

    fun deleteHabit(id: String) {
        // 只做归档：列表里不再显示，但历史打卡仍保留在统计中
        mutate { current ->
            current.copy(
                habits = current.habits.map { if (it.id == id) it.copy(archived = true) else it }
            )
        }
    }

    // ---------- 计时 ----------

    fun setTimerMode(mode: TimerMode) {
        if (_timer.value.mode == mode) return
        stopTicker()
        flushOvertime()
        accumulatedMillis = 0L
        _timer.update {
            it.copy(
                mode = mode,
                elapsedMillis = 0L,
                running = false,
                completed = false,
                recordedOvertimeMillis = 0L,
                label = "",
                taskId = null
            )
        }
        persistTimer()
        syncTimerService()
    }

    fun setCountdownMinutes(minutes: Int) {
        if (_timer.value.running) return
        stopTicker()
        flushOvertime()
        accumulatedMillis = 0L
        _timer.update {
            it.copy(
                targetMinutes = minutes.coerceIn(1, MAX_MINUTES),
                elapsedMillis = 0L,
                completed = false,
                recordedOvertimeMillis = 0L,
                label = "",
                taskId = null
            )
        }
        persistTimer()
        syncTimerService()
    }

    fun toggleTimer() {
        if (_timer.value.running) pauseTimer() else startTimer()
    }

    /** 选好「这次专注做什么」之后开始计时 */
    fun startWithLabel(label: String, taskId: String? = null) {
        if (_timer.value.running) return
        val text = label.trim()
        val current = _timer.value
        val finished = current.mode == TimerMode.Countdown &&
            current.elapsedMillis >= current.targetMinutes * 60_000L
        if (finished) {
            // 开新一轮前先把这一轮多出来的超时时间记进统计
            flushOvertime()
            accumulatedMillis = 0L
            _timer.update {
                it.copy(
                    elapsedMillis = 0L,
                    recordedOvertimeMillis = 0L,
                    label = text,
                    taskId = taskId
                )
            }
        } else {
            _timer.update { it.copy(label = text, taskId = taskId) }
        }
        startTimer()
    }

    fun resetTimer() {
        stopTicker()
        flushOvertime()
        accumulatedMillis = 0L
        _timer.update {
            it.copy(
                elapsedMillis = 0L,
                running = false,
                completed = false,
                recordedOvertimeMillis = 0L,
                label = "",
                taskId = null
            )
        }
        _focusLock.value = false
        persistTimer()
        syncTimerService()
    }

    /** 结束这次计时并保存，然后问「任务是否完成」。倒计时、正计时都走这里 */
    fun finishTimer() {
        val current = _timer.value
        val seconds = (current.elapsedMillis / 1000).toInt()
        if (seconds <= 0) return
        val prompt = TimerTaskPrompt(current.mode, current.label, current.taskId)
        when {
            // 正计时：整段都还没入账
            current.mode == TimerMode.Stopwatch ->
                recordSession(TimerMode.Stopwatch, seconds, current.label)
            // 倒计时到点的部分在到点时就记过，这里只补还没入账的超时
            current.completed -> flushOvertime()
            // 倒计时没走完就结束：按正计时记下实际专注的时长，不伪装成完成的倒计时
            else -> recordSession(TimerMode.Stopwatch, seconds, current.label)
        }
        stopTicker()
        accumulatedMillis = 0L
        _timer.update {
            it.copy(
                elapsedMillis = 0L,
                running = false,
                completed = false,
                recordedOvertimeMillis = 0L,
                label = "",
                taskId = null
            )
        }
        _focusLock.value = false
        persistTimer()
        syncTimerService()
        // 记录完再问这次任务是否完成，方便同步待办
        _taskPrompt.value = prompt
    }

    /** 计时结束弹窗：确认任务是否完成，并同步待办 */
    fun resolveTimerTaskOutcome(completed: Boolean) {
        val prompt = _taskPrompt.value ?: return
        _taskPrompt.value = null
        if (completed) {
            val id = prompt.taskId ?: return
            val task = _state.value.tasks.firstOrNull { it.id == id } ?: return
            if (!task.done) toggleTask(id)
            return
        }
        // 「还没完成」：选中的待办本来就还在列表里，不用动
        val stillExists = prompt.taskId?.let { id -> _state.value.tasks.any { it.id == id } } == true
        if (stillExists) return
        // 计时页里自己写的事项，没完成就补进待办
        val text = prompt.label.trim()
        if (text.isEmpty()) return
        if (_state.value.tasks.any { !it.done && it.title == text }) return
        addTask(text, priority = 1)
    }

    /** 点弹窗外/返回键时只关掉弹窗，不改待办状态 */
    fun dismissTimerTaskPrompt() {
        _taskPrompt.value = null
    }

    /** 遮罩上点了「回到专注」：重新打开全屏专注页 */
    fun showFocusLock() {
        if (_timer.value.running) _focusLock.value = true
    }

    private fun startTimer() {
        val current = _timer.value
        if (current.mode == TimerMode.Countdown) {
            val currentTarget = current.targetMinutes * 60_000L
            val freshRound = current.completed ||
                current.elapsedMillis == 0L || current.elapsedMillis >= currentTarget
            if (freshRound) {
                // 新一轮开跑前，把上一轮倒计时结束后的超时时间补记进统计
                flushOvertime()
                // 新一轮一律按「上次选的时长」开始，完成后也沿用，不会回到默认 25 分钟
                val remembered = timerPrefs.getInt(KEY_LAST_MINUTES, current.targetMinutes)
                    .takeIf { it in 1..MAX_MINUTES } ?: current.targetMinutes
                _timer.update {
                    it.copy(
                        targetMinutes = remembered,
                        elapsedMillis = 0L,
                        completed = false,
                        recordedOvertimeMillis = 0L
                    )
                }
            }
        }
        accumulatedMillis = _timer.value.elapsedMillis
        anchorRealtime = SystemClock.elapsedRealtime()
        anchorWallMillis = System.currentTimeMillis()
        _timer.update { it.copy(running = true) }
        _focusLock.value = true
        persistTimer()
        syncTimerService()
        launchTicker()
    }

    /** 从持久化状态恢复后，按已经走过的时长继续计时 */
    private fun resumeTicker() {
        accumulatedMillis = _timer.value.elapsedMillis
        anchorRealtime = SystemClock.elapsedRealtime()
        anchorWallMillis = System.currentTimeMillis()
        _timer.update { it.copy(running = true) }
        persistTimer()
        syncTimerService()
        launchTicker()
    }

    private fun launchTicker() {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (isActive) {
                val elapsed = accumulatedMillis + (SystemClock.elapsedRealtime() - anchorRealtime)
                val snapshot = _timer.value
                if (snapshot.mode == TimerMode.Countdown && !snapshot.completed) {
                    val target = snapshot.targetMinutes * 60_000L
                    if (elapsed >= target) {
                        // 到点只记一次记录，然后继续按正计时往上走，不再停在这一秒
                        _timer.update {
                            it.copy(
                                elapsedMillis = elapsed,
                                completed = true,
                                completionTick = it.completionTick + 1
                            )
                        }
                        recordSession(TimerMode.Countdown, (target / 1000).toInt(), snapshot.label)
                        persistTimer()
                        // 到点后问一下这次任务完成没有，顺手同步待办
                        _taskPrompt.value =
                            TimerTaskPrompt(TimerMode.Countdown, snapshot.label, snapshot.taskId)
                        // 这里不再重启前台服务：通知里的超时计时由 TimerService 自己的
                        // finishCheck 负责切换，重复启动服务在小米等系统上有闪退风险
                    }
                }
                _timer.update { it.copy(elapsedMillis = elapsed) }
                delay(200L)
            }
        }
    }

    private fun pauseTimer() {
        stopTicker()
        // 暂停即视为这一轮超时结束，先把还没入账的超时时间补上
        flushOvertime()
        accumulatedMillis = _timer.value.elapsedMillis
        _timer.update { it.copy(running = false) }
        _focusLock.value = false
        persistTimer()
        syncTimerService()
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /** 学霸模式开关，开启后计时期间挡住白名单以外的 App */
    fun setFocusGuardEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        FocusGuard.setEnabled(app, enabled)
        FocusGuard.sync(app, _timer.value.running)
    }

    /** 计时期间挂一个前台服务，降低后台被系统清理的概率，并实时刷新通知里的大字时间 */
    private fun syncTimerService() {
        val app = getApplication<Application>()
        val snapshot = _timer.value
        // 学霸模式跟着计时一起启停
        FocusGuard.sync(app, snapshot.running)
        if (!snapshot.running) {
            runCatching { app.stopService(Intent(app, TimerService::class.java)) }
            return
        }
        val intent = Intent(app, TimerService::class.java).apply {
            putExtra(TimerService.EXTRA_MODE, snapshot.mode.name)
            putExtra(TimerService.EXTRA_TARGET_MINUTES, snapshot.targetMinutes)
            putExtra(TimerService.EXTRA_BASE_ELAPSED, accumulatedMillis)
            putExtra(TimerService.EXTRA_ANCHOR_WALL, anchorWallMillis)
            putExtra(TimerService.EXTRA_LABEL, snapshot.label)
        }
        runCatching { ContextCompat.startForegroundService(app, intent) }
    }

    /** 把计时状态并进主数据一起写盘，避免计时和记录分两次写 */
    private fun persistTimer() {
        val snapshot = _timer.value
        timerPrefs.edit()
            .putString(KEY_LAST_MODE, snapshot.mode.name)
            .putInt(KEY_LAST_MINUTES, snapshot.targetMinutes)
            .apply()
        val stored = StoredTimer(
            mode = snapshot.mode,
            targetMinutes = snapshot.targetMinutes,
            // running 时存「锚点时刻的累计值」，配合 anchorWall 才能算准真实经过时间
            elapsedMillis = if (snapshot.running) accumulatedMillis else snapshot.elapsedMillis,
            lastElapsedMillis = snapshot.elapsedMillis,
            running = snapshot.running,
            anchorWallMillis = if (snapshot.running) anchorWallMillis else 0L,
            label = snapshot.label,
            taskId = snapshot.taskId,
            recordedOvertimeMillis = snapshot.recordedOvertimeMillis
        )
        mutate { it.copy(timerSnapshot = stored) }
    }

    /** 上次选过的模式和时长优先，不让界面回到默认的「倒计时 25 分钟」 */
    private fun applyLastTimerChoice() {
        val minutes = timerPrefs.getInt(KEY_LAST_MINUTES, 0)
        if (minutes in 1..MAX_MINUTES) {
            _timer.value = _timer.value.copy(targetMinutes = minutes)
        }
        val mode = runCatching {
            TimerMode.valueOf(timerPrefs.getString(KEY_LAST_MODE, "") ?: "")
        }.getOrNull()
        if (mode != null) {
            _timer.value = _timer.value.copy(mode = mode)
        }
    }

    /** 冷启动时恢复上次的计时 */
    private fun restoreTimer() {
        val saved = _state.value.timerSnapshot
        if (saved == null) {
            syncTimerService()
            return
        }
        val savedMinutes = saved.targetMinutes.coerceIn(1, MAX_MINUTES)
        val target = savedMinutes * 60_000L

        if (saved.mode == TimerMode.Stopwatch) {
            // 正计时：进程被杀视为暂停，只恢复离开前的进度，不把不在的时间算进去
            val elapsed = if (saved.running) saved.lastElapsedMillis else saved.elapsedMillis
            _timer.value = TimerState(
                mode = saved.mode,
                targetMinutes = savedMinutes,
                elapsedMillis = elapsed,
                running = false,
                label = saved.label,
                taskId = saved.taskId
            )
            persistTimer()
            syncTimerService()
            return
        }

        val elapsed = if (saved.running) {
            saved.elapsedMillis +
                (System.currentTimeMillis() - saved.anchorWallMillis).coerceAtLeast(0L)
        } else {
            saved.elapsedMillis
        }

        if (elapsed >= target) {
            // 倒计时在进程被杀期间已经走完：补记一条专注记录，并接着按超时正计时继续
            _timer.value = TimerState(
                mode = saved.mode,
                targetMinutes = savedMinutes,
                elapsedMillis = elapsed,
                running = false,
                completed = true,
                recordedOvertimeMillis = saved.recordedOvertimeMillis,
                label = saved.label,
                taskId = saved.taskId
            )
            if (saved.running) {
                val finishedAt = saved.anchorWallMillis + (target - saved.elapsedMillis)
                val targetSeconds = (target / 1000).toInt()
                // 如果上次恢复时已经补记过（进程在写盘前又被打断），这里不重复记录
                // 记录时刻和推算出来的完成时刻会差几百毫秒，所以按 10 秒的窗口判断
                val alreadyRecorded = _state.value.sessions.any {
                    it.mode == TimerMode.Countdown &&
                        it.seconds == targetSeconds &&
                        kotlin.math.abs(it.completedAt - finishedAt) < 10_000L
                }
                if (!alreadyRecorded) {
                    recordSession(
                        TimerMode.Countdown,
                        targetSeconds,
                        saved.label,
                        finishedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                    // 进程不在的时候倒计时已经走完，回来照样问一次任务完成没有
                    _taskPrompt.value =
                        TimerTaskPrompt(TimerMode.Countdown, saved.label, saved.taskId)
                }
                resumeTicker()
            }
        } else {
            _timer.value = TimerState(
                mode = saved.mode,
                targetMinutes = savedMinutes,
                elapsedMillis = elapsed,
                running = false,
                label = saved.label,
                taskId = saved.taskId
            )
            if (saved.running) resumeTicker()
        }
        persistTimer()
        syncTimerService()
    }

    /** 离开前台时记一次进度，万一被系统杀掉，恢复时能停在离开时的位置 */
    fun onAppStopped() {
        if (!_timer.value.running) return
        persistTimer()
        // 计时期间离开 App（回桌面、切到别的应用、锁屏）记一次分心，只统计不拦截
        val now = System.currentTimeMillis()
        mutate { current ->
            current.copy(distractions = (current.distractions + now).takeLast(MAX_DISTRACTIONS))
        }
    }

    // ---------- 备份 ----------

    /** 导出备份用：当前全部数据的 JSON 文本 */
    fun encodeBackup(): String = store.encode(_state.value)

    /** 从备份恢复；计时状态保持当前不变 */
    fun applyBackup(raw: String): Boolean = runCatching {
        val imported = store.decode(raw)
        mutate { current -> imported.copy(timerSnapshot = current.timerSnapshot) }
        true
    }.getOrDefault(false)

    // ---------- 账号与云同步 ----------

    private var cloudUploadJob: Job? = null
    /** 本机有改动还没成功传到云端：回到前台、重开 App 或下次改动时都会补传 */
    private var pendingCloudUpload: Boolean
        get() = syncPrefs.getBoolean(KEY_PENDING_UPLOAD, false)
        set(value) {
            syncPrefs.edit().putBoolean(KEY_PENDING_UPLOAD, value).apply()
        }

    /** 云端调用统一兜底：任何异常都转成错误信息，避免协程异常把 App 杀掉 */
    private suspend fun <T> safeCloud(block: () -> CloudResult<T>): CloudResult<T> =
        withContext(Dispatchers.IO) {
            runCatching { block() }
                .getOrElse { CloudResult(error = "网络异常：${it.message.orEmpty()}") }
        }

    fun cloudLogin(email: String, password: String, register: Boolean) {
        if (_cloud.value.busy) return
        if (email.isBlank() || password.length < 6) {
            _cloud.update { it.copy(message = "请输入邮箱和至少 6 位密码") }
            return
        }
        _cloud.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = safeCloud {
                if (register) cloudSync.signUp(email.trim(), password)
                else cloudSync.signIn(email.trim(), password)
            }
            if (!result.ok) {
                _cloud.update { it.copy(busy = false, message = result.error) }
                return@launch
            }
            val hasCloud = safeCloud { cloudSync.hasCloudBackup() }
            val base = _cloud.value.copy(
                busy = false,
                loggedIn = true,
                email = cloudSync.email,
                lastSyncAt = cloudSync.lastSyncAt
            )
            if (!hasCloud.ok) {
                _cloud.value = base.copy(message = hasCloud.error)
                return@launch
            }
            when {
                // 云端已有备份、本机是空的（换手机场景）→ 自动恢复
                hasCloud.value == true && isLocalEmpty(_state.value) -> {
                    restoreCloudInternal(auto = true)
                }
                // 两边都有数据，等用户选恢复还是覆盖，避免误覆盖
                hasCloud.value == true -> {
                    _cloud.value = base.copy(
                        needsAttention = true,
                        message = "云端已有备份，请选择「从云端恢复」或用本机覆盖"
                    )
                }
                else -> {
                    val upload = safeCloud {
                        cloudSync.upload(store.encode(_state.value))
                    }
                    _cloud.value = base.copy(
                        lastSyncAt = cloudSync.lastSyncAt,
                        message = if (upload.ok) "已登录，本机数据已上传" else upload.error
                    )
                }
            }
        }
    }

    /** 忘记密码：往邮箱发重置链接，用户在网页上设置新密码后再回 App 登录 */
    fun cloudSendReset(email: String) {
        if (_cloud.value.busy) return
        if (email.isBlank()) {
            _cloud.update { it.copy(message = "请先填邮箱") }
            return
        }
        _cloud.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = safeCloud { cloudSync.sendPasswordReset(email) }
            _cloud.update {
                it.copy(
                    busy = false,
                    message = if (result.ok) {
                        "重置邮件已发送，请到邮箱点链接设置新密码后再登录"
                    } else {
                        result.error
                    }
                )
            }
        }
    }

    fun cloudLogout() {
        cloudUploadJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) { cloudSync.signOut() }
        _cloud.value = CloudUiState()
    }

    fun cloudUploadNow() {
        if (_cloud.value.busy || !cloudSync.loggedIn) return
        _cloud.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = safeCloud {
                cloudSync.upload(store.encode(_state.value))
            }
            if (result.ok) pendingCloudUpload = false
            _cloud.update {
                it.copy(
                    busy = false,
                    lastSyncAt = cloudSync.lastSyncAt,
                    needsAttention = if (result.ok) false else it.needsAttention,
                    message = if (result.ok) "已上传到云端" else result.error
                )
            }
        }
    }

    fun cloudRestore() {
        if (_cloud.value.busy) return
        _cloud.update { it.copy(busy = true, message = null) }
        viewModelScope.launch { restoreCloudInternal(auto = false) }
    }

    fun cloudDismissMessage() {
        _cloud.update { it.copy(message = null) }
    }

    private suspend fun restoreCloudInternal(auto: Boolean) {
        val result = safeCloud { cloudSync.download() }
        if (!result.ok || result.value == null) {
            _cloud.update {
                it.copy(busy = false, message = result.error ?: "云端备份无法读取")
            }
            return
        }
        val applied = applyBackup(result.value)
        _cloud.update {
            it.copy(
                busy = false,
                loggedIn = true,
                email = cloudSync.email,
                lastSyncAt = cloudSync.lastSyncAt,
                needsAttention = false,
                message = when {
                    !applied -> "云端备份格式无法识别"
                    auto -> "已从云端恢复本机数据"
                    else -> "已从云端恢复"
                }
            )
        }
    }

    /** 数据变化后延迟上传，避免频繁请求；等用户处理冲突期间不自动上传 */
    private fun scheduleCloudUpload() {
        if (!cloudSync.loggedIn) return
        val cloud = _cloud.value
        if (cloud.needsAttention || cloud.busy) return
        pendingCloudUpload = true
        cloudUploadJob?.cancel()
        cloudUploadJob = viewModelScope.launch {
            delay(2_500L)
            uploadWithRetry()
        }
    }

    /** 回到前台时，如果上次还有没传成功的改动，补传一次 */
    fun retryPendingCloudUpload() {
        if (!pendingCloudUpload || !cloudSync.loggedIn) return
        val cloud = _cloud.value
        if (cloud.needsAttention || cloud.busy) return
        cloudUploadJob?.cancel()
        cloudUploadJob = viewModelScope.launch {
            delay(1_000L)
            uploadWithRetry()
        }
    }

    /** 失败后按 0/8/30 秒重试三次；仍然失败就留着，等下次改动或回到前台补传 */
    private suspend fun uploadWithRetry(): Boolean {
        var lastError: String? = null
        for (wait in longArrayOf(0L, 8_000L, 30_000L)) {
            if (wait > 0L) delay(wait)
            if (!cloudSync.loggedIn) return false
            // 上传前先确认云端没被另一台设备改过，避免把对方的数据覆盖掉
            val remote = safeCloud { cloudSync.remoteVersion() }
            if (remote.ok) {
                val remoteStamp = remote.value
                val localStamp = cloudSync.cloudVersion
                if (remoteStamp != null && localStamp != null && remoteStamp != localStamp) {
                    pendingCloudUpload = true
                    _cloud.update {
                        it.copy(
                            needsAttention = true,
                            message = "另一台设备改过云端数据，请选择「从云端恢复」或用本机覆盖"
                        )
                    }
                    return false
                }
            }
            val json = store.encode(_state.value)
            val result = safeCloud { cloudSync.upload(json) }
            if (result.ok) {
                pendingCloudUpload = false
                _cloud.update {
                    it.copy(
                        lastSyncAt = cloudSync.lastSyncAt,
                        // 补传成功后把之前的失败提示收掉，其他提示保留
                        message = it.message?.takeUnless { msg -> msg.startsWith("同步失败") }
                    )
                }
                return true
            }
            lastError = result.error
        }
        pendingCloudUpload = true
        _cloud.update {
            it.copy(message = "同步失败，稍后会自动重试：${lastError.orEmpty()}")
        }
        return false
    }

    private fun isLocalEmpty(state: AppState): Boolean =
        state.tasks.isEmpty() && state.countdowns.isEmpty() && state.habits.isEmpty() &&
            state.sessions.isEmpty() && state.taskRecords.isEmpty()

    /**
     * 倒计时结束后的「超时正计时」不像倒计时到点那样自动入账，
     * 在暂停、重置、切换模式或开新一轮时，把还没记过的超时补成一条正计时记录。
     */
    private fun flushOvertime() {
        val snapshot = _timer.value
        if (snapshot.mode != TimerMode.Countdown) return
        val target = snapshot.targetMinutes * 60_000L
        val overtime = (snapshot.elapsedMillis - target).coerceAtLeast(0L)
        val pendingMillis = overtime - snapshot.recordedOvertimeMillis
        val seconds = (pendingMillis / 1000L).toInt()
        if (seconds <= 0) return
        // 先推进已入账水位，只记整秒，零头留到下次补记
        _timer.update {
            it.copy(recordedOvertimeMillis = it.recordedOvertimeMillis + seconds * 1000L)
        }
        recordSession(TimerMode.Stopwatch, seconds, snapshot.label)
    }

    private fun recordSession(
        mode: TimerMode,
        seconds: Int,
        label: String,
        completedAt: Long = System.currentTimeMillis()
    ) {
        if (seconds <= 0) return
        mutate { current ->
            current.copy(
                sessions = current.sessions + TimerSession(
                    id = UUID.randomUUID().toString(),
                    mode = mode,
                    seconds = seconds,
                    completedAt = completedAt,
                    label = label
                )
            )
        }
    }

    private companion object {
        const val KEY_PENDING_UPLOAD = "pending_upload"
        const val KEY_NEEDS_ATTENTION = "needs_attention"
        const val KEY_LAST_MODE = "last_mode"
        const val KEY_LAST_MINUTES = "last_minutes"

        /** 自定义最长 600 分钟（10 小时），滑杆本身仍只到 60 */
        const val MAX_MINUTES = 600


        /** 只留最近的分心记录，避免数据文件无限变大 */
        const val MAX_DISTRACTIONS = 2000
    }
}
