package com.nuoyi.agent.core.agent

import com.nuoyi.agent.core.assembly.toolDispatcher
import com.nuoyi.agent.core.model.Author
import com.nuoyi.agent.core.model.Capabilities
import com.nuoyi.agent.core.model.ChatModel
import com.nuoyi.agent.core.model.Message
import com.nuoyi.agent.core.model.ModelError
import com.nuoyi.agent.core.model.ModelReply
import com.nuoyi.agent.core.model.Part
import com.nuoyi.agent.core.model.Role
import com.nuoyi.agent.core.model.StopReason
import com.nuoyi.agent.core.model.ToolSpec
import com.nuoyi.agent.core.model.Usage
import com.nuoyi.agent.core.quota.AllQuotaCandidatesExhausted
import com.nuoyi.agent.core.quota.Availability
import com.nuoyi.agent.core.quota.FreeTierCatalog
import com.nuoyi.agent.core.quota.QuotaAwareRouter
import com.nuoyi.agent.core.quota.QuotaCandidate
import com.nuoyi.agent.core.quota.QuotaPool
import com.nuoyi.agent.core.quota.QuotaRequirement
import com.nuoyi.agent.core.record.RunEvent
import com.nuoyi.agent.core.tool.FakeScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 额度池接进 [ModelCaller] 之后的端到端行为。重点是「不接额度池时行为不变」、
 * 「切换后返回的是真正承接请求的模型」,以及「额度恢复后会自动自愈」(issue #416)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelCallerQuotaTest {
    private class PoolModel(
        override val name: String,
        private var failTimes: Int = 0,
        private val error: () -> Throwable = { ModelError.RateLimited(2_000L) },
        toolCalls: Boolean = true,
        vision: Boolean = true,
    ) : ChatModel {
        override val capabilities = Capabilities(toolCalls, vision, 128_000, reasoning = false)
        var calls = 0
            private set

        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            if (failTimes > 0) { failTimes--; throw error() }
            return ModelReply(listOf(Part.Text("from-$name")), StopReason.EndTurn, Usage(11, 7))
        }
    }

    private val history = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))

    private fun caller(
        main: ChatModel,
        router: QuotaAwareRouter?,
        clock: () -> Long,
        events: MutableList<RunEvent>,
        retry: RetryPolicy = RetryPolicy.NONE,
        requirement: (List<Message>) -> QuotaRequirement = { QuotaRequirement() },
    ): ModelCaller = ModelCaller(
        main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, retry,
        { events += it }, clock, false, null, router, requirement,
    ).also { it.reset("BASE") }

    private fun replyText(reply: ModelReply): String =
        reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }

    @Test fun withoutPoolBehaviourIsUnchanged() = runTest {
        val events = mutableListOf<RunEvent>()
        val main = PoolModel("main")
        val c = caller(main, null, { 0L }, events)
        val (reply, used) = c.askWithModel(history, emptyList(), 1)
        assertEquals("from-main", replyText(reply))
        assertSame(main, used)
        assertEquals(1, main.calls)
        assertTrue(events.filterIsInstance<RunEvent.State>().none { it.name == "quota_switch" })
    }

    /** 一家额度用完不终止任务:立刻换下一家,并把用量和记录记到真正承接的模型上。 */
    @Test fun quotaFailureSwitchesAndReportsRealModel() = runTest {
        val events = mutableListOf<RunEvent>()
        val main = PoolModel("main", failTimes = 1, error = { ModelError.QuotaExceeded() })
        val backup = PoolModel("backup")
        val pool = QuotaPool(
            listOf(QuotaCandidate("main", main), QuotaCandidate("backup", backup)),
            now = { 0L },
        )
        val c = caller(main, pool.router, { 0L }, events)
        val (reply, used) = c.askWithModel(history, emptyList(), 1)

        assertSame(backup, used)
        assertEquals("from-backup", replyText(reply))
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "quota_switch" })
        assertEquals(Availability.Cooling, pool.snapshot().first { it.id == "main" }.status.availability)
        // 真实 usage 回填到承接者,而不是记到主模型头上
        assertEquals(11L, pool.snapshot().first { it.id == "backup" }.inputTokens)
        assertEquals(7L, pool.snapshot().first { it.id == "backup" }.outputTokens)
    }

    /** #416:冷却到期后自动回到首选模型,不需要用户手动「重试」或重启。 */
    @Test fun recoveredQuotaAutomaticallyReturnsToPreferredModel() = runTest {
        var at = 0L
        val main = PoolModel("main", failTimes = 1, error = { ModelError.RateLimited(2_000L) })
        val backup = PoolModel("backup")
        val pool = QuotaPool(
            listOf(QuotaCandidate("main", main), QuotaCandidate("backup", backup)),
            now = { at },
        )
        val c = caller(main, pool.router, { at }, mutableListOf())
        assertSame(backup, c.askWithModel(history, emptyList(), 1).second)
        assertEquals(Availability.Cooling, pool.snapshot().first { it.id == "main" }.status.availability)

        at += 2_000L
        assertSame(main, c.askWithModel(history, emptyList(), 2).second)
        assertEquals(Availability.Available, pool.snapshot().first { it.id == "main" }.status.availability)
    }

    /** App 标记了敏感步骤时,会把输入用于训练的免费档在发请求之前就被跳过。 */
    @Test fun sensitiveStepSkipsTrainingTierBeforeAnyRequest() = runTest {
        val free = PoolModel("free")
        val local = PoolModel("local")
        val pool = QuotaPool(
            listOf(
                QuotaCandidate("free", free, tier = FreeTierCatalog.byId("gemini-flash")),
                QuotaCandidate("local", local, tier = FreeTierCatalog.byId("local-ollama")),
            ),
            now = { 0L },
        )
        val c = caller(free, pool.router, { 0L }, mutableListOf(),
            requirement = { QuotaRequirement(sensitive = true) })
        assertSame(local, c.askWithModel(history, emptyList(), 1).second)
        assertEquals(0, free.calls)
        assertEquals(1, local.calls)
    }

    /** 切换优先同族,保住按模型分开存的压缩状态与提示词前缀缓存。 */
    @Test fun switchingPrefersSameFamilyAsConfiguredModel() = runTest {
        val main = PoolModel("main", failTimes = 1)
        val other = PoolModel("other")
        val same = PoolModel("same")
        val pool = QuotaPool(
            listOf(
                QuotaCandidate("main", main, family = "google"),
                QuotaCandidate("other", other, family = "other"),
                QuotaCandidate("same", same, family = "google"),
            ),
            now = { 0L },
        )
        val c = caller(main, pool.router, { 0L }, mutableListOf())
        assertSame(same, c.askWithModel(history, emptyList(), 1).second)
        assertEquals(0, other.calls)
    }

    /** 整池都在冷却:预算内等到最早恢复时刻再试一次,任务不中断。 */
    @Test fun allCoolingWithinBudgetWaitsThenSucceeds() = runTest {
        val main = PoolModel("main", failTimes = 1, error = { ModelError.RateLimited(5_000L) })
        val backup = PoolModel("backup", failTimes = 1, error = { ModelError.RateLimited(5_000L) })
        val pool = QuotaPool(
            listOf(QuotaCandidate("main", main), QuotaCandidate("backup", backup)),
            now = { currentTime },
        )
        val events = mutableListOf<RunEvent>()
        val c = caller(main, pool.router, { currentTime }, events,
            retry = RetryPolicy(taskWaitBudgetMs = 60_000L))
        val (reply, used) = c.askWithModel(history, emptyList(), 1)

        assertSame(main, used)
        assertEquals("from-main", replyText(reply))
        assertEquals(5_000L, currentTime)
        assertTrue(events.filterIsInstance<RunEvent.State>().any { it.name == "quota_pool_wait" })
    }

    /** 等待会超出任务预算时交出去,但带上恢复时刻,界面可以显示倒计时而不是笼统一句「额度用尽」。 */
    @Test fun exhaustedBeyondBudgetThrowsWithRecoveryTime() = runTest {
        val main = PoolModel("main", failTimes = 99, error = { ModelError.RateLimited(5_000L) })
        val backup = PoolModel("backup", failTimes = 99, error = { ModelError.RateLimited(5_000L) })
        val pool = QuotaPool(
            listOf(QuotaCandidate("main", main), QuotaCandidate("backup", backup)),
            now = { currentTime },
        )
        val c = caller(main, pool.router, { currentTime }, mutableListOf(),
            retry = RetryPolicy(taskWaitBudgetMs = 0L))
        val thrown = runCatching { c.askWithModel(history, emptyList(), 1) }.exceptionOrNull()
        assertTrue(thrown is AllQuotaCandidatesExhausted)
        assertEquals(5_000L, (thrown as AllQuotaCandidatesExhausted).recoveryAtMs)
    }
}
