package com.wangzan101.mika.voice

import android.content.Context
import com.wangzan101.mika.BuildConfig
import com.wangzan101.mika.core.voice.VoiceCapabilities
import com.wangzan101.mika.core.voice.VoiceEdition
import com.wangzan101.mika.core.voice.WakeWordReject
import com.wangzan101.mika.core.voice.WakeWordResult

/**
 * 公版能力集：固定唤醒词、不可自定义、无常驻麦克风。
 *
 * 两份实现分别放在 public / personal 源码集里，所以公版 APK **编译期就没有**
 * AlwaysOnWakeWordService 和本地热词引擎；init 里的 check 是第二道保险，
 * 防止打包脚本把个人版的 BuildConfig 开关误打到公版上。
 */
object VoiceRuntime : VoiceRuntimeApi {
    override val capabilities: VoiceCapabilities =
        VoiceCapabilities.forEdition(VoiceEdition.PUBLIC, BuildConfig.VOICE_FIXED_WAKE_WORD)

    init {
        check(!BuildConfig.VOICE_CUSTOM_WAKE_WORD && !BuildConfig.VOICE_ALWAYS_ON) {
            "public flavor must not enable custom wake word or always-on microphone"
        }
        check(capabilities.edition == VoiceEdition.PUBLIC)
    }

    override fun wakeWords(): List<String> = capabilities.wakeWords()

    /** 就算有人绕过设置页直接调到这个方法，也不会改掉公版的唤醒词。 */
    override fun changeWakeWord(context: Context, requested: String): WakeWordResult =
        WakeWordResult.Bad(WakeWordReject.EditionLocked)

    override fun alwaysOnEnabled(context: Context): Boolean = false

    /** 公版没有常驻服务，也没有 microphone 前台服务权限；开关点了也不会开麦。 */
    override fun startAlwaysOn(context: Context) = Unit

    override fun stopAlwaysOn(context: Context) = Unit
}
