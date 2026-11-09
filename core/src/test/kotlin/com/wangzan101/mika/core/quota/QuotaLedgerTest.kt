package com.wangzan101.mika.core.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class Clock(var at: Long = 1_000_000L) {
    fun now(): Long = at
    fun advance(ms: Long) { at += ms }
}

class QuotaLedgerTest {
    private fun ledger(clock: Clock, windowMs: Long = 24 * 60 * 60_000L) =
        QuotaLedger(now = clock::now, usageWindowMs = windowMs)

    @Test fun unknownCandidateIsAvailable() {
        val l = QuotaLedger()
        assertTrue(l.usable("never-seen"))
        assertEquals(Availability.Available, l.status("never-seen").availability)
    }

    /** #416 的核心:额度状态永远带过期时刻,窗口一过自己复活,不需要用户重启 App 或手动清缓存。 */
    @Test fun coolingExpiresAutomatically() {
        val clock = Clock()
        val l = ledger(clock)
        val until = l.noteQuotaFailure("gemini", QuotaSignal(QuotaKind.RateLimited, 60_000L))
        assertEquals(1_060_000L, until)

        val cooling = l.status("gemini")
        assertEquals(Availability.Cooling, cooling.availability)
        assertEquals(60_000L, cooling.remainingMs)
        assertFalse(l.usable("gemini"))

        clock.advance(59_999L)
        assertEquals(Availability.Cooling, l.status("gemini").availability)
        assertEquals(1L, l.status("gemini").remainingMs)

        clock.advance(1L)
        assertTrue(l.usable("gemini"))
        assertNull(l.status("gemini").untilMs)
    }

    /** 服务端额度提前恢复(用户在后台充值、日界提前到):一次成功就立刻清掉旧冷却。 */
    @Test fun successClearsCooldownImmediately() {
        val clock = Clock()
        val l = ledger(clock)
        l.noteQuotaFailure("gemini", QuotaSignal(QuotaKind.WindowExhausted, null))
        assertFalse(l.usable("gemini"))

        clock.advance(10_000L)
        l.noteSuccess("gemini", 1_200, 300)
        assertTrue(l.usable("gemini"))

        val row = l.snapshot().single()
        assertEquals(1L, row.requests)
        assertEquals(1_200L, row.inputTokens)
        assertEquals(300L, row.outputTokens)
        assertEquals(clock.now(), row.lastSuccessMs)
    }

    @Test fun permanentFailureNeedsExplicitReset() {
        val clock = Clock()
        val l = ledger(clock)
        l.notePermanentFailure("bad-key", "auth")
        clock.advance(10 * 24 * 60 * 60_000L)
        assertEquals(Availability.Disabled, l.status("bad-key").availability)
        assertFalse(l.usable("bad-key"))

        // 用户改了 Key / 手动点了重试
        l.markAvailable("bad-key")
        assertTrue(l.usable("bad-key"))
    }

    /** 落盘重启也不会把一次旧的额度失败变成永久状态。 */
    @Test fun survivesEncodeDecodeAndStillExpires() {
        val clock = Clock()
        val l = ledger(clock)
        l.noteQuotaFailure("groq", QuotaSignal(QuotaKind.RateLimited, 30_000L))
        l.noteSuccess("local", 10, 5)
        val saved = l.encode()

        val restored = ledger(clock)
        restored.decode(saved)
        assertEquals(Availability.Cooling, restored.status("groq").availability)
        assertEquals(1L, restored.snapshot().first { it.id == "local" }.requests)

        clock.advance(30_000L)
        assertTrue(restored.usable("groq"))
        assertTrue(restored.status("groq").reason == null)
    }

    @Test fun brokenJsonDoesNotWipeTheLedgerIntoAnUnusableState() {
        val l = QuotaLedger()
        l.decode("not json")
        l.decode(null)
        l.decode("")
        assertTrue(l.usable("anything"))
    }

    @Test fun usageCountersResetWithTheWindow() {
        val clock = Clock()
        val l = ledger(clock, windowMs = 60_000L)
        l.noteSuccess("m", 100, 20)
        l.noteSuccess("m", 100, 20)
        assertEquals(2L, l.snapshot().single().requests)

        clock.advance(60_001L)
        l.noteSuccess("m", 5, 1)
        val row = l.snapshot().single()
        assertEquals(1L, row.requests)
        assertEquals(5L, row.inputTokens)
        assertEquals(1L, row.outputTokens)
    }

    @Test fun everyQuotaFailureHasAFiniteDeadline() {
        val l = QuotaLedger()
        for (kind in QuotaKind.entries) {
            val signal = QuotaSignal(kind, null)
            val until = l.noteQuotaFailure(kind.name, signal)
            val status = l.status(kind.name)
            assertEquals(Availability.Cooling, status.availability)
            assertTrue("${kind} 必须有有限的冷却时长", status.remainingMs!! > 0 && status.remainingMs!! < 60 * 60_000L)
            assertEquals(until, status.untilMs)
        }
    }

    @Test fun serverRetryAfterIsClamped() {
        val short = QuotaSignal(QuotaKind.RateLimited, 0L)
        assertEquals(QuotaSignal.MIN_RETRY_MS, short.suggestedCooldownMs)
        val huge = QuotaSignal(QuotaKind.PlanLimit, 10 * 24 * 60 * 60_000L)
        assertEquals(QuotaSignal.MAX_RETRY_MS, huge.suggestedCooldownMs)
    }
}
