package com.qingxiao.lighteff.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.qingxiao.lighteff.TimerState
import com.qingxiao.lighteff.data.TimerMode
import kotlin.math.max

/**
 * 全屏专注页：点开始后盖住整个界面（含状态栏、导航栏、底部标签），
 * 只留剩余时间和一个紧急退出。返回键在这里被拦截，必须点按钮才能离开。
 */
@Composable
fun FocusLockScreen(timer: TimerState, onEmergencyExit: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val colors = MaterialTheme.colorScheme

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
            OutlinedButton(
                onClick = onEmergencyExit,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp)
            ) {
                Text("紧急退出", color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = "退出后计时继续，通知栏仍会显示倒计时",
                style = MaterialTheme.typography.labelSmall,
                color = colors.outline,
                textAlign = TextAlign.Center
            )
        }
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
