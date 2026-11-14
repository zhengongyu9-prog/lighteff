package com.qingxiao.lighteff.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.qingxiao.lighteff.FocusGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 计时页的「学霸模式」卡片：开关 + 白名单入口 + 权限引导 */
@Composable
fun FocusGuardCard(onEnabledChange: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(FocusGuard.isEnabled(context)) }
    var whitelistCount by remember { mutableIntStateOf(FocusGuard.whitelist(context).size) }
    var hasUsage by remember { mutableStateOf(FocusGuard.hasUsageAccess(context)) }
    var hasOverlay by remember { mutableStateOf(FocusGuard.canDrawOverlay(context)) }
    var showPermission by remember { mutableStateOf(false) }
    var showWhitelist by remember { mutableStateOf(false) }

    // 从系统设置授权返回后刷新状态
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = FocusGuard.isEnabled(context)
                hasUsage = FocusGuard.hasUsageAccess(context)
                hasOverlay = FocusGuard.canDrawOverlay(context)
                whitelistCount = FocusGuard.whitelist(context).size
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "专注屏蔽（学霸模式）",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "计时期间挡住白名单以外的 App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (want && !(FocusGuard.hasUsageAccess(context) && FocusGuard.canDrawOverlay(context))) {
                            showPermission = true
                        } else {
                            enabled = want
                            onEnabledChange(want)
                        }
                    }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showWhitelist = true }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "白名单",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$whitelistCount 个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    if (showPermission) {
        AlertDialog(
            onDismissRequest = { showPermission = false },
            title = { Text("还差两个权限") },
            text = {
                Column {
                    Text(
                        text = "开启后会挡住白名单以外的 App，需要下面两个系统权限：",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    PermissionRow(
                        title = "使用情况访问",
                        subtitle = "用来判断当前打开的是哪个 App",
                        granted = hasUsage
                    ) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                    }
                    Spacer(Modifier.height(8.dp))
                    PermissionRow(
                        title = "悬浮窗",
                        subtitle = "用来把违规的 App 盖住",
                        granted = hasOverlay
                    ) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = hasUsage && hasOverlay,
                    onClick = {
                        enabled = true
                        onEnabledChange(true)
                        showPermission = false
                    }
                ) { Text("开启") }
            },
            dismissButton = {
                TextButton(onClick = { showPermission = false }) { Text("取消") }
            }
        )
    }

    if (showWhitelist) {
        WhitelistDialog(
            selected = FocusGuard.whitelist(context),
            onDismiss = { showWhitelist = false },
            onConfirm = { packages ->
                FocusGuard.setWhitelist(context, packages)
                whitelistCount = packages.size
                showWhitelist = false
            }
        )
    }
}

@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onRequest: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (granted) "已授权" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (granted) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Text(
                text = "已完成",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            TextButton(onClick = onRequest) { Text("去授权") }
        }
    }
}

@Composable
private fun WhitelistDialog(
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var picked by remember { mutableStateOf(selected) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.applicationInfo }
                .filter { it.packageName != context.packageName }
                .distinctBy { it.packageName }
                .map { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }
                        .getOrDefault(info.packageName)
                    info.packageName to label
                }
                .sortedBy { it.second }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("白名单") },
        text = {
            val list = apps
            if (list == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                Column {
                    Text(
                        text = "勾选专注时允许使用的 App",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                        items(list) { (pkg, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        picked = if (pkg in picked) picked - pkg else picked + pkg
                                    }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(checked = pkg in picked, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(picked) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
