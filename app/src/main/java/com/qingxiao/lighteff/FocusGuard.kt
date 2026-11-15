package com.qingxiao.lighteff

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
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
