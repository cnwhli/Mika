package com.nuoyi.agent.core.quota

import com.nuoyi.agent.core.model.ModelAccessErrors
import com.nuoyi.agent.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #416 的另一半成因:任何一次额度失败都被当成没有期限的结论。
 *
 * 上游把 429「这个模型免费额度为零」认成 [ModelError.ModelNotAvailable](注释写「等多久也不会好」),
 * 这是有意为之且有单测保护,所以本层不推翻它;[QuotaSignals] 把它认成会恢复的额度信号,
 * 由 [QuotaLedger] 记成带过期时刻的冷却,窗口一过自动复活,不需要用户点任何按钮。
 */
class QuotaRegressionTest {
    @Test fun quotaZeroRecoversWithoutAnyUserAction() {
        val body = """{"error":{"code":429,"message":"Quota exceeded for metric: free_tier_requests, limit: 0","status":"RESOURCE_EXHAUSTED"}}"""
        val upstream = ModelAccessErrors.of(429, body, "gemini-flash")
        assertTrue("上游仍按自己的规则认成 ModelNotAvailable", upstream is ModelError.ModelNotAvailable)

        val signal = QuotaSignals.of(upstream!!)
        assertEquals(QuotaKind.QuotaZero, signal!!.kind)

        var at = 1_000_000L
        val ledger = QuotaLedger(now = { at })
        val until = ledger.noteQuotaFailure("gemini-flash", signal)
        assertEquals(Availability.Cooling, ledger.status("gemini-flash").availability)

        at = until
        assertEquals(Availability.Available, ledger.status("gemini-flash").availability)
        assertTrue(ledger.usable("gemini-flash"))
    }
}
