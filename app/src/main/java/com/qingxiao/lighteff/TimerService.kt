package com.qingxiao.lighteff

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.qingxiao.lighteff.data.AppStore
import com.qingxiao.lighteff.data.TimerMode

/**
 * 计时期间的前台服务：常驻通知，大字显示剩余/已用时间。
 *
 * 通知里的时间交给 Chronometer 自己走（系统界面每秒重绘），不再由 App 每秒调
 * notify() 刷新。原来的每秒刷新在息屏休眠、厂商限制通知刷新频率、主线程卡顿时
 * 会被系统推迟或丢弃，表现就是通知时间停在某一秒不动；交给系统走时就不会了，
 * 顺带省电。
 *
 * stopWithTask=false，任务被划掉也保留进程，降低后台清理打断计时的概率。
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var mode = TimerMode.Countdown
    private var targetMinutes = 25
    private var startElapsed = 0L
    private var startedRealtime = 0L
    private var label = ""

    /** 倒计时走到 0 时把通知切成「超时正计时」，计时继续跑，不再停服务 */
    private val finishCheck = Runnable {
        if (mode != TimerMode.Countdown) return@Runnable
        if (currentElapsed() < targetMillis()) {
            // 息屏期间被推迟执行，醒来时还没到点就按剩余时间重新挂一次
            scheduleFinishCheck()
            return@Runnable
        }
        notify(buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        var baseElapsed = 0L
        var anchorWall = System.currentTimeMillis()
        if (intent == null) {
            // 服务被系统重启时从主数据恢复，没有运行中的计时就不留通知
            val saved = runCatching { AppStore(filesDir).load().timerSnapshot }.getOrNull()
            if (saved?.running != true) {
                stopSelf()
                return START_NOT_STICKY
            }
            mode = saved.mode
            targetMinutes = saved.targetMinutes
            baseElapsed = saved.elapsedMillis
            anchorWall = saved.anchorWallMillis
            label = saved.label
        } else {
            mode = runCatching { TimerMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
                .getOrDefault(TimerMode.Countdown)
            targetMinutes = intent.getIntExtra(EXTRA_TARGET_MINUTES, 25)
            baseElapsed = intent.getLongExtra(EXTRA_BASE_ELAPSED, 0L)
            anchorWall = intent.getLongExtra(EXTRA_ANCHOR_WALL, System.currentTimeMillis())
            label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        }

        // 墙钟只用来把「离开时的进度」换算成此刻的进度，之后一律用单调时钟计时，
        // 系统时间被校准或手动改动也不会让通知时间跳变
        val carried = baseElapsed +
            (System.currentTimeMillis() - anchorWall).coerceAtLeast(0L)
        startElapsed = carried
        startedRealtime = SystemClock.elapsedRealtime()

        ensureChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleFinishCheck()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishCheck)
        super.onDestroy()
    }

    private fun targetMillis(): Long = targetMinutes * 60_000L

    private fun currentElapsed(): Long =
        startElapsed + (SystemClock.elapsedRealtime() - startedRealtime).coerceAtLeast(0L)

    private fun scheduleFinishCheck() {
        handler.removeCallbacks(finishCheck)
        if (mode != TimerMode.Countdown) return
        val remaining = targetMillis() - currentElapsed()
        if (remaining <= 0L) {
            handler.post(finishCheck)
        } else {
            handler.postDelayed(finishCheck, remaining + 50L)
        }
    }

    private fun buildNotification(): Notification {
        val elapsed = currentElapsed()
        val target = targetMillis()
        // 倒计时走完后不停，改成从 0 往上记超时时间
        val overtime = mode == TimerMode.Countdown && elapsed >= target
        val status = when {
            overtime -> if (label.isBlank()) "已完成 · 超时计时中" else "已完成 · $label"
            mode == TimerMode.Countdown -> if (label.isBlank()) "倒计时专注中" else "专注中 · $label"
            else -> if (label.isBlank()) "正计时进行中" else "计时中 · $label"
        }
        // Chronometer 的基准用 elapsedRealtime 时基：倒计时给「终点时刻」，
        // 正计时和超时给「起点时刻」，系统界面自己每秒往前走，不依赖 App 刷新
        val base = when {
            overtime -> SystemClock.elapsedRealtime() - (elapsed - target)
            mode == TimerMode.Countdown -> SystemClock.elapsedRealtime() + (target - elapsed)
            else -> SystemClock.elapsedRealtime() - elapsed
        }
        val views = RemoteViews(packageName, R.layout.notification_timer).apply {
            setChronometerCountDown(R.id.notification_time, mode == TimerMode.Countdown && !overtime)
            setChronometer(R.id.notification_time, base, null, true)
            setTextViewText(R.id.notification_label, status)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tab_countdown)
            .setCustomContentView(views)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = getSharedPreferences("lighteff_timer_channel", Context.MODE_PRIVATE)
        val migrated = prefs.getBoolean(KEY_CHANNEL_MIGRATED, false)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (!migrated) {
            // 只做一次迁移：老版本建的是低优先级渠道，会折叠进「静音通知」，
            // 迁移完记住标记，之后不再动它，用户手动调过的优先级不会被重置
            manager.deleteNotificationChannel(CHANNEL_ID)
            manager.createNotificationChannel(defaultChannel())
            prefs.edit().putBoolean(KEY_CHANNEL_MIGRATED, true).apply()
            return
        }
        if (existing == null) manager.createNotificationChannel(defaultChannel())
    }

    private fun defaultChannel() = NotificationChannel(
        CHANNEL_ID,
        "计时进行中",
        NotificationManager.IMPORTANCE_DEFAULT
    )

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TARGET_MINUTES = "extra_target_minutes"
        const val EXTRA_BASE_ELAPSED = "extra_base_elapsed"
        const val EXTRA_ANCHOR_WALL = "extra_anchor_wall"
        const val EXTRA_LABEL = "extra_label"

        private const val CHANNEL_ID = "timer_running"
        private const val NOTIFICATION_ID = 1
        private const val KEY_CHANNEL_MIGRATED = "channel_migrated"
    }
}
