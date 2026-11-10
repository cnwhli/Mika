package com.wangzan101.mika.core.voice

/**
 * 语音「听清之后」的那一层：把一句口语变成可以派发的结构化动作。
 *
 * 这一层故意不碰 Android、不碰麦克风、不碰 ASR：识别、唤醒、播报都由宿主负责，
 * 这里只负责「这句话到底要做什么」，所以能直接在 JVM 上测，也方便换掉整套语音引擎。
 * 宿主拿到 [VoiceAction] 后自行映射到自己的任务入口（起任务／插话／取消／查状态）。
 */

enum class VoiceIntent {
    StartTask, Steer, AwaitConfirmation, Confirm, Cancel, Status, Help, Ignore, Clarify,
    /** 在问「你是谁」。答案由产品固定给出，不进模型、不起任务。 */
    Identity,
}

/** 这句话会不会造成难以撤回的后果。 */
enum class VoiceRisk { Normal, Sensitive }

sealed interface VoiceAction {
    /** 起一个新任务。[goal] 已经去掉唤醒词，可以直接当任务目标用。 */
    data class StartTask(val goal: String) : VoiceAction

    /** 往正在跑的任务里插一句话（「不对，改成…」）。 */
    data class Steer(val text: String) : VoiceAction

    /** 敏感动作先播报 [prompt] 并等待口头确认；[goal] 由 [VoiceDialog] 暂存，确认后才派发。 */
    data class AwaitConfirmation(val goal: String, val prompt: String) : VoiceAction

    data object Cancel : VoiceAction
    data object Status : VoiceAction
    data class Help(val text: String) : VoiceAction

    /** 只播报、不派任务。身份问答这类「答案必须是原话」的场景专用。 */
    data class Reply(val text: String) : VoiceAction

    /** 没唤醒、或识别结果明显不是对本产品说的：宿主应直接忽略，不播报、不建任务。 */
    data object Ignore : VoiceAction

    /** 需要用户再说一遍。[prompt] 可直接交给 TTS。 */
    data class Clarify(val prompt: String) : VoiceAction
}

/** 产品相关的词表全部集中在这里，换成自己的品牌／语气时不用改解析逻辑。 */
data class VoiceConfig(
    /** 免手（始终在听）时的唤醒词；按住说话模式可留空。 */
    val wakeWords: List<String> = emptyList(),
    /** 为真时，没命中唤醒词一律 [VoiceAction.Ignore]，避免把旁人说话当指令。 */
    val requireWakeWord: Boolean = false,
    /** 命中即要求二次确认。GUI Agent 会真的点下去，误识别的代价很高。 */
    val sensitiveWords: List<String> = DEFAULT_SENSITIVE_WORDS,
    val confirmWords: List<String> = listOf("确认", "确定", "执行", "执行吧", "继续", "好的", "好", "对", "没问题", "可以", "同意", "是的"),
    val rejectWords: List<String> = listOf("取消", "算了", "不要", "不确认", "不用了", "不用", "别"),
    val cancelPhrases: List<String> = listOf("取消", "停止", "停下", "别做了", "算了", "终止", "结束任务", "别干了"),
    val statusPhrases: List<String> = listOf("到哪了", "进度", "在干嘛", "什么情况", "状态", "怎么样了", "做到哪了", "还在吗"),
    val helpPhrases: List<String> = listOf("你能做什么", "你能干嘛", "怎么用", "帮助", "有什么功能"),
    val steerPrefixes: List<String> = listOf("不对", "改成", "改一下", "等等", "顺便", "还有", "另外", "其实", "我是说", "再补充"),
    /** 确认等待的有效期；过了就当没这回事，防止隔很久一句「确认」触发旧任务。 */
    val confirmationTimeoutMs: Long = 60_000L,
    /** 控制语必须是短句，避免「取消，顺便帮我…」被当成取消。 */
    val shortUtteranceMaxChars: Int = 10,
) {
    companion object {
        val DEFAULT_SENSITIVE_WORDS: List<String> = listOf(
            "转账", "付款", "支付", "下单", "购买", "买下", "提交订单", "红包", "打赏",
            "删除", "清空", "卸载", "注销", "解绑", "重置密码", "验证码", "密码",
            "发给", "发送给", "发送", "群发", "发布", "评论", "私信", "回复", "拨打电话", "打电话",
        )
    }
}

/** 所有播报文案集中在此，做自己的产品时整份替换即可。 */
object VoicePrompts {
    const val EMPTY = "没听清，请再说一遍。"
    const val WAKE_ONLY = "我在，请说出要做什么。"
    const val CONFIRM_HINT = "上一件事还没确认，请说「确认」或「取消」。"
    const val HELP = "直接说出要做的事就行，比如「帮我给张三发消息说明天见」；也可以说「取消」「进度」，或「不对，改成…」来改口。"
    fun sensitive(keyword: String) = "这句话涉及「$keyword」，请说「确认」继续，或说「取消」。"
}

data class VoiceCommand(
    val raw: String,
    /** 去掉唤醒词、全角转半角后的文本。 */
    val transcript: String,
    val intent: VoiceIntent,
    val action: VoiceAction,
    val risk: VoiceRisk,
    val wakeMatched: Boolean,
) {
    /** 直接可交给 TTS 的回执；不需要播报时为 null（例如任务已开始，交给任务自己汇报）。 */
    val spokenReply: String? get() = when (action) {
        is VoiceAction.AwaitConfirmation -> action.prompt
        is VoiceAction.Clarify -> action.prompt
        is VoiceAction.Help -> action.text
        is VoiceAction.Reply -> action.text
        else -> null
    }
}

/**
 * 一轮语音对话的状态机：处理唤醒词、短控制语、改口插话，以及敏感动作的「确认／取消」二轮对话。
 * 麦克风与 ASR 每产出一句最终文本就调一次 [onSpeech]；是否正在跑任务由宿主通过 [hasActiveTask] 告诉它。
 */
class VoiceDialog(
    private val config: VoiceConfig = VoiceConfig(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(val goal: String, val createdAtMs: Long)

    private var pending: Pending? = null

    fun hasPendingConfirmation(): Boolean = livePending() != null

    /** 用户点了界面上的取消、或任务已结束时清掉待确认的指令。 */
    fun cancelPending() { pending = null }

    fun onSpeech(raw: String, hasActiveTask: Boolean = false): VoiceCommand {
        val normalized = normalize(raw)
        val waiting = livePending()
        val stripped = stripWake(normalized)
        // 等确认时用户通常只回「确认」，不会再喊一次唤醒词，这一句放行；其余场景没唤醒就忽略。
        val (text, wakeMatched) = when {
            stripped != null -> stripped
            waiting != null -> normalized to false
            else -> return command(raw, "", VoiceIntent.Ignore, VoiceAction.Ignore, wakeMatched = false)
        }

        // 身份问答优先于一切：答案由产品常量给出，绝不交给模型，也绝不变成任务。
        // 用原句判，这样「诺伊是谁」这类叫着名字问的也认得出（text 已被剥掉唤醒词）。
        if (ProductIdentity.matches(raw)) {
            return command(
                raw, text, VoiceIntent.Identity,
                VoiceAction.Reply(ProductIdentity.REPLY), wakeMatched = wakeMatched,
            )
        }

        // 等待确认时只接受简短的确认／否定，别的都当作没听清，避免把新指令误当成「确认」。
        if (waiting != null) {
            return when {
                isShort(text) && containsAny(text, config.confirmWords) -> {
                    pending = null
                    command(raw, waiting.goal, VoiceIntent.Confirm, VoiceAction.StartTask(waiting.goal), VoiceRisk.Sensitive)
                }
                isShort(text) && containsAny(text, config.rejectWords) -> {
                    pending = null
                    command(raw, text, VoiceIntent.Cancel, VoiceAction.Cancel)
                }
                else -> command(raw, text, VoiceIntent.Clarify, VoiceAction.Clarify(VoicePrompts.CONFIRM_HINT))
            }
        }

        if (text.isBlank()) {
            val prompt = if (wakeMatched && normalized.isNotBlank()) VoicePrompts.WAKE_ONLY else VoicePrompts.EMPTY
            return command(raw, "", VoiceIntent.Clarify, VoiceAction.Clarify(prompt), wakeMatched = wakeMatched)
        }

        if (isShort(text)) {
            if (containsAny(text, config.cancelPhrases)) return command(raw, text, VoiceIntent.Cancel, VoiceAction.Cancel)
            if (containsAny(text, config.statusPhrases)) return command(raw, text, VoiceIntent.Status, VoiceAction.Status)
            if (containsAny(text, config.helpPhrases)) {
                return command(raw, text, VoiceIntent.Help, VoiceAction.Help(VoicePrompts.HELP))
            }
        }

        if (hasActiveTask && config.steerPrefixes.any { text.startsWith(it) }) {
            return command(raw, text, VoiceIntent.Steer, VoiceAction.Steer(text))
        }

        val hit = config.sensitiveWords.firstOrNull { text.contains(it) }
        return if (hit == null) {
            command(raw, text, VoiceIntent.StartTask, VoiceAction.StartTask(text))
        } else {
            pending = Pending(text, nowMs())
            command(
                raw, text, VoiceIntent.AwaitConfirmation,
                VoiceAction.AwaitConfirmation(text, VoicePrompts.sensitive(hit)),
                VoiceRisk.Sensitive,
            )
        }
    }

    private fun livePending(): Pending? {
        val p = pending ?: return null
        if (nowMs() - p.createdAtMs > config.confirmationTimeoutMs) {
            pending = null
            return null
        }
        return p
    }

    private fun stripWake(text: String): Pair<String, Boolean>? {
        if (!config.requireWakeWord) return text to true
        val words = config.wakeWords.map { normalize(it) }.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (words.isEmpty()) return text to true // 没配唤醒词就不拦，免得整个功能哑掉
        for (word in words) {
            if (text.startsWith(word, ignoreCase = true)) {
                return text.substring(word.length).dropWhile { it in WAKE_SEPARATORS } to true
            }
        }
        return null
    }

    private fun isShort(text: String) = text.length <= config.shortUtteranceMaxChars

    private fun containsAny(text: String, words: List<String>) = words.any { text.contains(it) }

    private fun command(
        raw: String, transcript: String, intent: VoiceIntent, action: VoiceAction,
        risk: VoiceRisk = VoiceRisk.Normal, wakeMatched: Boolean = true,
    ) = VoiceCommand(raw, transcript, intent, action, risk, wakeMatched)

    private companion object {
        const val WAKE_SEPARATORS = "，,、：:；;。. ！!？?~-"

        /**
         * 全角空格和全角英数字转半角、去掉首尾空白。
         * 中文标点（，。、：等）刻意保留：它是任务目标的一部分，转成半角反而让原文失真。
         */
        fun normalize(raw: String): String = buildString {
            for (c in raw) {
                when (c.code) {
                    0x3000, 0x00A0 -> append(' ')
                    in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A ->
                        append((c.code - 0xFEE0).toChar())
                    else -> append(c)
                }
            }
        }.trim()
    }
}
