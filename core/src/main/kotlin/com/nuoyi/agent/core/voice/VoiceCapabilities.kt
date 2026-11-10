package com.nuoyi.agent.core.voice

/**
 * 两个发行版本的能力边界。
 *
 * 这个产品对外发行「公版」，同时保留一版自用的「个人版」：
 * - 公版：由系统的数字助手入口唤起，唤醒词固定、不可改，也不带常驻麦克风。
 *   原因不只是产品取舍：Android 12 起 AlwaysOnHotwordDetector 变成 @SystemApi，
 *   第三方应用即使被用户设为默认数字助手也拿不到系统热词；Google Play 对
 *   microphone 类型前台服务还要求用户发起、有时长、需提交声明与演示视频，
 *   常驻热词基本不可能过审。
 * - 个人版：自己侧载使用，可以本地常驻热词，唤醒词由用户自己定。
 *
 * 关键约束：**公版不是「把设置页的输入框藏起来」，而是运行时也拒绝写入**。
 * 能力开关只由 [VoiceEdition] 推导，调用方无法传参提权，防止改个配置或反射就把
 * 常驻麦克风打开、把不该发的包变成能发的包。
 */

enum class VoiceEdition {
    /** 对外分发。系统助手入口、固定唤醒词、无常驻监听。 */
    PUBLIC,

    /** 自用侧载。允许自定义唤醒词与本地常驻热词。 */
    PERSONAL,
}

enum class WakeWordReject {
    /** 公版锁死，不允许自定义。 */
    EditionLocked,
    Empty,
    TooShort,
    TooLong,
    IllegalChar,
    /** 与控制语／敏感词撞车，喊一声就会误触发「确认」「取消」。 */
    ReservedWord,
    /** 与别的语音助手撞车，会同时叫醒两个。 */
    CompetingAssistant,
    AllDigits,
}

sealed interface WakeWordResult {
    /** [custom] 为真表示这是用户自定义的词，不是出厂固定词。 */
    data class Ok(val word: String, val custom: Boolean) : WakeWordResult
    data class Bad(val reason: WakeWordReject) : WakeWordResult
}

/** 自定义唤醒词的合法性检查。纯粹的字符串规则，不碰 Android，直接可测。 */
object WakeWordPolicy {
    const val MIN_CHARS = 2
    const val MAX_CHARS = 8

    /** 这些词在对话里另有含义，当唤醒词会把自己绕进去。 */
    val RESERVED: Set<String> = setOf(
        "确认", "确定", "取消", "停止", "停下", "算了", "终止", "帮助", "怎么用",
        "进度", "状态", "在干嘛", "支付", "转账", "付款", "密码", "验证码", "发送", "删除",
        "你好", "在吗", "继续", "执行", "好的", "不要",
    )

    /** 常见系统／厂商助手，避开它们免得一句话叫醒两个 App。 */
    val COMPETING_ASSISTANTS: List<String> = listOf(
        "小爱", "小艺", "小度", "小冰", "天猫精灵", "siri", "bixby", "alexa",
        "ok google", "hey google", "okay google", "google助手", "hi galaxy",
    )

    /** 全角英数字／空格转半角，去掉首尾空白和标点；中文标点本身不改写。 */
    fun normalize(raw: String): String = buildString {
        for (c in raw) {
            when (c.code) {
                0x3000, 0x00A0 -> append(' ')
                in 0xFF10..0xFF19, in 0xFF21..0xFF3A, in 0xFF41..0xFF5A ->
                    append((c.code - 0xFEE0).toChar())
                else -> append(c)
            }
        }
    }.trim().trim(*WAKE_TRIM)

    fun check(raw: String): WakeWordResult {
        val word = normalize(raw)
        if (word.isEmpty()) return WakeWordResult.Bad(WakeWordReject.Empty)
        if (word.length < MIN_CHARS) return WakeWordResult.Bad(WakeWordReject.TooShort)
        if (word.length > MAX_CHARS) return WakeWordResult.Bad(WakeWordReject.TooLong)
        if (word.any { !isAllowed(it) }) return WakeWordResult.Bad(WakeWordReject.IllegalChar)
        if (word.all { it in '0'..'9' }) return WakeWordResult.Bad(WakeWordReject.AllDigits)
        val lower = word.lowercase()
        if (RESERVED.any { lower == it || lower.contains(it) }) {
            return WakeWordResult.Bad(WakeWordReject.ReservedWord)
        }
        if (COMPETING_ASSISTANTS.any { lower == it || lower.contains(it) }) {
            return WakeWordResult.Bad(WakeWordReject.CompetingAssistant)
        }
        return WakeWordResult.Ok(word, custom = false)
    }

    /** 中文、英文字母、数字、以及词中间的空格；标点一律不收。 */
    private fun isAllowed(c: Char) = c in '\u4e00'..'\u9fff' ||
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == ' '

    private val WAKE_TRIM = charArrayOf(
        ' ', '，', ',', '。', '.', '、', '：', ':', '；', ';', '！', '!', '？', '?', '~', '-', '—',
    )
}

/**
 * 一个版本能做到什么。构造私有，只能经 [forEdition][VoiceCapabilities.Companion.forEdition] 生成，
 * 所以「传个 true 把公版解锁」这种事在类型层面就不存在。
 */
class VoiceCapabilities private constructor(
    val edition: VoiceEdition,
    /** 出厂唤醒词，两个版本都用它兜底；公版永远只有它。 */
    val fixedWakeWord: String,
    val allowCustomWakeWord: Boolean,
    /** 是否允许常驻麦克风（本地热词）。 */
    val allowAlwaysOnListening: Boolean,
    /** 是否允许注册系统数字助手入口。公版必须开，个人版也保留。 */
    val allowAssistantEntry: Boolean,
    /** 被叫醒后要不要播一声「在」。系统助手自己有提示音，公版不重复播。 */
    val greetOnWake: Boolean,
    /** 一轮会话最多几句话，防止免手状态下无限循环。 */
    val maxTurns: Int,
) {
    /** 给 [VoiceDialog]／控制器用的当前唤醒词表。 */
    fun wakeWords(custom: String? = null): List<String> {
        val words = mutableListOf(fixedWakeWord)
        if (allowCustomWakeWord && !custom.isNullOrBlank() && custom != fixedWakeWord) {
            words += custom
        }
        return words
    }

    /** 把用户想改的词落到实际生效的词上；公版在这里直接拒绝，不改状态。 */
    fun resolve(requested: String): WakeWordResult {
        if (!allowCustomWakeWord) return WakeWordResult.Bad(WakeWordReject.EditionLocked)
        return when (val r = WakeWordPolicy.check(requested)) {
            is WakeWordResult.Ok -> WakeWordResult.Ok(r.word, custom = true)
            is WakeWordResult.Bad -> r
        }
    }

    companion object {
        /** 出厂固定唤醒词「诺伊」。换词时这里和 app/build.gradle.kts 的 brandWakeWord 必须同步，VoiceRuntime 会校验。 */
        const val DEFAULT_FIXED_WAKE_WORD = "诺伊"

        fun publicEdition(fixedWakeWord: String = DEFAULT_FIXED_WAKE_WORD): VoiceCapabilities {
            val word = when (val r = WakeWordPolicy.check(fixedWakeWord)) {
                is WakeWordResult.Ok -> r.word
                is WakeWordResult.Bad -> DEFAULT_FIXED_WAKE_WORD
            }
            return VoiceCapabilities(
                edition = VoiceEdition.PUBLIC,
                fixedWakeWord = word,
                allowCustomWakeWord = false,
                allowAlwaysOnListening = false,
                allowAssistantEntry = true,
                greetOnWake = false,
                maxTurns = 8,
            )
        }

        fun personalEdition(fixedWakeWord: String = DEFAULT_FIXED_WAKE_WORD): VoiceCapabilities {
            val word = when (val r = WakeWordPolicy.check(fixedWakeWord)) {
                is WakeWordResult.Ok -> r.word
                is WakeWordResult.Bad -> DEFAULT_FIXED_WAKE_WORD
            }
            return VoiceCapabilities(
                edition = VoiceEdition.PERSONAL,
                fixedWakeWord = word,
                allowCustomWakeWord = true,
                allowAlwaysOnListening = true,
                allowAssistantEntry = true,
                greetOnWake = true,
                maxTurns = 12,
            )
        }

        /**
         * 按版本生成能力集。刻意不接收任何「允许自定义／允许常驻」参数：
         * 这类开关只由版本推导，避免打包脚本、远程配置或 UI 误传导致公版越权。
         */
        fun forEdition(edition: VoiceEdition, fixedWakeWord: String = DEFAULT_FIXED_WAKE_WORD) =
            when (edition) {
                VoiceEdition.PUBLIC -> publicEdition(fixedWakeWord)
                VoiceEdition.PERSONAL -> personalEdition(fixedWakeWord)
            }
    }
}

/**
 * 唤醒词的运行时状态。公版调用 [set] 拿到的永远是 [WakeWordReject.EditionLocked]，
 * 且 [current] 不变 —— 即使有人绕过设置页直接调到这个类也一样。
 */
class WakeWordSettings(private val capabilities: VoiceCapabilities) {
    private var custom: String? = null

    @Synchronized
    fun current(): String = custom ?: capabilities.fixedWakeWord

    fun isCustom(): Boolean = custom != null

    fun wakeWords(): List<String> = capabilities.wakeWords(custom)

    @Synchronized
    fun set(requested: String): WakeWordResult = when (val r = capabilities.resolve(requested)) {
        is WakeWordResult.Ok -> {
            custom = r.word
            WakeWordResult.Ok(r.word, custom = true)
        }
        is WakeWordResult.Bad -> r
    }

    @Synchronized
    fun reset() {
        custom = null
    }
}
