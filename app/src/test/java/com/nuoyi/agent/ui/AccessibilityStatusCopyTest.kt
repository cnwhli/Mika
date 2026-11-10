package com.nuoyi.agent.ui

import com.nuoyi.agent.i18n.ChineseTexts
import com.nuoyi.agent.settings.AccessibilityState
import com.nuoyi.agent.settings.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilityStatusCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test fun restrictedDisabledAccessibilityExplainsTheAppInfoRoute() {
        val problem = Problem.Accessibility(AccessibilityState.Off, restrictedSettings = true)
        assertTrue(StatusCopy.banner(problem).detail.contains("允许受限制的设置"))
        val explanation = StatusCopy.explain(problem, "14")
        assertTrue(explanation.body.contains("应用信息"))
        assertTrue(explanation.body.contains("如果有此项"))
        assertFalse(explanation.body.contains("关闭后重新开启"))
        assertEquals(Fix.OpenAccessibility, explanation.fix)
        assertEquals(Fix.OpenAppInfo, explanation.secondaryAction?.second)
    }

    @Test fun unrestrictedDisabledAccessibilityOpensAccessibilitySettings() {
        val problem = Problem.Accessibility(AccessibilityState.Off)
        assertFalse(StatusCopy.banner(problem).detail.contains("允许受限制的设置"))
        val explanation = StatusCopy.explain(problem, "14")
        assertEquals(Fix.OpenAccessibility, explanation.fix)
        assertNull(explanation.secondaryAction)
    }
}
