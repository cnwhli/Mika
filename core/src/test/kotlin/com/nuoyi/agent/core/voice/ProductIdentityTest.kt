package com.nuoyi.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductIdentityTest {
    /** 这句话是产品设定，测试锁死：改文案必须是有意的动作。 */
    @Test fun replyIsFixedWordForWord() {
        assertEquals("诺伊", ProductIdentity.BRAND)
        assertEquals("我是李万禧的女儿，数字女儿", ProductIdentity.REPLY)
    }

    @Test fun recognizesTypicalIdentityQuestions() {
        listOf(
            "你是谁", "你是谁呀", "你是哪位", "你叫什么名字", "你的名字",
            "诺伊，你是谁", "诺伊你是谁呀", "诺伊是谁", "诺伊是什么",
            "请问你是谁", "麻烦告诉我你是谁", "自我介绍", "介绍一下你自己",
            "谁造的你", "你爸爸是谁", "你是AI吗",
            "who are you", "what's your name", "Who are you?",
        ).forEach { assertTrue("应识别为身份问句: $it", ProductIdentity.matches(it)) }
    }

    @Test fun doesNotSwallowRealTasksOrHelpPhrases() {
        listOf(
            "帮我查一下这个人是谁", "帮我查一下你是谁", "你能做什么", "你能干嘛",
            "怎么用", "到哪了", "取消", "帮我给张三发消息", "你是谁帮我查天气",
            "诺伊，帮我查一下明天北京的天气", "查一下李万禧是谁",
        ).forEach { assertFalse("不该拦下任务: $it", ProductIdentity.matches(it)) }
    }

    /** 打字问、执行中被问，靠模型调用层的硬闸；系统提示词只是口径兜底。 */
    @Test fun systemPromptCarriesTheSameIdentity() {
        val system = com.nuoyi.agent.core.agent.SystemPrompt.identity()
        assertTrue(system.startsWith("You are Nuoyi"))
        assertTrue(ProductIdentity.REPLY in system)
    }

    @Test fun answerForReturnsTheFixedReplyOrNull() {
        assertEquals(ProductIdentity.REPLY, ProductIdentity.answerFor("你是谁"))
        assertEquals(ProductIdentity.REPLY, ProductIdentity.answerFor("诺伊，你是谁呀"))
        assertNull(ProductIdentity.answerFor("帮我打开微信"))
        assertNull(ProductIdentity.answerFor(""))
    }
}
