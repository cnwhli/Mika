package com.wangzan101.mika.core.quota

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class Ticker(var at: Long = 1_000_000L) {
    fun now(): Long = at
    fun advance(ms: Long) { at += ms }
}

private class FakeModel(
    override val name: String,
    toolCalls: Boolean = true,
    vision: Boolean = true,
    contextTokens: Int = 128_000,
    var failure: Throwable? = null,
) : ChatModel {
    override val capabilities = Capabilities(toolCalls, vision, contextTokens, reasoning = false)
    var calls = 0
        private set

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        calls++
        failure?.let { throw it }
        return ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(10, 5))
    }
}

private fun candidate(id: String, model: ChatModel, tier: FreeTier? = null, family: String = "", allowSensitive: Boolean = false) =
    QuotaCandidate(id, model, tier, family, allowSensitive)

private val REQ = QuotaRequirement()

class QuotaAwareRouterTest {
    @Test fun firstHealthyCandidateIsUsed() = runTest {
        val a = FakeModel("a")
        val b = FakeModel("b")
        val router = QuotaAwareRouter(listOf(candidate("a", a), candidate("b", b)), QuotaLedger())
        val (reply, used) = router.complete("s", emptyList(), emptyList(), REQ)
        assertEquals("ok", (reply.parts.single() as Part.Text).text)
        assertSame(a, used.model)
        assertEquals(1, a.calls)
        assertEquals(0, b.calls)
    }

    /** 一家额度用完不终止任务:立刻切下一家,并把上一家按时间冷却(而不是永久标记)。 */
    @Test fun quotaExceededSwitchesToNextCandidate() = runTest {
        val clock = Ticker()
        val ledger = QuotaLedger(now = clock::now)
        val a = FakeModel("a", failure = ModelError.QuotaExceeded())
        val b = FakeModel("b")
        val switches = mutableListOf<Pair<String, String?>>()
        val router = QuotaAwareRouter(
            listOf(candidate("a", a), candidate("b", b)), ledger,
            onSwitch = { from, to, _ -> switches += from to to },
        )
        val (_, used) = router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) }
        assertSame(b, used.model)
        assertEquals(listOf("a" to "b"), switches)
        assertEquals(Availability.Cooling, ledger.status("a").availability)
        assertEquals(Availability.Available, ledger.status("b").availability)

        // 冷却过后 a 重新排到最前
        clock.advance(QuotaSignal.DEFAULT_COOLDOWN_MS.getValue(QuotaKind.AccountQuota))
        a.failure = null
        val (_, back) = router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) }
        assertSame(a, back.model)
    }

    @Test fun rateLimitAndQuotaZeroBothCauseSwitch() = runTest {
        for (e in listOf(
            ModelError.RateLimited(2_000L),
            ModelError.ModelNotAvailable("m", "Quota exceeded for metric: free_tier_requests, limit: 0"),
            ModelError.ChatGptLimitReached(),
        )) {
            val a = FakeModel("a", failure = e)
            val b = FakeModel("b")
            val router = QuotaAwareRouter(listOf(candidate("a", a), candidate("b", b)), QuotaLedger())
            val (_, used) = router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) }
            assertSame(b, used.model)
        }
    }

    @Test fun capabilityMismatchIsSkippedBeforeAnyRequest() = runTest {
        val noTools = FakeModel("no-tools", toolCalls = false)
        val noVision = FakeModel("no-vision", vision = false)
        val small = FakeModel("small", contextTokens = 4_000)
        val good = FakeModel("good")
        val router = QuotaAwareRouter(
            listOf(candidate("no-tools", noTools), candidate("no-vision", noVision), candidate("small", small), candidate("good", good)),
            QuotaLedger(),
        )
        val (_, used) = router.complete("s", emptyList(), emptyList(), QuotaRequirement(minContextTokens = 32_000))
        assertSame(good, used.model)
        assertEquals(0, noTools.calls + noVision.calls + small.calls)
        assertEquals(1, good.calls)
    }

    /** GUI Agent 每步都上传整屏截图:免费档若允许拿输入做训练,敏感步骤不能交给它。 */
    @Test fun sensitiveStepsSkipTrainingTiers() = runTest {
        val training = FreeTierCatalog.byId("gemini-flash")!!
        assertTrue(training.dataUsedForTraining)
        val a = FakeModel("a")
        val local = FakeModel("local")
        val router = QuotaAwareRouter(
            listOf(candidate("a", a, tier = training), candidate("local", local, tier = FreeTierCatalog.byId("local-ollama"))),
            QuotaLedger(),
        )
        val (_, used) = router.complete("s", emptyList(), emptyList(), REQ.copy(sensitive = true))
        assertSame(local, used.model)
        assertEquals(0, a.calls)

        // 非敏感步骤正常用免费档
        val (_, normal) = router.complete("s", emptyList(), emptyList(), REQ.copy(sensitive = false))
        assertSame(a, normal.model)
    }

    @Test fun explicitlyAllowedSensitiveTierIsUsable() = runTest {
        val a = FakeModel("a")
        val router = QuotaAwareRouter(
            listOf(candidate("a", a, tier = FreeTierCatalog.byId("gemini-flash"), allowSensitive = true)),
            QuotaLedger(),
        )
        val (_, used) = router.complete("s", emptyList(), emptyList(), REQ.copy(sensitive = true))
        assertSame(a, used.model)
    }

    /** 换族会让按模型分开存的压缩状态和提示词前缀缓存失效:优先在同族里换。 */
    @Test fun sameFamilyIsPreferredWhenSwitching() = runTest {
        val a = FakeModel("a", failure = ModelError.QuotaExceeded())
        val b = FakeModel("b")
        val c = FakeModel("c")
        val router = QuotaAwareRouter(
            listOf(candidate("a", a, family = "google"), candidate("b", b, family = "other"), candidate("c", c, family = "google")),
            QuotaLedger(),
        )
        val (_, used) = router.complete("s", emptyList(), emptyList(), REQ.copy(preferFamily = "google"))
        assertSame(c, used.model)
        assertEquals(0, b.calls)
    }

    @Test fun allExhaustedReportsEarliestRecoveryAndRecoversWithTime() = runTest {
        val clock = Ticker()
        val ledger = QuotaLedger(now = clock::now)
        val a = FakeModel("a", failure = ModelError.RateLimited(60_000L))
        val b = FakeModel("b", failure = ModelError.RateLimited(5_000L))
        val router = QuotaAwareRouter(listOf(candidate("a", a), candidate("b", b)), ledger)
        val thrown = runCatching { router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) } }.exceptionOrNull()
        assertTrue(thrown is AllQuotaCandidatesExhausted)
        val exhausted = thrown as AllQuotaCandidatesExhausted
        assertEquals(listOf("a", "b"), exhausted.tried)
        assertEquals(clock.now() + 5_000L, exhausted.recoveryAtMs)
        assertEquals(setOf("ratelimited"), exhausted.reasons.values.toSet())
        assertEquals(clock.now() + 5_000L, router.recoveryTimes()["b"])

        // 到点自动复活:不需要用户做任何操作
        clock.advance(5_000L)
        b.failure = null
        val (_, used) = router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) }
        assertSame(b, used.model)
    }

    /** 非额度错误按原来的语义抛出,不能被「换个模型试试」悄悄掩盖掉。 */
    @Test fun nonQuotaErrorIsRethrownWithoutSwitching() = runTest {
        for (e in listOf(ModelError.Auth(), ModelError.ModelNotFound("m"), ModelError.BadResponse("x"))) {
            val a = FakeModel("a", failure = e)
            val b = FakeModel("b")
            val router = QuotaAwareRouter(listOf(candidate("a", a), candidate("b", b)), QuotaLedger())
            val thrown = runCatching { router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) } }.exceptionOrNull()
            assertSame(e, thrown)
            assertEquals(0, b.calls)
        }
    }

    @Test fun cancellationIsNotTreatedAsQuotaFailure() = runTest {
        val stop = CancellationException("user cancelled")
        val a = FakeModel("a", failure = stop)
        val b = FakeModel("b")
        val router = QuotaAwareRouter(listOf(candidate("a", a), candidate("b", b)), QuotaLedger())
        val thrown = runCatching { router.run(REQ) { it.model.complete("s", emptyList(), emptyList()) } }.exceptionOrNull()
        assertSame(stop, thrown)
        assertEquals(0, b.calls)
        assertNull(router.recoveryTimes()["a"])
    }

    @Test fun duplicateCandidateIdsAreRejected() {
        val m = FakeModel("m")
        val ok = runCatching { QuotaAwareRouter(listOf(candidate("x", m), candidate("x", m)), QuotaLedger()) }
        assertTrue(ok.exceptionOrNull() is IllegalArgumentException)
    }
}
