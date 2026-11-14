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
    val label: String = ""
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

    fun addTask(title: String, priority: Int) {
        val text = title.trim()
        if (text.isEmpty()) return
        mutate { current ->
            current.copy(
                tasks = listOf(TaskItem(id = UUID.randomUUID().toString(), title = text, priority = priority)) + current.tasks
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
        accumulatedMillis = 0L
        _timer.update {
            it.copy(mode = mode, elapsedMillis = 0L, running = false, completed = false, label = "")
        }
        persistTimer()
        syncTimerService()
    }

    fun setCountdownMinutes(minutes: Int) {
        if (_timer.value.running) return
        stopTicker()
        accumulatedMillis = 0L
        _timer.update {
            it.copy(
                targetMinutes = minutes.coerceIn(1, MAX_MINUTES),
                elapsedMillis = 0L,
                completed = false,
                label = ""
            )
        }
        persistTimer()
        syncTimerService()
    }

    fun toggleTimer() {
        if (_timer.value.running) pauseTimer() else startTimer()
    }

    /** 紧急退出全屏专注页：只关闭全屏，计时继续在后台跑 */
    fun dismissFocusLock() {
        _focusLock.value = false
    }

    /** 选好「这次专注做什么」之后开始计时 */
    fun startWithLabel(label: String) {
        if (_timer.value.running) return
        val text = label.trim()
        val current = _timer.value
        val finished = current.mode == TimerMode.Countdown &&
            current.elapsedMillis >= current.targetMinutes * 60_000L
        if (finished) {
            accumulatedMillis = 0L
            _timer.update { it.copy(elapsedMillis = 0L, label = text) }
        } else {
            _timer.update { it.copy(label = text) }
        }
        startTimer()
    }

    fun resetTimer() {
        stopTicker()
        accumulatedMillis = 0L
        _timer.update { it.copy(elapsedMillis = 0L, running = false, completed = false, label = "") }
        _focusLock.value = false
        persistTimer()
        syncTimerService()
    }

    /** 正计时：结束并保存本次记录 */
    fun finishStopwatch() {
        val current = _timer.value
        if (current.mode != TimerMode.Stopwatch) return
        val seconds = (current.elapsedMillis / 1000).toInt()
        if (seconds <= 0) return
        stopTicker()
        accumulatedMillis = 0L
        _timer.update { it.copy(elapsedMillis = 0L, running = false, label = "") }
        _focusLock.value = false
        recordSession(TimerMode.Stopwatch, seconds, current.label)
        persistTimer()
        syncTimerService()
    }

    private fun startTimer() {
        val current = _timer.value
        if (current.mode == TimerMode.Countdown) {
            val currentTarget = current.targetMinutes * 60_000L
            val freshRound = current.completed ||
                current.elapsedMillis == 0L || current.elapsedMillis >= currentTarget
            if (freshRound) {
                // 新一轮一律按「上次选的时长」开始，完成后也沿用，不会回到默认 25 分钟
                val remembered = timerPrefs.getInt(KEY_LAST_MINUTES, current.targetMinutes)
                    .takeIf { it in 1..MAX_MINUTES } ?: current.targetMinutes
                _timer.update {
                    it.copy(targetMinutes = remembered, elapsedMillis = 0L, completed = false)
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
                        syncTimerService()
                    }
                }
                _timer.update { it.copy(elapsedMillis = elapsed) }
                delay(200L)
            }
        }
    }

    private fun pauseTimer() {
        stopTicker()
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

    /** 计时期间挂一个前台服务，降低后台被系统清理的概率，并实时刷新通知里的大字时间 */
    private fun syncTimerService() {
        val app = getApplication<Application>()
        val snapshot = _timer.value
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
            label = snapshot.label
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
                label = saved.label
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
                label = saved.label
            )
            if (saved.running) {
                val finishedAt = saved.anchorWallMillis + (target - saved.elapsedMillis)
                val targetSeconds = (target / 1000).toInt()
                // 如果上次恢复时已经补记过（进程在写盘前又被打断），这里不重复记录
                val alreadyRecorded = _state.value.sessions.any {
                    it.mode == TimerMode.Countdown &&
                        it.seconds == targetSeconds &&
                        it.completedAt == finishedAt
                }
                if (!alreadyRecorded) {
                    recordSession(
                        TimerMode.Countdown,
                        targetSeconds,
                        saved.label,
                        finishedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
                    )
                }
                resumeTicker()
            }
        } else {
            _timer.value = TimerState(
                mode = saved.mode,
                targetMinutes = savedMinutes,
                elapsedMillis = elapsed,
                running = false,
                label = saved.label
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
