package com.nuoyi.agent.core.identity

import com.nuoyi.agent.core.model.Author
import com.nuoyi.agent.core.model.Message
import com.nuoyi.agent.core.model.Part
import com.nuoyi.agent.core.model.Role
import com.nuoyi.agent.core.voice.ProductIdentity

/**
 * 产品身份的最后一道闸，装在**模型调用层**。
 *
 * 语音链路有自己的短路（直接 TTS），但用户还可以从输入框、通知快捷回复、channel、
 * trigger、任务中插话等入口问「你是谁」。这些路径最终都要把历史交给模型，所以在这里
 * 统一判定：只要最近一句用户亲口说的话是身份问句，就替模型回答，请求根本不发出去。
 *
 * 放在这一层而不是某个 UI 入口，是为了保证「任何入口都一字不差」——新加的入口默认
 * 也在闸后，不需要各自记得实现一遍；也不花 token、不受模型服务商影响、不受提示词漂移影响。
 */
object IdentityGate {

    /**
     * 从整段历史里取用户最近亲口说的一句话，命中身份问句就返回 [ProductIdentity.REPLY]。
     * 工具结果、附注、屏幕截图都不是用户的话，不会被当成问句；任务句更不会被误拦。
     */
    fun answerFor(history: List<Message>): String? {
        val last = history.lastOrNull { m ->
            m.role == Role.User && m.author == Author.User && m.parts.any { it is Part.Text }
        } ?: return null
        val said = last.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
        return ProductIdentity.answerFor(said)
    }
}
