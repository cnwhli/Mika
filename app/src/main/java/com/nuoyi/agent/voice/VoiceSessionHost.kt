package com.nuoyi.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.nuoyi.agent.core.voice.VoiceCapabilities
import com.nuoyi.agent.core.voice.VoiceConsent
import com.nuoyi.agent.core.voice.VoiceEffect
import com.nuoyi.agent.core.voice.VoiceSessionController
import com.nuoyi.agent.core.voice.VoiceSessionState
import com.nuoyi.agent.core.voice.VoiceTaskBridge
import com.nuoyi.agent.core.voice.WakeSource

/**
 * 与入口无关的语音宿主：把状态机、识别、TTS、任务派发按 [VoiceEffect] 顺序执行。
 * 系统助手会话（公版）和常驻热词服务（个人版）都用同一个 Host，
 * 差别只在谁调 [start] 以及 [WakeSource] 是什么。
 */
class VoiceSessionHost(
    context: Context,
    capabilities: VoiceCapabilities,
    wakeWords: () -> List<String>,
    private val tasks: VoiceTaskBridge,
    consent: () -> VoiceConsent,
    private val onFinish: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val controller = VoiceSessionController(capabilities, wakeWords, tasks, consent)
    private val speaker = VoiceSpeaker(context) { run(controller.onSpeechDone()) }
    private val recognizer = AssistantRecognizer(
        context,
        onText = { run(controller.onUtterance(it)) },
        onUnrecoverable = { run(controller.onHide()) },
    )
    private var released = false
    private var startedAtMs = 0L

    /** 免手会话不能靠「用户会点一下」来收场：定期检查静默超时和会话总时长。 */
    private val watchdog = object : Runnable {
        override fun run() {
            if (released) return
            run(controller.onTimeout())
            if (controller.state == VoiceSessionState.WaitingTask &&
                SystemClock.elapsedRealtime() - startedAtMs > MAX_SESSION_MS
            ) {
                run(controller.onTaskStillRunning())
            }
            // 上面的 Finish 可能已经把宿主释放了，别再把自己排回队列。
            if (released) return
            main.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    fun start(source: WakeSource) {
        startedAtMs = SystemClock.elapsedRealtime()
        run(controller.onWake(source))
        main.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /** 任务完成时由上层接进来；这是免手闭环的「做完主动告诉你」。 */
    fun onTaskResult(summary: String) = run(controller.onTaskFinished(summary))

    /** 会话活不了那么久时的体面收场。 */
    fun onTaskStillRunning() = run(controller.onTaskStillRunning())

    fun onHidden() = run(controller.onHide())

    fun release() {
        released = true
        main.removeCallbacks(watchdog)
        recognizer.destroy()
        speaker.shutdown()
    }

    private fun run(effects: List<VoiceEffect>) {
        if (effects.isEmpty()) return
        if (Looper.myLooper() == Looper.getMainLooper()) execute(effects)
        else main.post { execute(effects) }
    }

    private fun execute(effects: List<VoiceEffect>) {
        if (released) return
        for (e in effects) when (e) {
            is VoiceEffect.SetMic -> if (e.enabled) recognizer.start() else recognizer.stop()
            is VoiceEffect.Speak -> speaker.speak(e.text)
            is VoiceEffect.StartTask -> tasks.start(e.goal)
            is VoiceEffect.Steer -> tasks.steer(e.text)
            VoiceEffect.CancelTask -> tasks.cancel()
            VoiceEffect.Finish -> {
                release()
                onFinish()
                return
            }
            is VoiceEffect.Note -> Log.d(TAG, e.tag)
        }
    }

    private companion object {
        const val TAG = "VoiceSession"
        const val WATCHDOG_INTERVAL_MS = 3_000L
        /** 一次免手会话最多挂五分钟；任务还没完就播一句，别把系统助手会话占死。 */
        const val MAX_SESSION_MS = 300_000L
    }
}
