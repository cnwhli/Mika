package com.wangzan101.mika.core.quota

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** 一个候选现在能不能用。[Cooling] 一定带着到期的时刻,不会有没有期限的「额度用尽」。 */
enum class Availability { Available, Cooling, Disabled }

/** [remainingMs] 是还要等多久(只在 [Availability.Cooling] 时有值)。 */
data class QuotaStatus(
    val availability: Availability,
    val reason: String? = null,
    val untilMs: Long? = null,
    val remainingMs: Long? = null,
) {
    val usable: Boolean get() = availability == Availability.Available
}

/** 额度池页面用的一行:状态、用量、下次可试的时刻。 */
data class QuotaSnapshot(
    val id: String,
    val status: QuotaStatus,
    val requests: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val lastSuccessMs: Long?,
    val lastFailureMs: Long?,
)

/**
 * 每个候选(一家服务商 + 一个模型 + 一把 Key)一份额度账本。
 *
 * 这里刻意不保存「额度已用完」这种没有时间维度的结论:额度／限流失败一律记成 [Availability.Cooling]
 * 并带上 [Entry.untilMs],读状态时窗口一过就自动复活([status]);成功一次也立刻复活([noteSuccess])。
 * 只有明确的永久错误(Key 不对、模型不存在、模型不支持工具)才记 [Availability.Disabled],
 * 且只能由用户改配置后 [markAvailable] 手动清掉。
 *
 * 这样「额度恢复了,界面还显示额度用尽」不可能来自账本:任何一次失败都有明确的过期时刻。
 * 账本可以整份落盘([encode] / [decode]),重启后仍按同一套时间规则复活。
 */
class QuotaLedger(
    private val now: () -> Long = System::currentTimeMillis,
    /** 用量统计的窗口长度,只影响 [QuotaSnapshot] 里的计数,不影响冷却是否到期。 */
    private val usageWindowMs: Long = 24 * 60 * 60_000L,
) {
    @Serializable
    data class Entry(
        val availability: Availability = Availability.Available,
        val untilMs: Long? = null,
        val reason: String? = null,
        val requests: Long = 0,
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val windowStartedMs: Long? = null,
        val lastSuccessMs: Long? = null,
        val lastFailureMs: Long? = null,
    ) {
        /** 窗口过去了:计数从头开始,状态不动(冷却由 [untilMs] 单独管)。 */
        fun inWindow(nowMs: Long, windowMs: Long): Boolean =
            windowStartedMs != null && nowMs - windowStartedMs < windowMs
    }

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>()

    /** 读状态时顺手复活到期的候选;这是「额度恢复后自动可用」的唯一入口。 */
    fun status(id: String): QuotaStatus = synchronized(lock) {
        val at = now()
        val e = entries[id] ?: return@synchronized QuotaStatus(Availability.Available)
        if (e.availability == Availability.Cooling && (e.untilMs == null || at >= e.untilMs)) {
            entries[id] = e.copy(availability = Availability.Available, untilMs = null, reason = null)
            return@synchronized QuotaStatus(Availability.Available)
        }
        QuotaStatus(
            e.availability, e.reason, e.untilMs,
            if (e.availability == Availability.Cooling && e.untilMs != null) (e.untilMs - at).coerceAtLeast(0) else null,
        )
    }

    fun usable(id: String): Boolean = status(id).usable

    /**
     * 记一次额度／限流失败:一定写 [Availability.Cooling],并返回可以再试的时刻。
     * [signal] 里服务端说了等多久就照它;没说按这类错误的保守默认值。
     */
    fun noteQuotaFailure(id: String, signal: QuotaSignal): Long = synchronized(lock) {
        val at = now()
        val until = at + signal.suggestedCooldownMs
        val e = entries[id] ?: Entry()
        entries[id] = e.copy(
            availability = Availability.Cooling,
            untilMs = until,
            reason = QuotaSignals.label(signal),
            lastFailureMs = at,
        )
        until
    }

    /** 成功一次就说明额度确实恢复了:立刻清掉冷却,别让用户等到旧的窗口结束。 */
    fun noteSuccess(id: String, inputTokens: Long = 0, outputTokens: Long = 0): Unit = synchronized(lock) {
        val at = now()
        val e = entries[id] ?: Entry()
        val fresh = if (e.inWindow(at, usageWindowMs)) e else e.copy(
            requests = 0, inputTokens = 0, outputTokens = 0, windowStartedMs = at,
        )
        entries[id] = fresh.copy(
            availability = Availability.Available,
            untilMs = null,
            reason = null,
            requests = fresh.requests + 1,
            inputTokens = fresh.inputTokens + inputTokens,
            outputTokens = fresh.outputTokens + outputTokens,
            windowStartedMs = fresh.windowStartedMs ?: at,
            lastSuccessMs = at,
        )
    }

    /**
     * 拿到模型返回的真实 usage 后回填 token 用量,只累加计数:不改冷却状态、不增加请求数。
     * [noteSuccess] 已经记过一次「请求成功」,这里补的是那次请求的体量,供额度池页面统计。
     */
    fun addUsage(id: String, inputTokens: Long, outputTokens: Long): Unit = synchronized(lock) {
        if (inputTokens <= 0 && outputTokens <= 0) return@synchronized
        val e = entries[id] ?: return@synchronized
        entries[id] = e.copy(
            inputTokens = e.inputTokens + inputTokens,
            outputTokens = e.outputTokens + outputTokens,
        )
    }

    /** Key、模型名、能力这类明确不会自己变好的错误;仍可由 [markAvailable] 复活。 */
    fun notePermanentFailure(id: String, reason: String): Unit = synchronized(lock) {
        val e = entries.getOrDefault(id, Entry())
        entries[id] = e.copy(
            availability = Availability.Disabled, untilMs = null, reason = reason, lastFailureMs = now(),
        )
    }

    /** 用户改了 Key、重置了订阅或手动点了「重试」:把这条记录清空。 */
    fun markAvailable(id: String): Unit = synchronized(lock) { entries.remove(id) }

    fun clear(): Unit = synchronized(lock) { entries.clear() }

    fun snapshot(): List<QuotaSnapshot> = synchronized(lock) {
        entries.keys.toList().map { id ->
            val e = entries.getValue(id)
            QuotaSnapshot(id, status(id), e.requests, e.inputTokens, e.outputTokens, e.lastSuccessMs, e.lastFailureMs)
        }
    }

    fun encode(): String = synchronized(lock) { JSON.encodeToString(ENTRIES, entries.toMap()) }

    /** 落盘过期的记录也按时间复活,不会因为读档重新变成「一直额度用尽」。 */
    fun decode(text: String?): Unit = synchronized(lock) {
        entries.clear()
        if (text.isNullOrBlank()) return@synchronized
        runCatching { JSON.decodeFromString(ENTRIES, text) }
            .getOrNull()
            ?.let { entries.putAll(it) }
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true }
        val ENTRIES = MapSerializer(String.serializer(), Entry.serializer())
    }
}
