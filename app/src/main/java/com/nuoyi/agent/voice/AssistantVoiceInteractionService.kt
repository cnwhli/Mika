package com.nuoyi.agent.voice

import android.service.voice.VoiceInteractionService

/**
 * 公版的免手入口。用户在系统设置里把本产品选为「默认数字助手应用」后，
 * 由系统手势 / 电源键 / 耳机键唤起 [AssistantVoiceSession]，之后全程不用碰屏幕。
 *
 * 这里刻意**不启动任何常驻麦克风**：
 * - Android 12 起 createAlwaysOnHotwordDetector() 是 @SystemApi，第三方助手拿不到系统热词；
 * - 公版要对外分发，microphone 类型前台服务过不了商店的 FGS 声明。
 * 常驻热词只在 personal flavor 的 AlwaysOnWakeWordService 里，且仅自用侧载。
 */
class AssistantVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        // 系统绑定完成。不做常驻监听；会话由 AssistantSessionService 按需创建。
    }
}
