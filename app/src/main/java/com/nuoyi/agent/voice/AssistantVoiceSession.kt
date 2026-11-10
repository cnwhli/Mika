package com.nuoyi.agent.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.nuoyi.agent.core.voice.WakeSource

/**
 * 系统助手会话。生命周期由系统管：onShow 开一轮免手会话，onHide / finish 时彻底放掉麦克风和 TTS。
 * 未读完隐私告知的用户会在 [VoiceSessionHost] 的第一道闸被挡回去（见 core 的 VoiceDisclosure）。
 */
@Suppress("UNUSED_PARAMETER")
class AssistantVoiceSession(context: Context, launchArgs: Bundle?) :
    VoiceInteractionSession(context) {

    private val app = context.applicationContext
    private val consentStore = VoiceConsentStore(app)
    private val host = VoiceSessionHost(
        app,
        VoiceRuntime.capabilities,
        VoiceRuntime::wakeWords,
        AssistantTaskBridge(app),
        consentStore::read,
        onFinish = { finish() },
    )

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 系统已经在手势 / 按键处把用户带进来了，不需要（也没有）第三方热词。
        host.start(WakeSource.SystemAssistant)
    }

    override fun onHide() {
        host.onHidden()
        host.release()
        super.onHide()
    }

    override fun onDestroy() {
        host.release()
        super.onDestroy()
    }
}
