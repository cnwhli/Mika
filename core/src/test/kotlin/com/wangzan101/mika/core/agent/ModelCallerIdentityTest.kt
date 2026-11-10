package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.identity.IdentityGate
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.quota.QuotaRequirement
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.voice.ProductIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 身份硬闸装在模型调用层：命中「你是谁」时模型一次都不该被调用，
 * 回复必须逐字等于产品设定，且用量记零。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelCallerIdentityTest {
    private class CountingModel : ChatModel {
        override val name = "counting"
        override val capabilities = Capabilities(true, true, 128_000, reasoning = false)
        var calls = 0
            private set

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            return ModelReply(listOf(Part.Text("model said")), StopReason.EndTurn, Usage(9, 4))
        }
    }

    private fun user(text: String) = Message(Role.User, listOf(Part.Text(text)), Author.User)

    private fun caller(
        model: ChatModel,
        gate: (List<Message>) -> String? = IdentityGate::answerFor,
        onText: ((String) -> Unit)? = null,
    ): ModelCaller = ModelCaller(
        model, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE,
        { }, { 0L }, false, null, null, { QuotaRequirement() }, gate,
    ).also { it.reset("BASE") }

    private fun text(reply: ModelReply): String =
        reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    @Test fun askNeverCallsTheModelAndReturnsTheFixedReply() = runTest {
        val model = CountingModel()
        val spoken = mutableListOf<String>()
        val reply = caller(model, onText = { spoken += it })
            .ask(listOf(user("你是谁")), emptyList(), 1, onText = { spoken += it })

        assertEquals(0, model.calls)
        assertEquals(ProductIdentity.REPLY, text(reply))
        assertEquals(ProductIdentity.REPLY, spoken.single())
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(0, reply.usage?.inputTokens)
        assertEquals(0, reply.usage?.outputTokens)
    }

    @Test fun askWithModelAndRoutedAreCoveredToo() = runTest {
        val model = CountingModel()
        val c = caller(model)
        val history = listOf(user("请问你是谁呀"))

        assertEquals(ProductIdentity.REPLY, text(c.askWithModel(history, emptyList(), 1).first))
        assertEquals(ProductIdentity.REPLY, text(c.routed(history, emptyList(), StepTrack(), 1, { }).first))
        assertEquals(0, model.calls)
    }

    @Test fun englishQuestionIsAlsoFixed() = runTest {
        val model = CountingModel()
        val reply = caller(model).ask(listOf(user("Who are you?")), emptyList(), 1)
        assertEquals(ProductIdentity.REPLY, text(reply))
        assertEquals(0, model.calls)
    }

    @Test fun normalTasksStillReachTheModel() = runTest {
        val model = CountingModel()
        val reply = caller(model).ask(listOf(user("帮我打开微信")), emptyList(), 1)
        assertEquals(1, model.calls)
        assertEquals("model said", text(reply))
    }

    @Test fun gateCanBeDisabledForTests() = runTest {
        val model = CountingModel()
        val reply = caller(model, gate = { null }).ask(listOf(user("你是谁")), emptyList(), 1)
        assertEquals(1, model.calls)
        assertEquals("model said", text(reply))
    }

    @Test fun injectedAnswersAreHonouredWithoutTouchingTheModel() = runTest {
        val model = CountingModel()
        val reply = caller(model, gate = { "别的固定答复" }).ask(listOf(user("随便说点什么")), emptyList(), 1)
        assertEquals("别的固定答复", text(reply))
        assertTrue(model.calls == 0)
    }
}
