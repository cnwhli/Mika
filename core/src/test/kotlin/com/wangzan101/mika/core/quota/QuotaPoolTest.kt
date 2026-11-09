package com.wangzan101.mika.core.quota

import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class StubModel(override val name: String = "m") : ChatModel {
    override val capabilities = Capabilities(true, true, 128_000, reasoning = false)
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(3, 2))
}

/** 装配层:App 只需要 QuotaPool 的这几个入口,状态必须能落盘、能按时间自愈。 */
class QuotaPoolTest {
    private fun pool(clock: () -> Long = { 0L }): QuotaPool = QuotaPool(
        listOf(QuotaCandidate("a", StubModel("a")), QuotaCandidate("b", StubModel("b"))),
        now = clock,
    )

    @Test fun snapshotListsEveryCandidateEvenIfNeverUsed() {
        val p = pool()
        val rows = p.snapshot()
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertTrue(rows.all { it.status.usable && it.requests == 0L && it.inputTokens == 0L })
        assertEquals(setOf("a", "b"), p.recoveryTimes().keys)
        assertTrue(p.recoveryTimes().values.all { it == null })
    }

    /** 重启后读回账本,冷却仍带时刻;时间一到自动复活,不会变成永久的「额度用尽」。 */
    @Test fun coolingSurvivesPersistAndRevivesByClock() {
        var at = 1_000L
        val p = pool { at }
        p.ledger.noteSuccess("a")
        p.router.noteUsage("a", 10L, 5L)
        assertEquals(10L, p.snapshot().first { it.id == "a" }.inputTokens)

        p.ledger.noteQuotaFailure("a", QuotaSignal(QuotaKind.RateLimited, 2_000L))
        assertEquals(Availability.Cooling, p.snapshot().first { it.id == "a" }.status.availability)
        val saved = p.persist()

        val restored = pool { at }
        restored.restore(saved)
        assertEquals(Availability.Cooling, restored.snapshot().first { it.id == "a" }.status.availability)
        assertEquals(2_000L, restored.snapshot().first { it.id == "a" }.status.remainingMs)

        at += 2_000L
        assertEquals(Availability.Available, restored.snapshot().first { it.id == "a" }.status.availability)
        assertEquals(null, restored.recoveryTimes()["a"])

        restored.ledger.noteQuotaFailure("a", QuotaSignal(QuotaKind.AccountQuota, null))
        restored.reset("a")
        assertEquals(Availability.Available, restored.snapshot().first { it.id == "a" }.status.availability)
    }

    /** 读档内容坏了就当空账本,不能因为一次落盘异常把整个额度池清空。 */
    @Test fun malformedRestoreIsIgnored() {
        val p = pool()
        p.ledger.noteSuccess("a")
        p.restore("{not json")
        assertTrue(p.snapshot().all { it.status.usable && it.requests == 0L })

        p.resetAll()
        assertTrue(p.snapshot().all { it.status.usable })
    }

    /** 回填用量只累加 token,不会把一次请求数重复记两遍。 */
    @Test fun noteUsageDoesNotDoubleCountRequests() {
        val p = pool()
        p.ledger.noteSuccess("a")
        p.router.noteUsage("a", 7L, 3L)
        val row = p.snapshot().first { it.id == "a" }
        assertEquals(1L, row.requests)
        assertEquals(7L, row.inputTokens)
        assertEquals(3L, row.outputTokens)

        p.router.noteUsage("unknown-id", 100L, 100L)
        assertTrue(p.snapshot().none { it.id == "unknown-id" })
    }
}
