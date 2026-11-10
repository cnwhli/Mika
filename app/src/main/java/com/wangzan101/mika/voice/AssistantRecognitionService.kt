package com.wangzan101.mika.voice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * VIA 规范要求数字助手同时登记一个 RecognitionService；这里只占位。
 *
 * 真正的识别在 [AssistantRecognizer] 里显式挑系统自带的识别服务完成，
 * 不经过这里 —— 否则本 App 一旦被登记为识别服务，就会绑回自己形成递归。
 * 万一有别的 App 误绑过来，明确回一个错误，不要静默占用麦克风。
 */
class AssistantRecognitionService : RecognitionService() {
    // AOSP 里这三个方法是 protected，参数类型是 RecognitionService.Callback。
    protected override fun onStartListening(
        recognizerIntent: Intent?, listener: RecognitionService.Callback?,
    ) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    protected override fun onCancel(listener: RecognitionService.Callback?) = Unit

    protected override fun onStopListening(listener: RecognitionService.Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
}
