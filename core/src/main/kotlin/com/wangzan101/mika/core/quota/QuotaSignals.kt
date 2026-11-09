package com.wangzan101.mika.core.quota

import com.wangzan101.mika.core.model.ModelError

/** 一次模型调用失败里「额度／限流」这件事的性质。都按会自己恢复处理,没有一种是永久结论。 */
enum class QuotaKind {
    /** 短时间请求太频繁(429),通常带 Retry-After。 */
    RateLimited,
    /** 免费档按分钟／按日的额度窗口用完,窗口过去就会恢复。 */
    WindowExhausted,
    /** 账户余额或套餐额度不足(402 / insufficient_quota)。 */
    AccountQuota,
    /** 用账号登录复用套餐额度时到了上限(如 ChatGPT 每 5 小时)。 */
    PlanLimit,
    /**
     * 上游 [ModelError.ModelNotAvailable] 里说的是「这个模型免费额度为零」:可能是按日额度耗尽,
     * 也可能是这个账号本来就没有免费档。两种都不该被当成永久结论,按窗口冷却后重试一次即可分辨。
     */
    QuotaZero,
}

/**
 * 从一次失败里认出的额度信号。[retryAfterMs] 是服务端明确说的要等多久(没有为 null),
 * [suggestedCooldownMs] 是没说时按 [kind] 给的保守默认值;两者都是「多久以后可以再试」,不是「永远不行」。
 */
data class QuotaSignal(
    val kind: QuotaKind,
    val retryAfterMs: Long?,
    val detail: String = "",
) {
    val suggestedCooldownMs: Long get() = retryAfterMs?.coerceIn(MIN_RETRY_MS, MAX_RETRY_MS)
        ?: DEFAULT_COOLDOWN_MS.getValue(kind)

    companion object {
        /** 服务端说等 0 秒也至少等一下,避免同一批任务在一个循环里把额度打满。 */
        const val MIN_RETRY_MS = 1_000L
        /** 服务端说等三天时不真的记三天:超过一小时的按一小时记,到期再问一次就好。 */
        const val MAX_RETRY_MS = 60 * 60_000L

        val DEFAULT_COOLDOWN_MS: Map<QuotaKind, Long> = mapOf(
            QuotaKind.RateLimited to 60_000L,
            // 免费档按分钟重置居多,五分钟后重试既不浪费请求也不会一直撞墙
            QuotaKind.WindowExhausted to 5 * 60_000L,
            QuotaKind.AccountQuota to 30 * 60_000L,
            QuotaKind.PlanLimit to 30 * 60_000L,
            QuotaKind.QuotaZero to 5 * 60_000L,
        )
    }
}

/**
 * 把模型层的错误翻译成额度信号;认不出返回 null(调用方照原来的语义处理,不切换候选)。
 *
 * 这里刻意不改动 [ModelError] 的分类:上游把 429 里「这个模型免费额度为零」认成
 * [ModelError.ModelNotAvailable] 是有意为之(换模型确实是最快的解法)。但「换模型」和
 * 「这个模型会自己恢复」不矛盾:本层把它记为 [QuotaKind.QuotaZero],由 [QuotaLedger]
 * 在窗口到期后自动复活,避免一次额度耗尽被记成永久状态一直显示「额度用尽」。
 */
object QuotaSignals {
    /** 说的是「免费额度／配额为零／配额耗尽」,而不是模型没开通、没权限或已下线。 */
    private val QUOTA_WORDS = Regex(
        """limit\s*:\s*0(?![\d.])|quotavalue|free\s+quota|quota\s+(?:exceeded|exhausted|limit)|额度|配额""",
        RegexOption.IGNORE_CASE,
    )

    fun of(e: Throwable): QuotaSignal? = when (e) {
        is ModelError.QuotaExceeded -> QuotaSignal(QuotaKind.AccountQuota, e.retryAfterMs, "账户额度不足")
        is ModelError.ChatGptLimitReached -> QuotaSignal(QuotaKind.PlanLimit, null, "套餐额度到上限")
        is ModelError.RateLimited -> QuotaSignal(QuotaKind.RateLimited, e.retryAfterMs, "请求被限流")
        // 402 在各适配器里已映射成 QuotaExceeded;这里兜住别的实现可能直接抛的 402
        is ModelError.Server -> if (e.status == 402) QuotaSignal(QuotaKind.AccountQuota, null, "HTTP 402") else null
        is ModelError.ModelNotAvailable -> if (QUOTA_WORDS.containsMatchIn(e.detail)) {
            QuotaSignal(QuotaKind.QuotaZero, null, e.detail)
        } else null
        else -> null
    }

    /** 给运行记录用的短名字。 */
    fun label(s: QuotaSignal): String = s.kind.name.lowercase()
}
