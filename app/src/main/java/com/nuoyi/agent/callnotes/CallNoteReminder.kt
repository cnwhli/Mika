package com.nuoyi.agent.callnotes

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nuoyi.agent.R

/** 同一文件的私有常量,调度和接收两侧共用。 */
private const val CHANNEL_ID = "callnote_reminders"
private const val CHANNEL_NAME = "通话纪要提醒"
private const val ACTION = "com.nuoyi.agent.callnotes.REMIND"
private const val EXTRA_TEXT = "text"

/**
 * 轻量提醒:到点在系统日历/闹钟之外,用安卓标准闹钟弹一条本地通知。
 * 不依赖联网、不依赖 Agent 跑任务,所以和「通话纪要」这种离线整理场景最搭。
 */
object CallNoteReminder {
    fun schedule(context: Context, id: String, text: String, atMs: Long) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, CallNoteReminderReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_TEXT, text)
        }
        val pi = PendingIntent.getBroadcast(
            context, id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        } else {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
        }
    }
}

/** 到点后弹出提醒通知。 */
class CallNoteReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("通话纪要提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        nm.notify(text.hashCode(), notification)
    }
}
