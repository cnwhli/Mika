package com.wangzan101.mika.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.wangzan101.mika.core.voice.WakeSource

/**
 * 个人版专用：常驻监听自定义唤醒词，命中后直接起一轮免手会话。
 *
 * 只在 personal flavor 里编译，公版 APK 不含这个类、也不申请 microphone 类型前台服务。
 * 启动必须由用户在**前台**点开关触发（Android 12+ 禁止后台 startForegroundService，
 * Android 14+ 还限制后台启动 microphone 前台服务），且常驻通知一直可见、可随时关掉。
 * 自用侧载没问题；拿去上架应用商店基本会被前台服务权限政策挡回来。
 */
class AlwaysOnWakeWordService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var engine: WakeWordEngine? = null
    private var host: VoiceSessionHost? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                showForeground()
                startEngine()
            }
            ACTION_STOP -> {
                stopAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startEngine() {
        stopHost()
        val e = engine ?: SampledWakeWordEngine(this).also { engine = it }
        e.start(VoiceRuntime.wakeWords(), ::onWakeWordHit)
    }

    private fun onWakeWordHit(word: String) {
        // 命中后立刻交出麦克风：热词引擎和会话识别不能同时录音。
        engine?.stop()
        stopHost()
        val consent = VoiceConsentStore(this)
        host = VoiceSessionHost(
            this,
            VoiceRuntime.capabilities,
            VoiceRuntime::wakeWords,
            AssistantTaskBridge(this),
            consent::read,
            onFinish = { main.post { startEngine() } },
        ).also { it.start(WakeSource.LocalHotword) }
    }

    private fun stopHost() {
        host?.release()
        host = null
    }

    private fun stopAll() {
        engine?.stop()
        engine = null
        main.removeCallbacksAndMessages(null)
        stopHost()
    }

    override fun onDestroy() {
        stopAll()
        super.onDestroy()
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "语音唤醒", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("诺伊正在听唤醒词")
            .setContentText("可在设置里随时关闭")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val ACTION_START = "com.voicehelm.agent.voice.ALWAYS_ON_START"
        private const val ACTION_STOP = "com.voicehelm.agent.voice.ALWAYS_ON_STOP"
        private const val CHANNEL = "voice_always_on"
        private const val NOTIFICATION_ID = 71

        /** 只能从前台界面（用户点开关）调用。 */
        fun start(context: Context) {
            VoiceRuntime.restore(context)
            PersonalWakeWordStore(context).setAlwaysOn(true)
            context.startForegroundService(
                Intent(context, AlwaysOnWakeWordService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            PersonalWakeWordStore(context).setAlwaysOn(false)
            context.startService(Intent(context, AlwaysOnWakeWordService::class.java).setAction(ACTION_STOP))
        }
    }
}
