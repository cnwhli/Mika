package com.nuoyi.agent.wechat

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.nuoyi.agent.NuoyiApp
import com.nuoyi.agent.R
import com.nuoyi.agent.i18n.tr
import com.nuoyi.agent.settings.FeatureFlags
import com.nuoyi.agent.task.TaskService

/** 只保活微信长轮询；任务执行仍由 TaskService 管理。 */
class WechatService : Service() {
    private val runtime get() = (application as NuoyiApp).wechat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        if (intent?.action == ACTION_TURN_OFF && !FeatureFlags.get(this).quitRequested()) runtime.setEnabled(false)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else startForeground(NOTIFICATION_ID, notification())
        }.onFailure { Log.w("NuoyiWechat", "WeChat service could not enter the foreground", it) }.isSuccess
        if (!foreground || FeatureFlags.get(this).quitRequested() || !runtime.sync()) {
            runtime.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runtime.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val off = PendingIntent.getService(this, 1, Intent(this, WechatService::class.java).setAction(ACTION_TURN_OFF),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.wechat_notif_title))
            .setContentText(tr(R.string.wechat_notif_body))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.wechat_turn_off), off).build())
            .build()
    }

    companion object {
        const val ACTION_TURN_OFF = "com.nuoyi.agent.wechat.TURN_OFF"
        private const val NOTIFICATION_ID = 30_002
    }
}
