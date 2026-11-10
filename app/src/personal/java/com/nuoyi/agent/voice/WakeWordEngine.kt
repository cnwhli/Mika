package com.nuoyi.agent.voice

import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * 本地热词的抽象。换成 Porcupine / openWakeWord / sherpa-onnx 等低功耗 KWS 时，
 * 只要实现这两个方法，[AlwaysOnWakeWordService] 不用改。
 */
interface WakeWordEngine {
    fun start(words: List<String>, onHit: (String) -> Unit)
    fun stop()
}

/**
 * 零依赖的软件热词：短窗口轮询系统识别，文本里命中唤醒词就算叫醒。
 *
 * 说清楚代价：**这不是 DSP 低功耗热词**，会周期性开麦、耗电明显，且锁屏 / 后台时
 * Android 14+ 可能掐断麦克风。它只适合自用侧载的个人版；要真正低功耗常驻，
 * 请换成自带模型的本地 KWS（注意其许可证与模型分发条款），别拿这个上商店。
 */
class SampledWakeWordEngine(context: Context) : WakeWordEngine {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var words: List<String> = emptyList()
    private var onHit: ((String) -> Unit)? = null

    private val recognizer = AssistantRecognizer(
        app,
        onText = ::handle,
        onUnrecoverable = ::retry,
    )

    override fun start(words: List<String>, onHit: (String) -> Unit) {
        running = true
        this.words = words.filter { it.isNotBlank() }.sortedByDescending { it.length }
        this.onHit = onHit
        recognizer.start()
    }

    override fun stop() {
        running = false
        onHit = null
        main.removeCallbacksAndMessages(null)
        recognizer.stop()
    }

    private fun handle(text: String) {
        if (!running) return
        val trimmed = text.trim()
        val hit = words.firstOrNull { trimmed.startsWith(it, ignoreCase = true) }
        if (hit != null) {
            // 命中后立刻把麦克风让给对话会话，避免两边同时抢录音。
            running = false
            recognizer.stop()
            onHit?.invoke(hit)
            return
        }
        retry()
    }

    private fun retry() {
        if (!running) return
        main.postDelayed({ if (running) recognizer.start() }, WINDOW_GAP_MS)
    }

    private companion object {
        /** 两个短窗口之间的间隔；调小更灵敏、更费电。 */
        const val WINDOW_GAP_MS = 1_200L
    }
}
