package com.qingxiao.lighteff.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qingxiao.lighteff.FocusGuard
import com.qingxiao.lighteff.R
import com.qingxiao.lighteff.TimerState
import com.qingxiao.lighteff.data.TimerMode
import kotlin.math.max

/**
 * 全屏专注页：点开始后盖住整个界面（含状态栏、导航栏、底部标签），
 * 只留剩余时间和暂停、结束、紧急退出。返回键在这里被拦截，必须点按钮才能离开。
 */
@Composable
fun FocusLockScreen(
    timer: TimerState,
    onEmergencyExit: () -> Unit,
    onPause: () -> Unit = {},
    onFinish: () -> Unit = {},
    onWhitelistChanged: () -> Unit = {},
    onEnableGuard: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme
    var showWhitelist by remember { mutableStateOf(false) }
    var whitelistCount by remember { mutableIntStateOf(FocusGuard.whitelist(context).size) }
    var guardOn by remember {
        mutableStateOf(FocusGuard.isEnabled(context) && FocusGuard.permissionsReady(context))
    }
    var hasUsage by remember { mutableStateOf(FocusGuard.hasUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(FocusGuard.canDrawOverlay(context)) }
    var showPermission by remember { mutableStateOf(false) }

    // 从系统设置授权返回后刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                guardOn = FocusGuard.isEnabled(context) && FocusGuard.permissionsReady(context)
                hasUsage = FocusGuard.hasUsageAccess(context)
                hasOverlay = FocusGuard.canDrawOverlay(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 盖住系统栏，变成真正的全屏；退出时恢复
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // 返回键不生效，只能走紧急退出
    BackHandler(enabled = true) { }

    val targetMillis = timer.targetMinutes * 60_000L
    val runningOvertime = timer.mode == TimerMode.Countdown && timer.completed
    val displayMillis = when {
        runningOvertime -> (timer.elapsedMillis - targetMillis).coerceAtLeast(0L)
        timer.mode == TimerMode.Countdown -> max(targetMillis - timer.elapsedMillis, 0L)
        else -> timer.elapsedMillis
    }
    val timeText = if (runningOvertime) "+${formatClock(displayMillis)}" else formatClock(displayMillis)
    val task = timer.label.trim()
    val modeText = when {
        runningOvertime -> "已完成 · 超时计时中"
        timer.mode == TimerMode.Countdown -> "倒计时专注中"
        else -> "正计时进行中"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 20.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = modeText,
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = timeText,
                fontSize = if (timeText.length > 5) 58.sp else 76.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = colors.onBackground,
                maxLines = 1
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = task.ifBlank { "本次专注" },
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (guardOn) {
                Text(
                    text = "学霸模式已开启 · 白名单外的 App 会被挡住",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.primary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
            } else {
                TextButton(onClick = { showPermission = true }) {
                    Text(
                        text = "开启学霸模式，离开这里也挡住其他 App",
                        color = colors.primary
                    )
                }
            }
            TextButton(
                onClick = {
                    whitelistCount = FocusGuard.whitelist(context).size
                    showWhitelist = true
                }
            ) {
                Text(
                    text = "白名单 · $whitelistCount 个应用",
                    color = colors.primary
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onPause,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_pause),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("暂停")
                }
                Button(
                    onClick = onFinish,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("结束")
                }
            }
            TextButton(onClick = onEmergencyExit) {
                Text(
                    text = "紧急退出（计时继续）",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant
                )
            }
        }
    }

    if (showWhitelist) {
        WhitelistDialog(
            selected = FocusGuard.whitelist(context),
            onDismiss = { showWhitelist = false },
            onConfirm = { packages ->
                FocusGuard.setWhitelist(context, packages)
                whitelistCount = packages.size
                onWhitelistChanged()
                showWhitelist = false
            }
        )
    }

    if (showPermission) {
        FocusGuardPermissionDialog(
            hasUsage = hasUsage,
            hasOverlay = hasOverlay,
            onRequestUsage = {
                runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            },
            onRequestOverlay = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                    )
                }
            },
            onDismiss = { showPermission = false },
            onEnable = {
                onEnableGuard()
                FocusGuard.sync(context, timer.running)
                guardOn = true
                onWhitelistChanged()
                showPermission = false
            }
        )
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
