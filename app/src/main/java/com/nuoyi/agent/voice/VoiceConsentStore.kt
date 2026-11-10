package com.nuoyi.agent.voice

import android.content.Context
import com.nuoyi.agent.core.voice.VoiceConsent

/**
 * 对外分发时的告知授权状态。默认全否：没逐项确认之前 [VoiceSessionHost] 连麦都不开。
 * 只存本机 SharedPreferences，不上传、不进模型上下文。
 */
class VoiceConsentStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("voice_consent", Context.MODE_PRIVATE)

    fun read(): VoiceConsent = VoiceConsent(
        acknowledged = prefs.getBoolean(K_ACK, false),
        microphone = prefs.getBoolean(K_MIC, false),
        screenCapture = prefs.getBoolean(K_SCREEN, false),
        freeTierTrainingUnderstood = prefs.getBoolean(K_TRAINING, false),
        ownCredentialsConfirmed = prefs.getBoolean(K_OWN_KEY, false),
    )

    fun write(c: VoiceConsent) = prefs.edit()
        .putBoolean(K_ACK, c.acknowledged)
        .putBoolean(K_MIC, c.microphone)
        .putBoolean(K_SCREEN, c.screenCapture)
        .putBoolean(K_TRAINING, c.freeTierTrainingUnderstood)
        .putBoolean(K_OWN_KEY, c.ownCredentialsConfirmed)
        .apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val K_ACK = "ack"
        const val K_MIC = "mic"
        const val K_SCREEN = "screen"
        const val K_TRAINING = "training"
        const val K_OWN_KEY = "own_key"
    }
}
