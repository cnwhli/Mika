package com.nuoyi.agent.voice

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 语音播报。**播完必须回调 onFinished**：上层靠这个回调重新开麦，
 * 而不是估算文本长度，否则半双工会把自己的播报收成下一句指令。
 */
class VoiceSpeaker(context: Context, private val onFinished: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var shutDown = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                runCatching { tts?.setLanguage(Locale.SIMPLIFIED_CHINESE) }
                pending?.let(::speakNow)
                pending = null
            } else {
                // 这台机器没有可用 TTS 引擎：别让会话卡在「正在说」，当作播完继续走。
                pending = null
                main.post { onFinished() }
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { main.post { onFinished() } }

            @Suppress("DEPRECATION")
            override fun onError(utteranceId: String?) { main.post { onFinished() } }
        })
    }

    fun speak(text: String) {
        if (shutDown) return
        if (!ready) { pending = text; return }
        speakNow(text)
    }

    private fun speakNow(text: String) {
        val result = runCatching {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        }.getOrNull()
        // 引擎拒绝这一次请求时也要放行，否则会话永远等不到「播完」。
        if (result != TextToSpeech.SUCCESS) main.post { onFinished() }
    }

    fun shutdown() {
        shutDown = true
        runCatching { tts?.stop(); tts?.shutdown() }
        tts = null
    }

    private companion object {
        const val UTTERANCE_ID = "voice_turn"
    }
}
