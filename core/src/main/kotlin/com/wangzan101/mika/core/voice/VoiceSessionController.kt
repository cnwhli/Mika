package com.wangzan101.mika.core.voice

/**
 * 一整轮免手语音会话的编排器。
 *
 * 与 [VoiceDialog] 的分工：Dialog 只回答「这句话是什么意思」，这里回答
 * 「现在该关麦还是开麦、该说哪句话、该派发什么动作、什么时候收场」。
 * 整套状态机是纯 JVM 的，宿主（系统助手会话／常驻热词服务）按顺序执行 [VoiceEffect]，
 * 所以换 Android 入口不用重写业务逻辑，也能在 JVM 上把免手的各种坑测干净。
 *
 * 三个真的会咬人的坑：
 * 1. **自听回声**：TTS 播报时自己的声音会被麦克风收进去，变成下一句指令。
 *    所以凡是 Speak 一定先 SetMic(false)，播完由宿主回调 [onSpeechDone] 才重新开麦；
 *    [VoiceSessionState.Speaking] 期间到达的识别结果一律丢弃。
 * 2. **旁人说话**：没被叫醒时（Idle）收到的文字全部忽略，不建任务、不播报。
 * 3. **敏感动作绕过**：[VoiceRisk.Sensitive] 的起任务只接受来自「确认」二轮对话的指令，
 *    解析层万一被改坏也不会直接点下去。
 */

enum class WakeSource {
    /** 公版：用户在系统设置里把本产品选为默认数字助手，由手势／电源键／耳机键唤起。 */
    SystemAssistant,

    /** 个人版：本地热词引擎命中自定义唤醒词。 */
    LocalHotword,

    /** 桌面快捷方式、通知、深链（仍是要碰一下的兜底入口）。 */
    Shortcut,
    Headset,
}

enum class VoiceSessionState { Idle, Listening, Speaking, WaitingTask, Closed }

sealed interface VoiceEffect {
    /** 半双工开关。宿主必须真的关掉录音，不只是不听结果。 */
    data class SetMic(val enabled: Boolean, val reason: String) : VoiceEffect

    data class Speak(val text: String) : VoiceEffect
    data class StartTask(val goal: String) : VoiceEffect
    data class Steer(val text: String) : VoiceEffect
    data object CancelTask : VoiceEffect

    /** 会话可以撤了：宿主关 ASR、关 TTS、finish() 系统助手会话。 */
    data object Finish : VoiceEffect

    /** 给日志／诊断用，宿主不需要做任何事。 */
    data class Note(val tag: String) : VoiceEffect
}

/** 宿主把任务系统的能力喂进来；控制器只调用，不持有 Android 对象。 */
interface VoiceTaskBridge {
    fun hasActiveTask(): Boolean
    fun start(goal: String)
    fun steer(text: String)
    fun cancel()
    fun status(): String?
}

object VoiceSessionPrompts {
    const val GREETING = "在，请说。"
    const val TASK_STARTED = "好，我去做，做完告诉你。"
    const val STEERED = "收到，按你说的改。"
    const val CANCELLED = "已经停下了。"
    const val NO_STATUS = "现在没有在跑的任务。"
    const val SENSITIVE_GUARD = "这一步涉及敏感操作，请重新说一遍并确认。"
    const val TASK_STILL_RUNNING = "任务还在跑，完成后我会告诉你结果。"
    const val BUSY = "正在做上一件事，做完再说。"
    const val TIMEOUT = "没听到，先退下了。"
    const val TOO_MANY_TURNS = "这轮说得太久了，先到这儿。"
}

class VoiceSessionController(
    private val capabilities: VoiceCapabilities,
    /** 随时读当前生效的唤醒词（个人版运行中可改），默认用出厂固定词。 */
    private val wakeWords: () -> List<String> = { capabilities.wakeWords() },
    private val tasks: VoiceTaskBridge,
    /** 开麦前最后一道合规闸。默认全否，宿主必须显式把已授权的 consent 传进来。 */
    private val consent: () -> VoiceConsent = { VoiceConsent() },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val listenTimeoutMs: Long = 12_000L,
) {
    var state: VoiceSessionState = VoiceSessionState.Idle
        private set

    private var dialog = VoiceDialog(VoiceConfig(requireWakeWord = false))
    private var lastHeardMs = 0L
    private var turns = 0
    private var finishAfterSpeech = false
    /** 这句播完之后回到哪个状态；任务还在跑时要回到 WaitingTask，不能退回 Listening。 */
    private var resumeStateAfterSpeech = VoiceSessionState.Listening
    /** 播报途中来的任务结果先存着，等这句播完再报，不丢结果。 */
    private var pendingResult: String? = null

    /** 改完唤醒词要调一下：重置解析层，丢掉可能还挂着的旧确认。 */
    fun onWakeWordChanged() {
        dialog = VoiceDialog(VoiceConfig(requireWakeWord = false))
    }

    /**
     * 被叫醒。公版由系统助手触发，个人版由本地热词触发，之后都不需要再碰屏幕。
     * 未拿到完整告知授权时直接收场，连麦克风都不开。
     */
    fun onWake(source: WakeSource): List<VoiceEffect> {
        if (state == VoiceSessionState.Closed) return listOf(VoiceEffect.Note("wake_after_close"))
        if (!VoiceDisclosure.canUseVoice(consent())) {
            state = VoiceSessionState.Speaking
            finishAfterSpeech = true
            resumeStateAfterSpeech = VoiceSessionState.Closed
            return listOf(
                VoiceEffect.Note("consent_missing"),
                VoiceEffect.SetMic(false, "consent"),
                VoiceEffect.Speak(VoiceDisclosure.MISSING_CONSENT),
            )
        }
        state = VoiceSessionState.Listening
        lastHeardMs = nowMs()
        turns = 0
        finishAfterSpeech = false
        pendingResult = null
        resumeStateAfterSpeech = VoiceSessionState.Listening
        val out = mutableListOf<VoiceEffect>(VoiceEffect.Note("wake_${source.name.lowercase()}"))
        if (capabilities.greetOnWake) {
            // 个人版常驻监听要给个「醒了」的回执；系统助手自带提示音，公版不重复。
            state = VoiceSessionState.Speaking
            out += VoiceEffect.SetMic(false, "greeting")
            out += VoiceEffect.Speak(VoiceSessionPrompts.GREETING)
        } else {
            out += VoiceEffect.SetMic(true, "wake")
        }
        return out
    }

    /** ASR 每产出一句最终文本调一次。 */
    fun onUtterance(raw: String): List<VoiceEffect> {
        when (state) {
            VoiceSessionState.Closed -> return listOf(VoiceEffect.Note("utterance_after_close"))
            VoiceSessionState.Speaking -> return listOf(VoiceEffect.Note("self_echo_dropped"))
            VoiceSessionState.Idle -> return listOf(VoiceEffect.Note("utterance_without_wake"))
            VoiceSessionState.WaitingTask -> return whileWaitingTask(raw)
            VoiceSessionState.Listening -> Unit
        }
        if (nowMs() - lastHeardMs > listenTimeoutMs) return finish("listen_timeout", VoiceSessionPrompts.TIMEOUT)
        lastHeardMs = nowMs()
        val cmd = dialog.onSpeech(stripWake(raw), tasks.hasActiveTask())
        return dispatch(cmd)
    }

    /** 任务还在跑时只收短控制语，别让一句闲聊又起一个新任务。 */
    private fun whileWaitingTask(raw: String): List<VoiceEffect> {
        val text = stripWake(raw)
        if (text.isBlank()) return listOf(VoiceEffect.Note("busy_empty"))
        if (text.length <= 10 && (text.contains("取消") || text.contains("停止") || text.contains("别做了"))) {
            return sayThenWait(listOf(VoiceEffect.CancelTask), VoiceSessionPrompts.CANCELLED, finishAfter = true)
        }
        if (text.length <= 10 && (text.contains("进度") || text.contains("到哪") || text.contains("状态"))) {
            return sayAndListen(tasks.status() ?: VoiceSessionPrompts.TASK_STILL_RUNNING)
        }
        return listOf(VoiceEffect.Note("busy_ignored"))
    }

    private fun dispatch(cmd: VoiceCommand): List<VoiceEffect> = when (val a = cmd.action) {
        VoiceAction.Ignore -> listOf(VoiceEffect.Note("ignored"))

        is VoiceAction.AwaitConfirmation -> sayAndListen(a.prompt)
        is VoiceAction.Clarify -> sayAndListen(a.prompt)
        is VoiceAction.Help -> sayAndListen(a.text)

        VoiceAction.Cancel -> sayThenWait(
            listOf(VoiceEffect.CancelTask), VoiceSessionPrompts.CANCELLED, finishAfter = true,
        )

        is VoiceAction.Status -> sayAndListen(tasks.status() ?: VoiceSessionPrompts.NO_STATUS)

        is VoiceAction.Steer -> sayThenWait(
            listOf(VoiceEffect.Steer(a.text)), VoiceSessionPrompts.STEERED, finishAfter = true,
        )

        is VoiceAction.StartTask -> {
            // 保险丝：敏感动作的起任务只能来自「确认」二轮对话。
            if (cmd.risk == VoiceRisk.Sensitive && cmd.intent != VoiceIntent.Confirm) {
                dialog.cancelPending()
                sayAndListen(VoiceSessionPrompts.SENSITIVE_GUARD)
            } else {
                sayThenWait(
                    listOf(VoiceEffect.StartTask(a.goal)), VoiceSessionPrompts.TASK_STARTED, finishAfter = false,
                )
            }
        }
    }

    /** 播一句再接着听（确认、澄清、查状态、帮助都走这里）。 */
    private fun sayAndListen(text: String): List<VoiceEffect> {
        if (turns >= capabilities.maxTurns) return finish("max_turns", VoiceSessionPrompts.TOO_MANY_TURNS)
        turns++
        resumeStateAfterSpeech = if (state == VoiceSessionState.WaitingTask) {
            VoiceSessionState.WaitingTask
        } else {
            VoiceSessionState.Listening
        }
        state = VoiceSessionState.Speaking
        finishAfterSpeech = false
        lastHeardMs = nowMs()
        return listOf(VoiceEffect.SetMic(false, "speak"), VoiceEffect.Speak(text))
    }

    /**
     * 播一句并派发副作用。起任务后不立刻 Finish，而是进 [VoiceSessionState.WaitingTask]
     * 等任务结果 —— 免手闭环的最后一公里是「做完主动告诉你」，不是让用户去看屏幕。
     */
    private fun sayThenWait(
        sideEffects: List<VoiceEffect>, text: String, finishAfter: Boolean,
    ): List<VoiceEffect> {
        resumeStateAfterSpeech = if (finishAfter) VoiceSessionState.Closed else VoiceSessionState.WaitingTask
        state = VoiceSessionState.Speaking
        finishAfterSpeech = finishAfter
        return listOf(VoiceEffect.SetMic(false, "speak")) + sideEffects + VoiceEffect.Speak(text)
    }

    /** TTS 真的播完了（宿主用 UtteranceProgressListener 回调，不要自己估时间）。 */
    fun onSpeechDone(): List<VoiceEffect> {
        if (state != VoiceSessionState.Speaking) return emptyList()
        // 上一句还在播时来的任务结果优先报：不能让「任务完成」被一句回执盖掉。
        val queued = pendingResult
        if (queued != null) {
            pendingResult = null
            finishAfterSpeech = true
            resumeStateAfterSpeech = VoiceSessionState.Closed
            state = VoiceSessionState.Speaking
            return listOf(VoiceEffect.SetMic(false, "queued_result"), VoiceEffect.Speak(queued))
        }
        if (finishAfterSpeech) {
            finishAfterSpeech = false
            return finalize()
        }
        state = resumeStateAfterSpeech
        lastHeardMs = nowMs()
        // 等任务时重新开麦，但只收「取消／进度」这类短控制语（见 whileWaitingTask）。
        val reason = if (state == VoiceSessionState.WaitingTask) "resume_control_only" else "resume"
        return listOf(VoiceEffect.SetMic(true, reason))
    }

    /** 任务跑完了：主动播报结果，然后收场。 */
    fun onTaskFinished(summary: String): List<VoiceEffect> {
        if (state == VoiceSessionState.Closed) return emptyList()
        val text = summary.ifBlank { VoiceSessionPrompts.TASK_STILL_RUNNING }
        if (state == VoiceSessionState.Speaking) {
            pendingResult = text
            return listOf(VoiceEffect.Note("result_queued"))
        }
        resumeStateAfterSpeech = VoiceSessionState.Closed
        state = VoiceSessionState.Speaking
        finishAfterSpeech = true
        return listOf(VoiceEffect.SetMic(false, "result"), VoiceEffect.Speak(text))
    }

    /** 任务太久（例如超过宿主允许的会话时长）：先播一句体面的话，别把会话永远挂住。 */
    fun onTaskStillRunning(): List<VoiceEffect> = onTaskFinished(VoiceSessionPrompts.TASK_STILL_RUNNING)

    /**
     * 静默超时只在等用户说话时收场；[VoiceSessionState.WaitingTask] 是等任务，
     * 可能几分钟都没声音，收不收场由宿主调 [onTaskStillRunning] 决定。
     */
    fun onTimeout(): List<VoiceEffect> = when (state) {
        VoiceSessionState.Listening ->
            if (nowMs() - lastHeardMs > listenTimeoutMs) finish("timeout", VoiceSessionPrompts.TIMEOUT)
            else emptyList()
        else -> emptyList()
    }

    fun onHide(): List<VoiceEffect> =
        if (state == VoiceSessionState.Closed) emptyList() else finish("hidden", null)

    private fun finish(reason: String, spoken: String?): List<VoiceEffect> {
        dialog.cancelPending()
        val out = mutableListOf<VoiceEffect>(VoiceEffect.Note("finish_$reason"), VoiceEffect.SetMic(false, "finish"))
        if (spoken != null) {
            // 先把话播完再收场：直接 Finish 会把 TTS 掐断，用户只听到半个字。
            out += VoiceEffect.Speak(spoken)
            state = VoiceSessionState.Speaking
            finishAfterSpeech = true
            resumeStateAfterSpeech = VoiceSessionState.Closed
            return out
        }
        return finalize(out)
    }

    private fun finalize(prefix: List<VoiceEffect> = emptyList()): List<VoiceEffect> {
        state = VoiceSessionState.Closed
        finishAfterSpeech = false
        pendingResult = null
        return prefix + VoiceEffect.Finish
    }

    /** 系统／热词层已经剥掉唤醒词时，这里再兜一次底，顺手把「小舵，帮我…」处理干净。 */
    private fun stripWake(raw: String): String {
        val text = raw.trim()
        val words = wakeWords().map { it.trim() }.filter { it.isNotBlank() }.sortedByDescending { it.length }
        for (word in words) {
            if (text.startsWith(word, ignoreCase = true)) {
                return text.substring(word.length).trimStart(*WAKE_SEPARATORS)
            }
        }
        return text
    }

    private companion object {
        val WAKE_SEPARATORS = charArrayOf('，', ',', '、', '：', ':', '；', ';', '。', '.', ' ', '！', '!', '？', '?', '~', '-')
    }
}
