package com.nuoyi.agent.callnotes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.nuoyi.agent.notify.NotificationCenter
import com.nuoyi.agent.notify.NuoyiNotification
import com.nuoyi.agent.notify.Tone

/**
 * 监听通话状态(需要 READ_PHONE_STATE)。第三方应用拿不到通话双方的声音,这里只负责:
 * 1) 检测来电 / 通话中 / 挂断;2) 记录一次通话的起止,落 [CallSessionStore];3) 把实时状态推进 [CallStateFlow],
 *    供灵动岛胶囊显示「通话中 00:42」;4) 挂断后发一条灵动岛通知,点一下去整理纪要。
 *
 * 宿主在 [com.nuoyi.agent.notify.DynamicIslandService](前台服务)里 start/stop,这样 App 退到后台也能持续感知通话。
 */
object CallStateMonitor {
    private var tm: TelephonyManager? = null
    private var listener: PhoneStateListener? = null
    private var currentSessionId: String? = null
    private var answerMs: Long = 0L

    fun currentSessionId(): String? = currentSessionId

    fun start(context: Context) {
        if (listener != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        val mgr = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        tm = mgr
        val l = object : PhoneStateListener() {
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                handle(state, phoneNumber, context)
            }
        }
        listener = l
        @Suppress("DEPRECATION")
        mgr.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
    }

    fun stop() {
        listener?.let { tm?.listen(it, PhoneStateListener.LISTEN_NONE) }
        listener = null
        tm = null
    }

    private fun handle(state: Int, phoneNumber: String?, context: Context) {
        val num = phoneNumber?.takeIf { it.isNotBlank() }
        when (state) {
            TelephonyManager.CALL_STATE_IDLE -> {
                val sid = currentSessionId
                currentSessionId = null
                if (sid != null) {
                    CallSessionStore.of(context).end(sid, System.currentTimeMillis())
                    // 通话结束:提示整理纪要(点通知进入通话纪要页)
                    NotificationCenter.post(context, NuoyiNotification(
                        id = "call-end-$sid",
                        title = "通话已结束",
                        text = "点此整理通话纪要",
                        tone = Tone.Info,
                        timeoutMillis = 0,
                        openRoute = "callnotes",
                    ))
                }
                CallStateFlow.update(CallState())
            }
            TelephonyManager.CALL_STATE_RINGING -> {
                CallStateFlow.update(CallState(
                    ringing = true,
                    number = num,
                    isIncoming = true,
                    startMs = System.currentTimeMillis(),
                ))
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                val wasRinging = CallStateFlow.state.value.ringing
                if (currentSessionId == null) {
                    currentSessionId = CallSessionStore.of(context).begin(num, !wasRinging, System.currentTimeMillis())
                    answerMs = System.currentTimeMillis()
                }
                CallStateFlow.update(CallState(
                    offhook = true,
                    number = num ?: CallStateFlow.state.value.number,
                    isIncoming = wasRinging,
                    startMs = answerMs,
                ))
            }
        }
    }
}
