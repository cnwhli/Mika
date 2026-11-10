package com.wangzan101.mika.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** 系统每次发起助手会话时绑定它；真正的会话逻辑在 [AssistantVoiceSession]。 */
class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AssistantVoiceSession(this, args)
}
