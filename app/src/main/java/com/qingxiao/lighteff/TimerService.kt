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
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.qingxiao.lighteff.data.AppStore
import com.qingxiao.lighteff.data.TimerMode
import java.util.Locale

/**
 * 计时期间的前台服务：常驻通知，大字显示剩余/已用时间；
 * stopWithTask=false，任务被划掉也保留进程，降低后台清理打断计时的概率。
 */
class TimerService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private var mode = TimerMode.Countdown
    private var targetMinutes = 25
    private var baseElapsed = 0L
    private var anchorWall = 0L
    private var label = ""
    private var ticking = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!ticking) return
            val elapsed = currentElapsed()
            val target = targetMinutes * 60_000L
            val finished = mode == TimerMode.Countdown && elapsed >= target
            notify(buildNotification(elapsed, finished))
            if (finished) {
                // 计时结束就停掉服务；补记记录由应用下次启动时完成
                ticking = false
                stopSelf()
                return
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

        ensureChannel()
        ticking = true
        startForeground(NOTIFICATION_ID, buildNotification(currentElapsed(), false))
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 1_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        ticking = false
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun currentElapsed(): Long =
        baseElapsed + (System.currentTimeMillis() - anchorWall).coerceAtLeast(0L)

    private fun buildNotification(elapsed: Long, finished: Boolean): Notification {
        val timeText = when {
            finished -> "00:00"
            mode == TimerMode.Countdown -> formatClock((targetMinutes * 60_000L - elapsed).coerceAtLeast(0L))
            else -> formatClock(elapsed)
        }
        val status = when {
            finished -> "本次专注已完成"
            mode == TimerMode.Countdown -> if (label.isBlank()) "倒计时专注中" else "专注中 · $label"
            else -> if (label.isBlank()) "正计时进行中" else "计时中 · $label"
        }
        val views = RemoteViews(packageName, R.layout.notification_timer).apply {
            setTextViewText(R.id.notification_time, timeText)
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
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        // 旧版本建的是低优先级渠道，会折叠进「静音通知」，这里替换成普通优先级
        if (existing == null || existing.importance != NotificationManager.IMPORTANCE_DEFAULT) {
            manager.deleteNotificationChannel(CHANNEL_ID)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "计时进行中", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun formatClock(millis: Long): String {
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

    companion object {
        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_TARGET_MINUTES = "extra_target_minutes"
        const val EXTRA_BASE_ELAPSED = "extra_base_elapsed"
        const val EXTRA_ANCHOR_WALL = "extra_anchor_wall"
        const val EXTRA_LABEL = "extra_label"

        private const val CHANNEL_ID = "timer_running"
        private const val NOTIFICATION_ID = 1
    }
}
