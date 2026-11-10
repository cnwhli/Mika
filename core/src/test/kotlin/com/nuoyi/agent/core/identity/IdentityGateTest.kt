package com.nuoyi.agent.core.identity

import com.nuoyi.agent.core.model.Author
import com.nuoyi.agent.core.model.Message
import com.nuoyi.agent.core.model.Part
import com.nuoyi.agent.core.model.Role
import com.nuoyi.agent.core.voice.ProductIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentityGateTest {
    private fun user(text: String) = Message(Role.User, listOf(Part.Text(text)), Author.User)
    private fun assistant(text: String) = Message(Role.Assistant, listOf(Part.Text(text)), Author.Mika)
    private fun tool(callId: String, content: String = "{\"ok\":true}") =
        Message(Role.Tool, listOf(Part.ToolResult(callId, content, false)), Author.Mika)

    @Test fun answersShortIdentityQuestion() {
        assertEquals(
            ProductIdentity.REPLY,
            IdentityGate.answerFor(listOf(user("你是谁"))),
        )
        assertEquals(
            ProductIdentity.REPLY,
            IdentityGate.answerFor(listOf(user("诺伊，你是谁呀"), user("who are you"))),
        )
    }

    @Test fun doesNotInterceptRealTasks() {
        listOf(
            "帮我打开微信", "帮我查一下这个人是谁", "你是谁帮我查天气",
            "诺伊，帮我查一下明天北京的天气", "取消", "到哪了",
        ).forEach { assertNull("不该拦下任务: $it", IdentityGate.answerFor(listOf(user(it)))) }
    }

    @Test fun usesTheMostRecentUserWordsOnly() {
        // 上一轮问过身份，但这一轮是正经任务：按最后一句判定
        assertNull(IdentityGate.answerFor(listOf(user("你是谁"), assistant(ProductIdentity.REPLY), user("打开设置"))))
        // 任务中途插话问身份：也要答
        assertEquals(
            ProductIdentity.REPLY,
            IdentityGate.answerFor(listOf(user("打开设置"), assistant("好"), tool("c1"), user("你是谁"))),
        )
    }

    @Test fun ignoresToolResultsAndAssistantNotes() {
        // 最后一条是工具结果，不是用户的话：不能把结果里的 JSON 当问句
        assertNull(IdentityGate.answerFor(listOf(user("打开设置"), tool("c1", "你是谁"))))
        assertNull(IdentityGate.answerFor(listOf(user("打开设置"), assistant("你是谁"))))
        assertNull(IdentityGate.answerFor(emptyList()))
        assertNull(IdentityGate.answerFor(listOf(tool("c1"))))
    }

    @Test fun userMessageWithOnlyAnImageIsNotAQuestion() {
        val shot = Message(Role.User, listOf(Part.Image("AAAA")), Author.User)
        assertNull(IdentityGate.answerFor(listOf(user("打开设置"), shot)))
        // 图片配一句身份问话仍然要答（取文本部分）
        val mixed = Message(Role.User, listOf(Part.Image("AAAA"), Part.Text("你是谁")), Author.User)
        assertEquals(ProductIdentity.REPLY, IdentityGate.answerFor(listOf(mixed)))
    }
}
