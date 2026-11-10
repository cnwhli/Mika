package com.nuoyi.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCapabilitiesTest {
    @Test fun publicEditionLocksEverythingDown() {
        val caps = VoiceCapabilities.forEdition(VoiceEdition.PUBLIC)
        assertEquals(VoiceEdition.PUBLIC, caps.edition)
        assertFalse(caps.allowCustomWakeWord)
        assertFalse(caps.allowAlwaysOnListening)
        assertTrue(caps.allowAssistantEntry)
        assertFalse(caps.greetOnWake) // 系统助手自带提示音，别重复播
    }

    @Test fun personalEditionAllowsHotwordAndAlwaysOn() {
        val caps = VoiceCapabilities.forEdition(VoiceEdition.PERSONAL)
        assertTrue(caps.allowCustomWakeWord)
        assertTrue(caps.allowAlwaysOnListening)
        assertTrue(caps.greetOnWake)
    }

    @Test fun publicEditionRejectsCustomWakeWordAtRuntime() {
        val settings = WakeWordSettings(VoiceCapabilities.forEdition(VoiceEdition.PUBLIC))
        val before = settings.current()
        val r = settings.set("阿福")
        assertEquals(WakeWordResult.Bad(WakeWordReject.EditionLocked), r)
        assertEquals(before, settings.current())
        assertFalse(settings.isCustom())
        assertEquals(listOf(before), settings.wakeWords())
    }

    @Test fun personalEditionAppliesCustomWakeWord() {
        val settings = WakeWordSettings(VoiceCapabilities.forEdition(VoiceEdition.PERSONAL))
        assertEquals(WakeWordResult.Ok("阿福", custom = true), settings.set("阿福"))
        assertEquals("阿福", settings.current())
        assertTrue(settings.isCustom())
        // 固定词仍保留，老习惯喊出厂词一样能醒
        assertEquals(listOf(VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD, "阿福"), settings.wakeWords())
        settings.reset()
        assertEquals(VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD, settings.current())
    }

    @Test fun rejectWordsThatBreakTheDialog() {
        fun reason(s: String) = (WakeWordPolicy.check(s) as WakeWordResult.Bad).reason
        assertEquals(WakeWordReject.Empty, reason("   "))
        assertEquals(WakeWordReject.TooShort, reason("阿"))
        assertEquals(WakeWordReject.TooLong, reason("一二三四五六七八九"))
        assertEquals(WakeWordReject.IllegalChar, reason("小@舵"))
        assertEquals(WakeWordReject.IllegalChar, reason("小，舵"))
        assertEquals(WakeWordReject.AllDigits, reason("1234"))
        assertEquals(WakeWordReject.ReservedWord, reason("确认"))
        assertEquals(WakeWordReject.ReservedWord, reason("帮我转账"))
        assertEquals(WakeWordReject.CompetingAssistant, reason("小爱同学"))
    }

    @Test fun normalizeKeepsChineseAndFoldsFullWidth() {
        assertEquals(VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD, WakeWordPolicy.normalize("  诺伊，"))
        assertEquals("A1", WakeWordPolicy.normalize("Ａ１"))
        assertEquals(WakeWordResult.Ok("阿福", custom = false), WakeWordPolicy.check(" 阿福 "))
    }

    /** 唤醒词是产品决策，锁在这里：换词要显式改，不能因为重构悄悄变回去。 */
    @Test fun factoryWakeWordIsNoYi() {
        assertEquals("诺伊", VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD)
        assertEquals(WakeWordResult.Ok("诺伊", custom = false), WakeWordPolicy.check("诺伊"))
        assertEquals("诺伊", VoiceCapabilities.publicEdition().fixedWakeWord)
        assertEquals("诺伊", VoiceCapabilities.personalEdition().fixedWakeWord)
        assertEquals(listOf("诺伊"), VoiceCapabilities.publicEdition().wakeWords())
    }

    @Test fun invalidCustomWordDoesNotReplaceCurrent() {
        val settings = WakeWordSettings(VoiceCapabilities.forEdition(VoiceEdition.PERSONAL))
        settings.set("阿福")
        assertEquals(WakeWordResult.Bad(WakeWordReject.CompetingAssistant), settings.set("小爱"))
        assertEquals("阿福", settings.current())
    }

    @Test fun invalidFixedWordFallsBackToDefault() {
        val caps = VoiceCapabilities.publicEdition("确认")
        assertEquals(VoiceCapabilities.DEFAULT_FIXED_WAKE_WORD, caps.fixedWakeWord)
    }

    @Test fun publicResolveCannotBeBribedIntoAccepting() {
        // 能力集上没有「解锁」参数；即便传入合法词，公版也只回 EditionLocked
        val caps = VoiceCapabilities.forEdition(VoiceEdition.PUBLIC)
        assertEquals(WakeWordResult.Bad(WakeWordReject.EditionLocked), caps.resolve("阿福"))
    }
}
