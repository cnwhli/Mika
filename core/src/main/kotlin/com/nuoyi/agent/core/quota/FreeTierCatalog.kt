package com.nuoyi.agent.core.quota

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 额度窗口怎样重置。只是给界面算倒计时用,真正的可用性一律由 [QuotaLedger] 的冷却时刻决定。 */
@Serializable
enum class QuotaReset { Rolling, Daily, None }

/**
 * 一家公开免费档的**元数据**,不是凭证:这里没有任何 Key、token 或用户账号。
 *
 * 数字只作展示和保守限速的参考,各家政策变得很快,一律以用户自己控制台里的为准。
 * [dataUsedForTraining] 为真时,涉及密码、支付、聊天记录等敏感步骤不应把截图交给它——
 * Nuoyi 的主要卖点是数据留在手机,免费档的训练条款往往和它冲突,所以默认按「不处理敏感内容」对待。
 */
@Serializable
data class FreeTier(
    val id: String,
    val label: String,
    val provider: String,
    val modelId: String,
    val toolCalls: Boolean = true,
    val vision: Boolean = false,
    val contextTokens: Int = 32_000,
    val requestsPerMinute: Int? = null,
    val requestsPerDay: Int? = null,
    val tokensPerDay: Long? = null,
    val reset: QuotaReset = QuotaReset.Rolling,
    /** 服务商条款允许把输入用于改进产品;敏感步骤跳过这类候选。 */
    val dataUsedForTraining: Boolean = true,
    /** 界面上给用户的提醒(如「需自己申请 Key」)。 */
    val note: String = "",
) {
    /** 能不能接主角色的活:每一步都要调工具,多数步还带截图。 */
    fun canBeMain(): Boolean = toolCalls && vision && contextTokens >= MIN_MAIN_CONTEXT_TOKENS

    /** 能不能接轻量判断、提取记忆这类不带图的用途。 */
    fun canBeLight(): Boolean = toolCalls && contextTokens >= MIN_LIGHT_CONTEXT_TOKENS

    companion object {
        /** GUI Agent 每步都要带截图 + 读屏 + 工具定义,太小的上下文一个转身就超长。 */
        const val MIN_MAIN_CONTEXT_TOKENS = 32_000
        const val MIN_LIGHT_CONTEXT_TOKENS = 8_000
    }
}

/**
 * 内置一份公开的免费档清单,可由 [parse] 用远程 JSON 覆盖而不必发版。
 * 只列**官方公开、用户自己注册 Key** 的档位:借用别家客户端赠送额度、共享他人 Key、批量注册刷额度
 * 都不在这里,也不建议做(违反服务条款,且会把用户账号置于风险中)。
 */
object FreeTierCatalog {
    val BUILT_IN: List<FreeTier> = listOf(
        FreeTier(
            id = "gemini-flash", label = "Gemini Flash (AI Studio)", provider = "Google AI Studio",
            modelId = "gemini-flash-latest", toolCalls = true, vision = true, contextTokens = 1_000_000,
            requestsPerMinute = 10, requestsPerDay = 1_000, reset = QuotaReset.Daily,
            dataUsedForTraining = true, note = "免费档支持工具调用与看图,最适合做候选池;数字以控制台为准",
        ),
        FreeTier(
            id = "gemini-flash-lite", label = "Gemini Flash-Lite", provider = "Google AI Studio",
            modelId = "gemini-flash-lite-latest", toolCalls = true, vision = true, contextTokens = 1_000_000,
            requestsPerMinute = 15, requestsPerDay = 1_000, reset = QuotaReset.Daily,
            dataUsedForTraining = true, note = "适合轻量用途",
        ),
        FreeTier(
            id = "groq", label = "Groq (Llama / Qwen 等)", provider = "Groq",
            modelId = "", toolCalls = true, vision = false, contextTokens = 128_000,
            requestsPerMinute = 30, requestsPerDay = 1_000, reset = QuotaReset.Rolling,
            dataUsedForTraining = false, note = "速度最快;逐模型确认是否支持工具调用与看图",
        ),
        FreeTier(
            id = "openrouter-free", label = "OpenRouter :free", provider = "OpenRouter",
            modelId = "", toolCalls = true, vision = false, contextTokens = 128_000,
            requestsPerMinute = 20, requestsPerDay = 50, reset = QuotaReset.Daily,
            dataUsedForTraining = true, note = "一个 Key 试多家;充值后日请求上限会提高",
        ),
        FreeTier(
            id = "cerebras", label = "Cerebras", provider = "Cerebras",
            modelId = "", toolCalls = true, vision = false, contextTokens = 128_000,
            tokensPerDay = 1_000_000, reset = QuotaReset.Daily,
            dataUsedForTraining = false, note = "有资料称已改成需验证支付方式的试用金,务必自己确认",
        ),
        FreeTier(
            id = "mistral-experiment", label = "Mistral Experiment", provider = "Mistral",
            modelId = "", toolCalls = true, vision = false, contextTokens = 32_000,
            reset = QuotaReset.Rolling, dataUsedForTraining = true, note = "速率较低,作备选",
        ),
        FreeTier(
            id = "zhipu-flash", label = "智谱 GLM Flash", provider = "智谱",
            modelId = "", toolCalls = true, vision = false, contextTokens = 128_000,
            reset = QuotaReset.Daily, dataUsedForTraining = true, note = "国内直连的备选",
        ),
        FreeTier(
            id = "siliconflow-free", label = "硅基流动 免费模型", provider = "硅基流动",
            modelId = "", toolCalls = true, vision = false, contextTokens = 32_000,
            reset = QuotaReset.Daily, dataUsedForTraining = true, note = "仅部分模型免费,以模型页为准",
        ),
        FreeTier(
            id = "local-ollama", label = "本机 Ollama / LM Studio", provider = "Local",
            modelId = "", toolCalls = true, vision = false, contextTokens = 32_000,
            reset = QuotaReset.None, dataUsedForTraining = false,
            note = "零成本、数据不出设备,但要有能跑得动的模型",
        ),
    )

    /** 远程清单格式不对或为空时保留内置清单,不让一次拉取失败把额度池清空。 */
    fun parse(text: String?): List<FreeTier> =
        text?.takeIf { it.isNotBlank() }?.let {
            runCatching { JSON.decodeFromString<List<FreeTier>>(it) }.getOrNull()?.takeIf(List<FreeTier>::isNotEmpty)
        } ?: BUILT_IN

    fun byId(id: String): FreeTier? = BUILT_IN.firstOrNull { it.id == id }

    /** 下次日界(按本地时区)还有多久;非 [QuotaReset.Daily] 返回 null。 */
    fun untilDailyReset(nowMs: Long, zoneOffsetMs: Long = 0): Long? {
        val day = 24 * 60 * 60_000L
        val local = nowMs + zoneOffsetMs
        val next = ((local / day) + 1) * day
        return next - local
    }

    private val JSON = Json { ignoreUnknownKeys = true }
}
