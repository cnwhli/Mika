package com.nuoyi.agent.voice

import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import com.nuoyi.agent.core.voice.WakeSource

/**
 * 系统助手会话。生命周期由系统管：onShow 开一轮免手会话，onHide / finish 时彻底放掉麦克风和 TTS。
 * 未读完隐私告知的用户会在 [VoiceSessionHost] 的第一道闸被挡回去（见 core 的 VoiceDisclosure）。
 *
 * 身份固定回答除了播报，还会显示在会话窗口的 [VoiceIdentityCard] 上：这句话是代码常量，
 * 不该只从喇叭里过去、屏幕上什么都不留。内容视图可能比回答晚创建，所以先缓存一次回答。
 */
@Suppress("UNUSED_PARAMETER")
class AssistantVoiceSession(context: Context, launchArgs: Bundle?) :
    VoiceInteractionSession(context) {

    private val app = context.applicationContext
    private val consentStore = VoiceConsentStore(app)
    private var card: VoiceIdentityCard? = null
    private var pendingReply: Pair<String, String>? = null

    private val host = VoiceSessionHost(
        app,
        VoiceRuntime.capabilities,
        VoiceRuntime::wakeWords,
        AssistantTaskBridge(app),
        consentStore::read,
        onFinish = { finish() },
        onFixedReply = { question, answer -> showFixedReply(question, answer) },
    )

    override fun onCreateContentView(): View {
        val created = VoiceIdentityCard(app)
        card = created
        pendingReply?.let { created.show(it.first, it.second) }
        pendingReply = null
        return created.view
    }

    private fun showFixedReply(question: String, answer: String) {
        val shown = card
        if (shown == null) pendingReply = question to answer
        else shown.show(question, answer)
    }

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
