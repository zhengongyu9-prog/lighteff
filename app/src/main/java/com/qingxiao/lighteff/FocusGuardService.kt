package com.qingxiao.lighteff

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.qingxiao.lighteff.data.AppStore

/**
 * 计时期间每隔几百毫秒看一眼前台是哪个 App，白名单外的就用悬浮窗盖住。
 */
class FocusGuardService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlay: View? = null
    private var blockedPackage: String? = null
    /** 点了「本次不屏蔽」临时放行的包，一旦离开这个 App 就恢复拦截 */
    private var skippedPackage: String? = null
    /** 1 像素的常驻小窗，让系统始终认为本应用有可见窗口，避免后台被冻结 */
    private var keepAlive: View? = null
    /** 系统级永远放行的包，白名单在每次轮询时叠加，改完立即生效 */
    private var systemAllowed: Set<String> = emptySet()
    private var pausedUntil = 0L
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            runCatching { checkForeground() }
            // 自适应间隔：正常盯前台时 250ms，遮罩已经盖上就 400ms 省电，
            // 自己在前台或者熄屏时完全不用盯，1s 一次就够
            val delay = when {
                FocusGuard.appVisible || !isScreenOn() -> 1000L
                overlay != null -> 400L
                else -> 250L
            }
            handler.postDelayed(this, delay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        systemAllowed = buildSystemAllowedPackages()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先把服务变成前台再判断要不要继续：
        // 用 startForegroundService 拉起的服务如果没及时 startForeground，系统会直接判 App 崩溃
        createChannel()
        val started = runCatching {
            startForeground(NOTIFICATION_ID, buildNotification())
        }.isSuccess
        if (!started) {
            // 系统不允许这个前台服务时安静退出，别把整个进程带崩
            stopSelf()
            return START_NOT_STICKY
        }
        // 系统自动拉起（没有 intent）时先看计时还在不在跑，不在就别留通知、别挡人
        if (intent == null) {
            val running = runCatching {
                AppStore(filesDir).load().timerSnapshot?.running
            }.getOrNull() == true
            if (!running) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // 已经关掉开关或权限不全时不该留通知，直接退出
        if (!FocusGuard.isEnabled(this) || !FocusGuard.permissionsReady(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(poll)
        handler.post(poll)
        showKeepAlive()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(poll)
        hideOverlay()
        hideKeepAlive()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkForeground() {
        if (!FocusGuard.isEnabled(this)) {
            stopSelf()
            return
        }
        // 自己在前台时一定不挡：使用情况统计有延迟，靠包名判断可能误盖自己
        if (FocusGuard.appVisible) {
            skippedPackage = null
            hideOverlay()
            return
        }
        // 熄屏时谁也没在看，先把遮罩撤掉，省得亮屏瞬间还停在遮罩上
        if (!isScreenOn()) {
            hideOverlay()
            return
        }
        // 刚点了「回到专注」，给切应用留点时间，别立刻把遮罩盖回来
        if (System.currentTimeMillis() < pausedUntil) return
        // 使用情况访问被撤销时直接退出，免得一直空转、遮罩也撤不掉
        if (!FocusGuard.hasUsageAccess(this)) {
            stopSelf()
            return
        }
        // 悬浮窗权限被撤销时直接退出，免得空转
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        val pkg = currentForegroundPackage()
        if (pkg == null) return
        // 白名单每次重新读，用户在计时过程中勾选/取消立刻生效
        val allowed = systemAllowed + FocusGuard.whitelist(this)
        // 「本次不屏蔽」只作用于当前这个 App，切走再回来就重新拦
        if (skippedPackage != null && pkg != skippedPackage) skippedPackage = null
        if (pkg == skippedPackage) hideOverlay() else if (pkg in allowed) hideOverlay() else showOverlay(pkg)
    }

    private fun isScreenOn(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive

    private fun currentForegroundPackage(): String? {
        val usage = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        // 只看最近 5 秒的事件，窗口越小查询越快
        val events = usage.queryEvents(now - 5_000L, now)
        val event = UsageEvents.Event()
        var last: String? = null
        var lastTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp >= lastTime) {
                last = event.packageName
                lastTime = event.timeStamp
            }
        }
        if (last == null) {
            // 刚亮屏还没产生事件时，用最近使用过的应用兜底
            last = runCatching {
                usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
                    ?.maxByOrNull { it.lastTimeUsed }
                    ?.packageName
            }.getOrNull()
        }
        return last
    }

    /** 自己的 App、桌面、系统界面、输入法、拨号、系统设置永远放行，避免把人锁死 */
    private fun buildSystemAllowedPackages(): Set<String> {
        val result = mutableSetOf(
            packageName,
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.google.android.gms",
            "com.android.phone",
            // 来电/紧急呼叫界面，不能挡
            "com.android.incallui",
            "com.google.android.dialer",
            "com.android.emergency",
            // 系统安装器、文件选择器、相册选择器：常被白名单 App 调起，挡了会卡住整个操作
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.documentsui",
            "com.google.android.documentsui",
            "com.android.providers.media.module",
            "com.google.android.providers.media.module",
            "com.android.intentresolver",
            "com.google.android.intentresolver"
        )
        // 桌面可能有多个（含系统 Resolver），用 queryIntentActivities 全部放行
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.queryIntentActivities(home, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .forEach(result::add)
        }
        runCatching {
            packageManager.resolveActivity(Intent(Intent.ACTION_DIAL), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(result::add)
        }
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.enabledInputMethodList.forEach { result.add(it.packageName) }
        }
        return result
    }

    private fun showOverlay(pkg: String) {
        if (overlay != null && blockedPackage == pkg) return
        hideOverlay()
        blockedPackage = pkg
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
        val view = LayoutInflater.from(this).inflate(R.layout.focus_lock_overlay, null, false)
        view.findViewById<TextView>(R.id.overlay_app_name).text =
            getString(R.string.guard_overlay_app_format, label)
        view.findViewById<View>(R.id.overlay_back).setOnClickListener {
            // 切回自己时先让出 2.5 秒，避免轮询又把遮罩加回来
            pausedUntil = System.currentTimeMillis() + 2_500L
            hideOverlay()
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    )
                )
            }
        }
        view.findViewById<View>(R.id.overlay_disable).setOnClickListener {
            // 只放行眼前这一次：学霸模式保持开启，切走再回来照样拦
            skippedPackage = blockedPackage
            hideOverlay()
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { windowManager.addView(view, params) }
            .onSuccess { overlay = view }
            .onFailure { Log.e("FocusGuard", "overlay failed for $pkg", it) }
    }

    private fun hideOverlay() {
        val view = overlay ?: return
        overlay = null
        blockedPackage = null
        runCatching { windowManager.removeView(view) }
    }

    /**
     * 小米/红米会冻结没有可见窗口的后台进程，连前台服务都不认。
     * 挂一个 1 像素的透明悬浮窗，进程就不会被判定成可回收的后台。
     */
    private fun showKeepAlive() {
        if (keepAlive != null || !Settings.canDrawOverlays(this)) return
        val view = View(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val params = WindowManager.LayoutParams(
            1,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        runCatching { windowManager.addView(view, params) }
            .onSuccess { keepAlive = view }
    }

    private fun hideKeepAlive() {
        val view = keepAlive ?: return
        keepAlive = null
        runCatching { windowManager.removeView(view) }
    }

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "专注屏蔽",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "计时期间挡住白名单外的应用"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_tab_habit)
        .setContentTitle("专注屏蔽已开启")
        .setContentText("白名单以外的 App 会被挡住")
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val CHANNEL_ID = "lighteff_focus_guard"
        private const val NOTIFICATION_ID = 2001

        /** 服务是否已经在跑，避免反复 startForegroundService 触发系统的前台服务限制 */
        @Volatile
        var isRunning: Boolean = false
            private set
    }
}
