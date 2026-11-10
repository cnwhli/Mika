package com.nuoyi.agent.voice

import android.content.Context

/** 个人版专用：自定义唤醒词与常驻开关，只存本机。公版源码集里没有这个类。 */
class PersonalWakeWordStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("personal_wake_word", Context.MODE_PRIVATE)

    fun saved(): String? = prefs.getString(K_WORD, null)?.takeIf { it.isNotBlank() }

    fun save(word: String) = prefs.edit().putString(K_WORD, word).apply()

    fun clear() = prefs.edit().remove(K_WORD).apply()

    fun alwaysOn(): Boolean = prefs.getBoolean(K_ALWAYS_ON, false)

    fun setAlwaysOn(enabled: Boolean) = prefs.edit().putBoolean(K_ALWAYS_ON, enabled).apply()

    private companion object {
        const val K_WORD = "word"
        const val K_ALWAYS_ON = "always_on"
    }
}
