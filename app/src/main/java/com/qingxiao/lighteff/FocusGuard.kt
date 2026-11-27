package com.qingxiao.lighteff

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.json.JSONArray

/**
 * 「学霸模式」：计时期间挡住白名单以外的 App。
 * 需要「使用情况访问」判断当前前台是谁，需要「悬浮窗」把屏幕盖住。
 */
object FocusGuard {
    private const val PREFS = "lighteff_guard"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_WHITELIST = "whitelist"

    /**
     * 轻效自己是否在前台。由 MainActivity 的生命周期更新。
     * 屏蔽服务据此保证遮罩永远不会盖住自己的界面：使用情况统计有延迟，
     * 刚点「回到专注」时可能还报着上一个 App，只靠包名判断会把自己也盖住。
     */
    @Volatile
    var appVisible: Boolean = false

    /** 遮罩上点了「回到专注」：主界面下次回到前台时重新打开全屏专注页 */
    @Volatile
    private var focusScreenRequested: Boolean = false

    fun requestFocusScreen() {
        focusScreenRequested = true
    }

    fun consumeFocusScreenRequest(): Boolean {
        val requested = focusScreenRequested
        focusScreenRequested = false
        return requested
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun whitelist(context: Context): Set<String> {
        val raw = prefs(context).getString(KEY_WHITELIST, null) ?: return emptySet()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun setWhitelist(context: Context, packages: Set<String>) {
        prefs(context).edit()
            .putString(KEY_WHITELIST, JSONArray(packages.toList()).toString())
            .apply()
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun permissionsReady(context: Context): Boolean =
        hasUsageAccess(context) && canDrawOverlay(context)

    /**
     * 小米/红米的后台保活设置页。国行 ROM 会把后台进程整个冻结，
     * 哪怕有前台服务也一样，只能让用户在这里把「省电策略」改成无限制。
     */
    fun openBackgroundSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR")
                .putExtra("extra_pkgname", context.packageName),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        )
        return candidates.any { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }.isSuccess
        }
    }

    /** 申请忽略电池优化，国际版/原生系统上直接解除后台限制 */
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (runCatching { power.isIgnoringBatteryOptimizations(context.packageName) }
                .getOrDefault(false)) {
            return false
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(request) }.isSuccess) return true
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(list) }.isSuccess
    }

    /** 计时状态或开关变化时调用，决定屏蔽服务的启停 */
    fun sync(context: Context, timerRunning: Boolean) {
        val shouldRun = timerRunning && isEnabled(context) && permissionsReady(context)
        val intent = Intent(context, FocusGuardService::class.java)
        if (shouldRun) {
            // 已经在跑就不重复启动，减少后台启动前台服务被系统拦下的概率
            if (FocusGuardService.isRunning) return
            runCatching { ContextCompat.startForegroundService(context, intent) }
        } else {
            runCatching { context.stopService(intent) }
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
