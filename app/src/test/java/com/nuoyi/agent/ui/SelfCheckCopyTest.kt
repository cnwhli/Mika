package com.nuoyi.agent.ui

import com.nuoyi.agent.about.Diagnostics
import com.nuoyi.agent.core.selfcheck.Advice
import com.nuoyi.agent.core.selfcheck.CheckResult
import com.nuoyi.agent.core.selfcheck.CheckStatus
import com.nuoyi.agent.core.selfcheck.DeviceFacts
import com.nuoyi.agent.core.selfcheck.SelfCheck
import com.nuoyi.agent.core.selfcheck.SelfCheckReport
import com.nuoyi.agent.core.selfcheck.SelfCheckState
import com.nuoyi.agent.i18n.ChineseTexts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SelfCheckCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val jargon = listOf("shell", "Binder", "uid", "adb", "SDK", "ABI")

    /** 报告走「复制诊断信息」同一套脱敏:IP、端口、邮箱、像凭据的值都不出现。 */
    @Test
    fun reportIsSanitizedLikeDiagnostics() {
        val facts = DeviceFacts("Acme", "Phone 7", "14", 34, listOf("arm64-v8a"), 8L shl 30, 1080, 2400, true, "0.1.21", "abc1234")
        val state = SelfCheckState(
            SelfCheckState.Phase.Done,
            SelfCheck.entries.map { CheckResult(it, CheckStatus.Pass, "ok") }.toMutableList().also {
                it[SelfCheck.HELPER.ordinal] = CheckResult(SelfCheck.HELPER, CheckStatus.Fail, "connect 192.168.1.20:37011 failed, token=abcdef123456 me@example.com", Advice.Error)
            },
            facts, 30_000,
        )
        val text = SelfCheckReport.render(state, Diagnostics::sanitize)
        assertFalse(text.contains("192.168"))
        assertFalse(text.contains("me@example.com"))
        assertFalse(text.contains("abcdef123456"))
        assertTrue(text.contains("3 helper: FAIL — connect <ip> failed"))
    }
}
