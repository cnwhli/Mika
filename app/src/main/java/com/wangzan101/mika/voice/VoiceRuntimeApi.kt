package com.wangzan101.mika.voice

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.wangzan101.mika.core.voice.VoiceCapabilities
import com.wangzan101.mika.core.voice.WakeWordResult

/**
 * 两个 flavor 各给一份实现（public / personal），main 只认这个接口。
 * 公版的实现里没有、也不可能有本地热词引擎；这不是靠 UI 藏输入框，
 * 而是 [WakeWordResult] 在运行时就拒绝，编译期公版 APK 里也没有那段代码。
 */
interface VoiceRuntimeApi {
    val capabilities: VoiceCapabilities
    fun wakeWords(): List<String>
    fun changeWakeWord(context: Context, requested: String): WakeWordResult
    fun alwaysOnEnabled(context: Context): Boolean

    /** 常驻热词开关。公版实现是空操作，main 层因此不需要引用个人版的服务类。 */
    fun startAlwaysOn(context: Context)
    fun stopAlwaysOn(context: Context)
}

/** 系统默认数字助手的设置与状态。App 自己无法申请这个角色，只能引导用户去选。 */
object AssistantSetup {
    const val EXTRA_OPEN_VOICE_SETTINGS = "open_voice_settings"

    fun defaultAppsSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 有些 ROM 把助手放在「语音输入 / 数字助手」单独一页。 */
    fun voiceInputSettings(): Intent =
        Intent(Settings.ACTION_VOICE_INPUT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun isDefaultAssistant(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else false
    }.getOrDefault(false)
}
