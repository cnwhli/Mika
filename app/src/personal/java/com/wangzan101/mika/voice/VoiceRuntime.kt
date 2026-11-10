package com.wangzan101.mika.voice

import android.content.Context
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.voice.VoiceCapabilities
import com.wangzan101.mika.core.voice.VoiceEdition
import com.wangzan101.mika.core.voice.WakeWordResult
import com.wangzan101.mika.core.voice.WakeWordSettings

/**
 * 个人版能力集：唤醒词可以自己改，也允许常驻热词。
 * 仅自用侧载，不进公版 APK、也不对外分发（常驻麦克风过不了商店的 FGS 声明）。
 */
object VoiceRuntime : VoiceRuntimeApi {
    override val capabilities: VoiceCapabilities =
        VoiceCapabilities.forEdition(VoiceEdition.PERSONAL, BuildConfig.VOICE_FIXED_WAKE_WORD)

    @Volatile
    private var settings = WakeWordSettings(capabilities)

    init {
        check(BuildConfig.VOICE_CUSTOM_WAKE_WORD && BuildConfig.VOICE_ALWAYS_ON) {
            "personal flavor must enable custom wake word and always-on microphone"
        }
        check(BuildConfig.VOICE_FIXED_WAKE_WORD == VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD) {
            "brandWakeWord in app/build.gradle.kts must match core DEFAULT_FIXED_WAKE_WORD"
        }
    }

    /** 进程冷启动后把上次定的词读回来；不读也能用出厂固定词。 */
    fun restore(context: Context) = synchronized(this) {
        val saved = PersonalWakeWordStore(context).saved() ?: return@synchronized
        settings.set(saved)
    }

    override fun wakeWords(): List<String> = settings.wakeWords()

    override fun changeWakeWord(context: Context, requested: String): WakeWordResult {
        if (requested.isBlank()) {
            synchronized(this) { settings.reset() }
            PersonalWakeWordStore(context).clear()
            return WakeWordResult.Ok(capabilities.fixedWakeWord, custom = false)
        }
        val result = synchronized(this) { settings.set(requested) }
        if (result is WakeWordResult.Ok) PersonalWakeWordStore(context).save(result.word)
        return result
    }

    override fun alwaysOnEnabled(context: Context): Boolean = PersonalWakeWordStore(context).alwaysOn()

    override fun startAlwaysOn(context: Context) = AlwaysOnWakeWordService.start(context)

    override fun stopAlwaysOn(context: Context) = AlwaysOnWakeWordService.stop(context)
}
