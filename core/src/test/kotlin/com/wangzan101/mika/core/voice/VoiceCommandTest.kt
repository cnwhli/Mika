package com.wangzan101.mika.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCommandTest {
    /** 按住说话：不需要唤醒词，说完整句就是任务目标。 */
    private val pushToTalk = VoiceConfig()

    private fun alwaysOn(vararg words: String) =
        VoiceConfig(wakeWords = words.toList(), requireWakeWord = true)

    @Test fun pushToTalkStartsTaskVerbatim() {
        val d = VoiceDialog(pushToTalk)
        val r = d.onSpeech("  帮我查一下明天北京的天气  ")
        assertEquals("帮我查一下明天北京的天气", r.transcript)
        assertEquals(VoiceAction.StartTask("帮我查一下明天北京的天气"), r.action)
        assertEquals(VoiceIntent.StartTask, r.intent)
        assertEquals(VoiceRisk.Normal, r.risk)
        assertNull(r.spokenReply) // 任务已开始，交给任务自己汇报进度
    }

    @Test fun alwaysOnIgnoresSpeechWithoutWakeWord() {
        val d = VoiceDialog(alwaysOn("小秘"))
        val r = d.onSpeech("帮我把灯关了")
        assertEquals(VoiceAction.Ignore, r.action)
        assertEquals(VoiceIntent.Ignore, r.intent)
        assertFalse(r.wakeMatched)
        assertNull(r.spokenReply) // 不播报，否则会一直插嘴
    }

    @Test fun alwaysOnStripsWakeWordAndPunctuation() {
        val d = VoiceDialog(alwaysOn("小秘"))
        val r = d.onSpeech("小秘，帮我打开微信")
        assertTrue(r.wakeMatched)
        assertEquals("帮我打开微信", r.transcript)
        assertEquals(VoiceAction.StartTask("帮我打开微信"), r.action)
    }

    @Test fun longestWakeWordWins() {
        val d = VoiceDialog(alwaysOn("小", "小秘"))
        val r = d.onSpeech("小秘：打开相册")
        assertEquals("打开相册", r.transcript)
    }

    @Test fun fullWidthPunctuationIsNormalized() {
        val d = VoiceDialog(alwaysOn("小秘"))
        val r = d.onSpeech("小秘，帮我查天气")
        assertEquals("帮我查天气", r.transcript)
        assertEquals(VoiceAction.StartTask("帮我查天气"), r.action)
    }

    @Test fun sensitiveActionRequiresSpokenConfirmation() {
        var now = 0L
        val d = VoiceDialog(pushToTalk) { now }
        val first = d.onSpeech("给张三转账 100 元")
        assertTrue(first.action is VoiceAction.AwaitConfirmation)
        assertEquals("给张三转账 100 元", (first.action as VoiceAction.AwaitConfirmation).goal)
        assertEquals(VoiceRisk.Sensitive, first.risk)
        assertTrue(first.spokenReply!!.contains("确认"))
        assertTrue(d.hasPendingConfirmation())

        now = 10_000L
        val second = d.onSpeech("确认")
        assertEquals(VoiceIntent.Confirm, second.intent)
        assertEquals(VoiceAction.StartTask("给张三转账 100 元"), second.action)
        assertFalse(d.hasPendingConfirmation())
    }

    @Test fun rejectionCancelsPendingSensitiveAction() {
        val d = VoiceDialog(pushToTalk)
        d.onSpeech("删除聊天记录")
        val r = d.onSpeech("算了")
        assertEquals(VoiceAction.Cancel, r.action)
        assertFalse(d.hasPendingConfirmation())
        // 取消之后说别的话，是全新的指令，不会误触发刚才那件事
        assertEquals(VoiceAction.StartTask("帮我记一下明天买牛奶"), d.onSpeech("帮我记一下明天买牛奶").action)
    }

    @Test fun unrelatedSpeechWhileAwaitingConfirmationIsNotTreatedAsConfirm() {
        val d = VoiceDialog(pushToTalk)
        d.onSpeech("给妈妈发红包")
        val r = d.onSpeech("帮我查一下天气")
        assertTrue(r.action is VoiceAction.Clarify)
        assertEquals(VoicePrompts.CONFIRM_HINT, r.spokenReply)
        assertTrue(d.hasPendingConfirmation())
    }

    @Test fun confirmationExpiresAndDoesNotFireLater() {
        var now = 0L
        val d = VoiceDialog(pushToTalk) { now }
        d.onSpeech("给妈妈发红包")
        now = 61_000L
        assertFalse(d.hasPendingConfirmation())
        assertEquals(VoiceAction.StartTask("确认"), d.onSpeech("确认").action)
    }

    @Test fun correctionSteersOnlyWhenATaskIsRunning() {
        val d = VoiceDialog(pushToTalk)
        assertEquals(
            VoiceAction.Steer("不对，改成明天"),
            d.onSpeech("不对，改成明天", hasActiveTask = true).action,
        )
        assertEquals(
            VoiceAction.StartTask("顺便帮我记一下"),
            d.onSpeech("顺便帮我记一下", hasActiveTask = false).action,
        )
    }

    @Test fun shortControlPhrases() {
        val d = VoiceDialog(pushToTalk)
        assertEquals(VoiceAction.Cancel, d.onSpeech("取消").action)
        assertEquals(VoiceAction.Status, d.onSpeech("到哪了").action)
        assertTrue(d.onSpeech("你能做什么").action is VoiceAction.Help)
        // 长句里带「取消」不应被当成取消
        assertEquals(
            VoiceAction.StartTask("取消，顺便帮我查一下订单"),
            d.onSpeech("取消，顺便帮我查一下订单").action,
        )
    }

    @Test fun emptyAndWakeOnlyUtterancesAskToRepeat() {
        val push = VoiceDialog(pushToTalk)
        assertEquals(VoicePrompts.EMPTY, push.onSpeech("   ").spokenReply)

        val handsFree = VoiceDialog(alwaysOn("小秘"))
        assertEquals(VoicePrompts.WAKE_ONLY, handsFree.onSpeech("小秘").spokenReply)
        assertEquals(VoicePrompts.WAKE_ONLY, handsFree.onSpeech("小秘，").spokenReply)
    }

    @Test fun customWordListsReplaceProductSpecificVocabulary() {
        val config = VoiceConfig(
            wakeWords = listOf("你好阿福"),
            requireWakeWord = true,
            sensitiveWords = listOf("发射"),
            cancelPhrases = listOf("住手"),
            confirmWords = listOf("就这么办"),
        )
        val d = VoiceDialog(config)
        val first = d.onSpeech("你好阿福，发射信号")
        assertTrue(first.action is VoiceAction.AwaitConfirmation)
        // 回「确认」时不用再喊一次唤醒词
        assertEquals(VoiceAction.StartTask("发射信号"), d.onSpeech("就这么办").action)
        assertEquals(VoiceAction.Cancel, d.onSpeech("你好阿福，住手").action)
        // 「转账」不在自定义敏感表里，直接执行
        assertEquals(VoiceAction.StartTask("转账给张三"), d.onSpeech("你好阿福，转账给张三").action)
    }

    @Test fun identityQuestionGetsFixedReplyAndNeverBecomesATask() {
        val d = VoiceDialog(pushToTalk)
        val r = d.onSpeech("你是谁")
        assertEquals(VoiceIntent.Identity, r.intent)
        assertEquals(VoiceAction.Reply(ProductIdentity.REPLY), r.action)
        assertEquals(ProductIdentity.REPLY, r.spokenReply)
        assertEquals(VoiceRisk.Normal, r.risk)
    }

    @Test fun identityQuestionKeepsPendingConfirmationAlive() {
        val d = VoiceDialog(pushToTalk) { 0L }
        assertTrue(d.onSpeech("给张三转账 100 元").action is VoiceAction.AwaitConfirmation)
        assertEquals(VoiceAction.Reply(ProductIdentity.REPLY), d.onSpeech("你是谁").action)
        assertTrue(d.hasPendingConfirmation())
        // 插一句闲聊不会把待确认的敏感任务冲掉
        assertEquals(VoiceAction.StartTask("给张三转账 100 元"), d.onSpeech("确认").action)
    }
}
