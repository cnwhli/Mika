package com.nuoyi.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.nuoyi.agent.notify.DynamicIslandService
import com.nuoyi.agent.settings.FeatureFlags

/**
 * 开机后把系统级灵动岛拉起来(前提是用户开通过「显示在其他应用上层」且没在设置里关掉)。
 * 配合 [DynamicIslandService.onTaskRemoved] 的自我保护,尽量做到「系统级常驻」。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.QUICKBOOT_POWERON") return
        if (!FeatureFlags.get(context).islandEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
        runCatching { context.startForegroundService(Intent(context, DynamicIslandService::class.java)) }
    }
}
