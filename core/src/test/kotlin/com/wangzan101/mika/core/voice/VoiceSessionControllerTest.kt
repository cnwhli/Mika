package com.wangzan101.mika.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTasks(var active: Boolean = false, var statusText: String? = null) : VoiceTaskBridge {
    val started = mutableListOf<String>()
    val steered = mutableListOf<String>()
    var cancelled = 0
        private set

    override fun hasActiveTask() = active
    override fun start(goal: String) { started += goal; active = true }
    override fun steer(text: String) { steered += text }
    override fun cancel() { cancelled++; active = false }
    override fun status() = statusText
}

/** 控制器只产 effects、不直接产生副作用；这里模拟宿主按顺序执行它们。 */
private fun FakeTasks.dispatch(effects: List<VoiceEffect>) {
    for (e in effects) when (e) {
        is VoiceEffect.StartTask -> start(e.goal)
        is VoiceEffect.Steer -> steer(e.text)
        VoiceEffect.CancelTask -> cancel()
        else -> Unit
    }
}

private fun List<VoiceEffect>.spoken() = filterIsInstance<VoiceEffect.Speak>().map { it.text }
private fun List<VoiceEffect>.goals() = filterIsInstance<VoiceEffect.StartTask>().map { it.goal }

class VoiceSessionControllerTest {
    private fun controller(
        caps: VoiceCapabilities = VoiceCapabilities.forEdition(VoiceEdition.PUBLIC),
        tasks: FakeTasks = FakeTasks(),
        consent: VoiceConsent = VoiceConsent.granted(),
        wakeWords: () -> List<String> = { caps.wakeWords() },
        nowMs: () -> Long = { 0L },
    ) = VoiceSessionController(caps, wakeWords, tasks, { consent }, nowMs)

    @Test fun missingConsentNeverOpensTheMic() {
        val c = controller(consent = VoiceConsent())
        val effects = c.onWake(WakeSource.SystemAssistant)
        assertTrue(effects.none { it is VoiceEffect.SetMic && it.enabled })
        assertEquals(listOf(VoiceDisclosure.MISSING_CONSENT), effects.spoken())
        assertTrue(c.onSpeechDone().contains(VoiceEffect.Finish))
        assertEquals(VoiceSessionState.Closed, c.state)
    }

    @Test fun publicWakeOpensMicWithoutAGreeting() {
        val c = controller()
        val effects = c.onWake(WakeSource.SystemAssistant)
        assertEquals(VoiceEffect.SetMic(true, "wake"), effects.filterIsInstance<VoiceEffect.SetMic>().single())
        assertTrue(effects.spoken().isEmpty())
        assertEquals(VoiceSessionState.Listening, c.state)
    }

    @Test fun personalHotwordGreetsThenResumesListening() {
        val c = controller(caps = VoiceCapabilities.forEdition(VoiceEdition.PERSONAL))
        val effects = c.onWake(WakeSource.LocalHotword)
        assertEquals(listOf(VoiceSessionPrompts.GREETING), effects.spoken())
        assertEquals(VoiceEffect.SetMic(false, "greeting"), effects.filterIsInstance<VoiceEffect.SetMic>().single())
        assertEquals(VoiceEffect.SetMic(true, "resume"), c.onSpeechDone().single())
        assertEquals(VoiceSessionState.Listening, c.state)
    }

    @Test fun taskLoopEndsWithSpokenResultInsteadOfMakingTheUserLookAtTheScreen() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        val out = c.onUtterance("帮我查一下明天北京的天气")
        tasks.dispatch(out)
        assertEquals(listOf("帮我查一下明天北京的天气"), out.goals())
        assertEquals(listOf(VoiceSessionPrompts.TASK_STARTED), out.spoken())
        assertFalse((out.filterIsInstance<VoiceEffect.SetMic>().single()).enabled)

        assertEquals(VoiceEffect.SetMic(true, "resume_control_only"), c.onSpeechDone().single())
        assertEquals(VoiceSessionState.WaitingTask, c.state)

        val result = c.onTaskFinished("查到了，明天北京晴")
        assertEquals(listOf("查到了，明天北京晴"), result.spoken())
        assertTrue(c.onSpeechDone().contains(VoiceEffect.Finish))
        assertEquals(VoiceSessionState.Closed, c.state)
        assertEquals(1, tasks.started.size)
    }

    @Test fun ttsSelfEchoIsDroppedWhileSpeaking() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        tasks.dispatch(c.onUtterance("帮我查天气")) // 现在是 Speaking，正在播「好，我去做」
        val echo = c.onUtterance("顺便给张三转账")
        assertEquals(listOf(VoiceEffect.Note("self_echo_dropped")), echo)
        assertTrue(echo.goals().isEmpty())
        assertEquals(1, tasks.started.size)
    }

    @Test fun ambientSpeechBeforeWakeIsIgnored() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        assertEquals(listOf(VoiceEffect.Note("utterance_without_wake")), c.onUtterance("帮我查天气"))
        assertEquals(0, tasks.started.size)
    }

    @Test fun sensitiveTaskWaitsForSpokenConfirmation() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        val first = c.onUtterance("给张三转账 100 元")
        tasks.dispatch(first)
        assertTrue(first.goals().isEmpty())
        assertTrue(first.spoken().single().contains("确认"))
        c.onSpeechDone()
        val confirmed = c.onUtterance("确认")
        tasks.dispatch(confirmed)
        assertEquals(listOf("给张三转账 100 元"), confirmed.goals())
        assertEquals(1, tasks.started.size)
    }

    @Test fun fixedWakeWordIsStrippedFromTheGoal() {
        val c = controller()
        c.onWake(WakeSource.SystemAssistant)
        assertEquals(
            listOf("帮我打开微信"),
            c.onUtterance("${VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD}，帮我打开微信").goals(),
        )
    }

    @Test fun renamedWakeWordTakesEffectInPersonalEdition() {
        val caps = VoiceCapabilities.forEdition(VoiceEdition.PERSONAL)
        val settings = WakeWordSettings(caps)
        settings.set("阿福")
        val c = controller(caps = caps, wakeWords = settings::wakeWords)
        c.onWakeWordChanged()
        c.onWake(WakeSource.LocalHotword)
        c.onSpeechDone() // 播完「在，请说。」
        assertEquals(listOf("帮我定个闹钟"), c.onUtterance("阿福，帮我定个闹钟").goals())
        // 出厂词仍然兼容
        assertEquals(listOf(VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD), settings.wakeWords().take(1))
    }

    @Test fun whileTaskRunsOnlyShortControlPhrasesAreAccepted() {
        val tasks = FakeTasks(statusText = "正在查天气")
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        tasks.dispatch(c.onUtterance("帮我查天气"))
        c.onSpeechDone()
        assertEquals(VoiceSessionState.WaitingTask, c.state)

        val chat = c.onUtterance("顺便帮我发个消息")
        assertEquals(listOf(VoiceEffect.Note("busy_ignored")), chat)
        assertEquals(1, tasks.started.size)

        val cancel = c.onUtterance("取消")
        tasks.dispatch(cancel)
        assertTrue(cancel.contains(VoiceEffect.CancelTask))
        assertEquals(1, tasks.cancelled)
    }

    @Test fun timeoutSpeaksBeforeFinishingSoTtsIsNotCutOff() {
        var now = 0L
        val c = controller(nowMs = { now })
        c.onWake(WakeSource.SystemAssistant)
        now = 20_000L
        val out = c.onTimeout()
        assertEquals(listOf(VoiceSessionPrompts.TIMEOUT), out.spoken())
        assertFalse(out.contains(VoiceEffect.Finish))
        assertTrue(c.onSpeechDone().contains(VoiceEffect.Finish))
    }

    @Test fun waitingForTaskDoesNotTimeOutLikeListeningDoes() {
        var now = 0L
        val c = controller(nowMs = { now })
        c.onWake(WakeSource.SystemAssistant)
        c.onUtterance("帮我查天气")
        c.onSpeechDone()
        now = 300_000L // 任务跑了五分钟没说话，不该被静默超时收掉
        assertTrue(c.onTimeout().isEmpty())
        assertEquals(VoiceSessionState.WaitingTask, c.state)
        assertEquals(listOf(VoiceSessionPrompts.TASK_STILL_RUNNING), c.onTaskStillRunning().spoken())
    }

    @Test fun maxTurnsStopsAnEndlessHandsFreeLoop() {
        val c = controller()
        c.onWake(WakeSource.SystemAssistant)
        repeat(8) {
            c.onUtterance("进度")
            c.onSpeechDone()
        }
        assertTrue(c.onUtterance("进度").spoken().contains(VoiceSessionPrompts.TOO_MANY_TURNS))
    }

    @Test fun resultArrivingDuringTtsIsQueuedNotLost() {
        val c = controller()
        c.onWake(WakeSource.SystemAssistant)
        c.onUtterance("帮我查天气") // 正在播 TASK_STARTED
        assertEquals(listOf(VoiceEffect.Note("result_queued")), c.onTaskFinished("查到了"))
        assertEquals(listOf("查到了"), c.onSpeechDone().spoken())
        assertTrue(c.onSpeechDone().contains(VoiceEffect.Finish))
    }

    @Test fun hidingTheSessionFinishesIt() {
        val c = controller()
        c.onWake(WakeSource.SystemAssistant)
        assertTrue(c.onHide().contains(VoiceEffect.Finish))
        assertEquals(VoiceSessionState.Closed, c.state)
        assertEquals(listOf(VoiceEffect.Note("wake_after_close")), c.onWake(WakeSource.SystemAssistant))
    }

    @Test fun identityQuestionIsAnsweredVerbatimWithoutStartingATask() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        val effects = c.onUtterance("诺伊，你是谁")
        assertEquals(listOf(ProductIdentity.REPLY), effects.spoken())
        assertTrue(effects.none { it is VoiceEffect.StartTask })
        assertTrue(effects.contains(VoiceEffect.SetMic(false, "speak"))) // 播报时真的关麦
        assertEquals(emptyList<String>(), tasks.started)
        // 答完接着听，会话不收场
        assertEquals(VoiceEffect.SetMic(true, "resume"), c.onSpeechDone().single())
        assertEquals(VoiceSessionState.Listening, c.state)
    }

    /** 原句里叫着名字问（剥掉唤醒词只剩「是谁」），仍必须认出来。 */
    @Test fun thirdPersonIdentityQuestionAlsoGetsTheFixedReply() {
        val c = controller()
        c.onWake(WakeSource.SystemAssistant)
        assertEquals(listOf(ProductIdentity.REPLY), c.onUtterance("诺伊是谁").spoken())
    }

    @Test fun identityQuestionWhileTaskRunsKeepsTheTask() {
        val tasks = FakeTasks()
        val c = controller(tasks = tasks)
        c.onWake(WakeSource.SystemAssistant)
        c.onUtterance("帮我查天气")
        c.onSpeechDone()
        assertEquals(VoiceSessionState.WaitingTask, c.state)
        val effects = c.onUtterance("你是谁")
        assertEquals(listOf(ProductIdentity.REPLY), effects.spoken())
        assertTrue(effects.none { it is VoiceEffect.CancelTask })
        assertEquals(VoiceEffect.SetMic(true, "resume_control_only"), c.onSpeechDone().single())
        assertEquals(VoiceSessionState.WaitingTask, c.state)
    }
}
