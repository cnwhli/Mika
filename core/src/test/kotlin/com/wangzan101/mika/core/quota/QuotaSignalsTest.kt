package com.wangzan101.mika.core.quota

import com.wangzan101.mika.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaSignalsTest {
    @Test fun recognisesEveryRecoverableQuotaError() {
        assertEquals(QuotaKind.AccountQuota, QuotaSignals.of(ModelError.QuotaExceeded())!!.kind)
        assertEquals(QuotaKind.PlanLimit, QuotaSignals.of(ModelError.ChatGptLimitReached())!!.kind)
        assertEquals(QuotaKind.RateLimited, QuotaSignals.of(ModelError.RateLimited(2_000L))!!.kind)
        assertEquals(2_000L, QuotaSignals.of(ModelError.RateLimited(2_000L))!!.retryAfterMs)
        assertEquals(QuotaKind.AccountQuota, QuotaSignals.of(ModelError.Server(402))!!.kind)
    }

    /** 上游把 429「这个模型免费额度为零」认成 ModelNotAvailable;本层把它当作会恢复的额度信号。 */
    @Test fun quotaZeroIsTemporaryEvenThoughUpstreamCallsItNotAvailable() {
        val gemini = """Quota exceeded for metric: generate_content_free_tier_requests, limit: 0, model: gemini-x"""
        val s = QuotaSignals.of(ModelError.ModelNotAvailable("gemini-x", gemini))
        assertNotNull(s)
        assertEquals(QuotaKind.QuotaZero, s!!.kind)
        assertEquals(gemini, s.detail)

        val quotaValue = """{"quotaValue":"0"}"""
        assertEquals(QuotaKind.QuotaZero, QuotaSignals.of(ModelError.ModelNotAvailable("m", quotaValue))!!.kind)
    }

    @Test fun genuinelyPermanentErrorsAreNotQuotaSignals() {
        assertNull(QuotaSignals.of(ModelError.Auth()))
        assertNull(QuotaSignals.of(ModelError.ModelNotFound("m")))
        assertNull(QuotaSignals.of(ModelError.ToolsUnsupported("m")))
        assertNull(QuotaSignals.of(ModelError.ContentRefused()))
        assertNull(QuotaSignals.of(ModelError.Network()))
        assertNull(QuotaSignals.of(ModelError.Server(500)))
        // 模型没开通、已下线:和额度无关,不该被换候选掩盖
        assertNull(QuotaSignals.of(ModelError.ModelNotAvailable("m", "not activated")))
        assertNull(QuotaSignals.of(ModelError.ModelNotAvailable("m", "has been retired")))
    }

    @Test fun defaultCooldownsAreShortAndFinite() {
        for (kind in QuotaKind.entries) {
            val ms = QuotaSignal(kind, null).suggestedCooldownMs
            assertNotNull(ms)
            org.junit.Assert.assertTrue("$kind 冷却应在一小时以内", ms in 1_000L..3_600_000L)
        }
    }

    @Test fun labelsAreStableForRunRecords() {
        assertEquals("ratelimited", QuotaSignals.label(QuotaSignal(QuotaKind.RateLimited, null)))
        assertEquals("quotazero", QuotaSignals.label(QuotaSignal(QuotaKind.QuotaZero, null)))
    }
}
