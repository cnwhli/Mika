package com.wangzan101.mika.core.quota

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.ToolSpec
import kotlinx.coroutines.CancellationException

/**
 * 候选链里的一项:一个可以真正发起调用的模型 + 它的额度／隐私元数据。
 * [id] 在同一个额度池里唯一(一般是「服务商:模型:Key 后缀」),账本按它记状态。
 * [family] 是模型族(如同一家的同一系模型):压缩状态和提示词前缀按 [ChatModel] 分开存,
 * 换族会让前缀缓存失效,所以优先在同一族里换,而不是一上来就跳到别家。
 */
data class QuotaCandidate(
    val id: String,
    val model: ChatModel,
    val tier: FreeTier? = null,
    val family: String = "",
    /** 用户明确同意让这一家处理敏感截图时为真;默认按 [FreeTier.dataUsedForTraining] 从严。 */
    val allowSensitive: Boolean = false,
) {
    /** 这一步是否允许处理带密码、支付、私密聊天的内容。 */
    val handlesSensitive: Boolean get() = allowSensitive || tier?.dataUsedForTraining != true
}

/** 这一步对模型的要求。默认按主角色的最坏情况(要调工具、要看图)。 */
data class QuotaRequirement(
    val needsTools: Boolean = true,
    val hasImages: Boolean = true,
    val minContextTokens: Int = 0,
    /** 这一步的上下文里有敏感内容:跳过会把数据用于训练的免费档。 */
    val sensitive: Boolean = false,
    /** 上一步用的模型族,用来优先在同源候选里切换。 */
    val preferFamily: String? = null,
)

/**
 * 候选都用尽时抛出。[recoveryAtMs] 是最早能再试的时刻(取所有被冷却候选里最早的那个),
 * 界面可以据此显示「约 x 分钟后自动重试」,而不是笼统一句「额度用尽」。
 */
class AllQuotaCandidatesExhausted(
    val tried: List<String>,
    val recoveryAtMs: Long?,
    val reasons: Map<String, String>,
) : Exception(
    if (recoveryAtMs == null) "所有候选模型都不可用" else "所有候选模型的额度都在冷却中,最早会在指定时刻后自动恢复",
)

/**
 * 额度感知的候选链路由:按用户排好的顺序,跳过能力不匹配、正在冷却、以及不允许处理敏感内容的候选;
 * 一次调用撞上额度／限流错误时,把这一项冷却并**立刻**试下一家,不终止任务。
 *
 * 和现有 [com.wangzan101.mika.core.agent.ModelRouter] 不冲突:那个管「主模型 ↔ 轻量模型」的分工,
 * 这个管「同一个角色下有多个可替换的服务商」。接线时把一次模型调用交给 [run] 即可,
 * 现有的重试、压缩、上下文恢复逻辑都不用改。
 *
 * 非额度错误(Key 不对、模型不存在、内容被拒、解析失败…)原样抛出,交回原来的错误处理,
 * 不会被悄悄换模型掩盖掉。
 */
class QuotaAwareRouter(
    val candidates: List<QuotaCandidate>,
    private val ledger: QuotaLedger,
    private val onSwitch: ((from: String, to: String?, signal: QuotaSignal) -> Unit)? = null,
) {
    init {
        require(candidates.map { it.id }.toSet().size == candidates.size) { "候选 id 不能重复" }
    }

    /** 这一步能不能交给这个候选:能力、上下文、隐私、账本状态都要过。 */
    private fun eligible(c: QuotaCandidate, req: QuotaRequirement): Boolean {
        val caps = c.model.capabilities
        if (req.needsTools && !caps.toolCalls) return false
        if (req.hasImages && !caps.vision) return false
        if (caps.contextTokens < req.minContextTokens) return false
        if (req.sensitive && !c.handlesSensitive) return false
        return ledger.usable(c.id)
    }

    /**
     * 选出这一步该用谁:[exclude] 是本次调用里已经试过、撞了额度的候选。
     * 同族优先(保住提示词前缀缓存),其余保持用户排的顺序。
     */
    fun select(req: QuotaRequirement, exclude: Set<String> = emptySet()): QuotaCandidate? {
        val usable = candidates.filter { it.id !in exclude && eligible(it, req) }
        val family = req.preferFamily ?: return usable.firstOrNull()
        return usable.firstOrNull { it.family == family } ?: usable.firstOrNull()
    }

    /**
     * 跑一次调用:[call] 里只管发给传进去的那个候选。额度失败自动切下一家,
     * 成功返回「结果 + 实际用的候选」,供调用方记用量、写运行记录。
     */
    suspend fun <T> run(req: QuotaRequirement, call: suspend (QuotaCandidate) -> T): Pair<T, QuotaCandidate> {
        val tried = LinkedHashSet<String>()
        val reasons = LinkedHashMap<String, String>()
        var earliest: Long? = null
        while (true) {
            val picked = select(req, tried) ?: throw AllQuotaCandidatesExhausted(tried.toList(), earliest, reasons)
            val result = try {
                call(picked)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val signal = QuotaSignals.of(e) ?: throw e
                val until = ledger.noteQuotaFailure(picked.id, signal)
                earliest = earliest?.let { minOf(it, until) } ?: until
                reasons[picked.id] = QuotaSignals.label(signal)
                tried += picked.id
                onSwitch?.invoke(picked.id, select(req, tried)?.id, signal)
                continue
            }
            ledger.noteSuccess(picked.id)
            return result to picked
        }
    }

    /** 接 [ModelCaller] 用的便捷入口:直接发一次完整的对话请求。 */
    suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        req: QuotaRequirement = QuotaRequirement(),
    ): Pair<ModelReply, QuotaCandidate> = run(req) { c ->
        c.model.complete(system, messages, tools)
    }

    /** 给额度池页面用:每个候选的状态和最早恢复时刻(没有冷却为 null)。 */
    fun recoveryTimes(): Map<String, Long?> = candidates.associate { c ->
        c.id to ledger.status(c.id).untilMs
    }

    /**
     * 首选模型属于哪一族。接进 [com.wangzan101.mika.core.agent.ModelCaller] 时用它填
     * [QuotaRequirement.preferFamily]:第一次切换优先同族,保住按模型分开存的压缩状态和提示词前缀缓存。
     */
    fun familyFor(model: ChatModel): String? =
        candidates.firstOrNull { it.model === model }?.family?.takeIf(String::isNotEmpty)

    /** 回填这次调用的真实 token 用量(账本里已有 [QuotaLedger.noteSuccess] 记的那次成功请求)。 */
    fun noteUsage(id: String, inputTokens: Long, outputTokens: Long): Unit =
        ledger.addUsage(id, inputTokens, outputTokens)
}
