package com.nuoyi.agent.core.quota

/**
 * 一个额度池的完整装配:候选链 + 账本 + 路由器。
 *
 * App 层接入只需要三件事:
 * 1. 构造 [QuotaPool](候选来自用户自己配置的服务商,元数据可取 [FreeTierCatalog]),把 [router] 交给 AgentLoop;
 * 2. 用 [restore] / [persist] 把账本落盘,重启后冷却状态按同一套时间规则复活;
 * 3. 用 [snapshot] / [recoveryTimes] 画「额度池」页面,显示每个候选的用量、状态和倒计时。
 *
 * 这里不保存任何 Key、token 或账号:凭证仍在原有的 ProviderStore 里,[QuotaCandidate] 只引用现成的
 * [ChatModel] 实例。也因此这一层能放进纯 JVM 的 :core 模块,不依赖 Android。
 */
class QuotaPool(
    candidates: List<QuotaCandidate>,
    now: () -> Long = System::currentTimeMillis,
    onSwitch: ((from: String, to: String?, signal: QuotaSignal) -> Unit)? = null,
) {
    val ledger: QuotaLedger = QuotaLedger(now)
    val router: QuotaAwareRouter = QuotaAwareRouter(candidates, ledger, onSwitch)

    /** 从本地设置读回账本;格式不对或为空时安全忽略,不会把额度池清空。 */
    fun restore(text: String?): Unit = ledger.decode(text)

    fun persist(): String = ledger.encode()

    /**
     * 额度池页面的一行一个候选:包括从未调用过的(显示可用、用量 0),
     * 免得用户配好了却以为没生效。状态由 [QuotaLedger.status] 按当前时间即时计算,到点自动复活。
     */
    fun snapshot(): List<QuotaSnapshot> {
        val rows = ledger.snapshot().associateBy { it.id }
        return router.candidates.map { c ->
            rows[c.id] ?: QuotaSnapshot(c.id, ledger.status(c.id), 0, 0, 0, null, null)
        }
    }

    /** 每个候选最早可再试的时刻;没有冷却为 null。 */
    fun recoveryTimes(): Map<String, Long?> = router.recoveryTimes()

    /** 用户换了 Key、重置了订阅或点了「立即重试」:清掉这一条的状态。 */
    fun reset(id: String): Unit = ledger.markAvailable(id)

    /** 用户在设置里重排／删除了候选:整份账本从头开始。 */
    fun resetAll(): Unit = ledger.clear()
}
