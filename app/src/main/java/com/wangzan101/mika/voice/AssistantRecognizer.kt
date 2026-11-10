package com.wangzan101.mika.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * 会话内的一次性识别。所有对 SpeechRecognizer 的操作都投到主线程（它要求如此）。
 *
 * 显式挑一个**别的包**的识别服务：本 App 为了符合 VIA 规范也登记了 RecognitionService
 * （占位实现），直接 createSpeechRecognizer(context) 有可能绑回自己，形成递归。
 */
class AssistantRecognizer(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onUnrecoverable: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var destroyed = false

    fun start() {
        if (destroyed || listening) return
        main.post {
            if (destroyed || listening) return@post
            val r = create() ?: run { onUnrecoverable(); return@post }
            recognizer = r
            listening = true
            r.setRecognitionListener(listener)
            r.startListening(intent())
        }
    }

    /** 关麦必须真的 cancel/destroy，只忽略回调是不够的：TTS 播报期间麦克风仍在采音。 */
    fun stop() {
        main.post {
            listening = false
            drop()
        }
    }

    fun destroy() {
        destroyed = true
        stop()
    }

    private fun drop() {
        runCatching { recognizer?.setRecognitionListener(null); recognizer?.cancel(); recognizer?.destroy() }
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        val others = SystemSpeech.services(context)
            .filter { it.open && !it.component.startsWith(context.packageName) }
            .mapNotNull { runCatching { ComponentName.unflattenFromString(it.component) }.getOrNull() }
        for (c in others) {
            runCatching { SpeechRecognizer.createSpeechRecognizer(context, c) }.getOrNull()?.let { return it }
        }
        return runCatching { SpeechRecognizer.createSpeechRecognizer(context) }.getOrNull()
    }

    private fun intent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.SIMPLIFIED_CHINESE.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        // 免手场景宁可早点收尾，也别让一段沉默把会话挂住
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            listening = false
            drop()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            onText(text)
        }

        override fun onError(error: Int) {
            listening = false
            drop()
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onText("") // 交给状态机的「没听清」分支
                else -> onUnrecoverable()
            }
        }
    }
}
