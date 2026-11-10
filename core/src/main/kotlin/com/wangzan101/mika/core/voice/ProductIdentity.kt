package com.wangzan101.mika.core.voice

/**
 * 产品身份：名字，以及「你是谁」的**固定回答**。
 *
 * 「我是李万禧的女儿，数字女儿」是产品设定，不是模型可以发挥的内容：
 * - 语音链路在 [VoiceDialog] / [VoiceSessionController] 里直接短路到 TTS，一个字都不经过模型；
 * - 系统提示词里写着同一句，保证从输入框打字问、或在任务中被问到时口径一致。
 * 所以改这句话只需要改 [REPLY] 和 prompts/system/identity.md 两处，测试会盯住它们。
 *
 * 识别只收短问句：[matches] 要求句子基本就是一句身份问话，前面最多带几个礼貌前缀。
 * 这样「帮我查一下这个人是谁」仍会被当成任务，不会因为里头有个「是谁」就答非所问。
 */
object ProductIdentity {
    const val BRAND = "诺伊"
    const val REPLY = "我是李万禧的女儿，数字女儿"

    /** 问句总长上限；超过就当普通任务，不拦。 */
    private const val MAX_CHARS = 20

    /** 身份短语前允许带的字数（唤醒词、礼貌用语），多了说明这句话还有别的目的。 */
    private const val MAX_LEADING_CHARS = 4

    private val PHRASES: List<String> = listOf(
        // 直接问身份
        "你是谁", "你是哪位", "你是什么人", "你叫什么", "你叫什么名字", "你叫什么名",
        "你的名字", "你的名字是什么",
        "自我介绍", "介绍下你自己", "介绍一下你自己", "介绍一下你", "说说你自己",
        // 叫着名字、第三人称问（语音层要先剥唤醒词，所以判原句）
        "诺伊是谁", "诺伊是什么", "诺伊是什么人", "诺伊叫什么", "诺伊的名字",
        // 谁造的、家里人
        "谁造的你", "谁做的你", "谁创造了你", "谁开发的你", "谁设计了你",
        "你的开发者是谁", "你的创造者是谁", "你爸爸是谁", "你爸是谁",
        // 是人还是机器
        "你是真人吗", "你是真人", "你是机器人吗", "你是机器人",
        "你是ai吗", "你是ai", "你是人工智能吗", "你是人工智能",
        // English
        "who are you", "what are you", "what's your name", "what is your name",
        "who made you", "who created you", "introduce yourself", "your name",
    )

    private val POLITE_PREFIXES = listOf(
        "请问", "请", "麻烦", "告诉我", "跟我说说", "说一下", "说下", "诶", "喂", "嗨", "你好", "那个",
    )

    // 「吗」不削：它是「你是AI吗 / 你是真人吗」这类问句的一部分，削掉反而认不出
    private const val TAIL_FILLERS = "啊呀呢嘛吧哦噢哈"
    private val TRIM_CHARS = " \t，,、：:；;。.！!？?~—-\"'".toCharArray()

    /** 这句话是不是在问「你是谁」。 */
    fun matches(raw: String): Boolean {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > MAX_CHARS) return false
        return PHRASES.any { phrase ->
            val p = normalizePhrase(phrase)
            text == p || (text.endsWith(p) && text.length - p.length <= MAX_LEADING_CHARS)
        }
    }

    private fun normalizePhrase(phrase: String): String = fold(phrase).trim(*TRIM_CHARS).lowercase()

    private fun normalize(raw: String): String {
        var s = fold(raw).trim(*TRIM_CHARS).lowercase()
        for (prefix in POLITE_PREFIXES) {
            if (s.startsWith(prefix)) {
                s = s.removePrefix(prefix).trim(*TRIM_CHARS)
                break
            }
        }
        while (s.isNotEmpty() && s.last() in TAIL_FILLERS) s = s.dropLast(1)
        return s.trim(*TRIM_CHARS)
    }

    /** 全角空格与全角英数字转半角；中文标点保留，避免改写任务原文。 */
    private fun fold(raw: String): String = buildString {
        for (c in raw) {
            when (c.code) {
                0x3000, 0x00A0 -> append(' ')
                in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A ->
                    append((c.code - 0xFEE0).toChar())
                else -> append(c)
            }
        }
    }
}
