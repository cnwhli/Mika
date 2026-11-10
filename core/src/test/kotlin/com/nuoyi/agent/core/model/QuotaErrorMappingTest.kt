package com.nuoyi.agent.core.model

import com.nuoyi.agent.core.agent.userReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #416「模型的额度恢复了,但是 Nuoyi 还是显示额度用尽」的回归测试(只依赖现有类,可独立提交)。
 *
 * 成因之一:429 正文里出现 insufficient_quota 时,错误映射把它当成「账户余额耗尽」——
 * 不重试、还让用户去查余额;但那往往只是配额窗口,服务端还给了 Retry-After。
 * 修好后 429 一律按可恢复的限流处理,402 仍是账户额度但带上要等多久,提示文案不再像永久结论。
 */
class QuotaErrorMappingTest {
    @Test fun rateLimitBodyMentioningQuotaIsStillRetryable() {
        val body = """{"error":{"code":"insufficient_quota","message":"You exceeded your current quota, retry in 3s"}}"""
        val e = ModelHttp.openAiError(429, "3", body, "gemini-flash")
        assertTrue("429 必须仍是可重试的限流,而不是账户额度耗尽: $e", e is ModelError.RateLimited)
        assertEquals(3_000L, (e as ModelError.RateLimited).retryAfterMs)
    }

    @Test fun http402StillMeansAccountQuotaAndNowCarriesTheWait() {
        val e = ModelHttp.openAiError(402, "30", """{"error":{"message":"insufficient_quota"}}""", "m")
        assertTrue(e is ModelError.QuotaExceeded)
        assertEquals(30_000L, (e as ModelError.QuotaExceeded).retryAfterMs)
    }

    @Test fun existing403InsufficientQuotaBehaviourIsUnchanged() {
        val e = ModelHttp.openAiError(403, null, """{"error":{"code":"insufficient_quota"}}""", "m")
        assertTrue(e is ModelError.QuotaExceeded)
    }

    @Test fun failureMessageNoLongerSoundsPermanent() {
        val plain = userReason(ModelError.QuotaExceeded())
        assertTrue(plain, plain.contains("自动恢复"))
        assertTrue(plain, plain.contains("换一个模型"))

        val withWait = userReason(ModelError.QuotaExceeded(120_000L))
        assertTrue(withWait, withWait.contains("2 分钟"))
        assertTrue(withWait, withWait.contains("换一个模型"))
    }
}
