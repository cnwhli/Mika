package com.nuoyi.agent.cap.phone.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nuoyi.agent.cap.phone.shell.AsciiTextKeys
import com.nuoyi.agent.cap.phone.shell.ShellService
import com.nuoyi.agent.core.uistate.ActionResult
import com.nuoyi.agent.core.uistate.UiElement
import com.nuoyi.agent.core.uistate.Verification
import com.nuoyi.agent.shell.INuoyiShell
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HiddenFocusedEditorTest {
    @Test fun implicitTypeRejectsTheCollapsedEditor() = rejects { screen, _ ->
        screen.typeText(null, null, "New draft")
    }

    @Test fun implicitClearRejectsTheCollapsedEditor() = rejects { screen, _ ->
        screen.clearText(null, null)
    }

    @Test fun pointTypeCannotFallBackToTheCollapsedEditor() = rejects { screen, surface ->
        screen.typeAt(surface.centerX, surface.centerY, "New draft")
    }

    @Test fun pointClearCannotFallBackToTheCollapsedEditor() = rejects { screen, surface ->
        screen.clearAt(surface.centerX, surface.centerY)
    }

    @Test fun editorCollapsingAfterInputCannotVerifyTheWrite() = runBlocking {
        withEditor(collapseAfterWrite = true) { screen ->
            val result = screen.typeText(null, null, "New draft")
            assertEquals(result.toString(), Verification.UNKNOWN, (result as? ActionResult.Ok)?.verification)
            assertTrue(screen.snapshot().elements.any { it.name == "Writes: 1" })
        }
    }

    private fun rejects(action: suspend (AndroidScreen, UiElement) -> ActionResult) = runBlocking {
        withEditor { screen ->
            val surface = screen.snapshot().elements.single { it.name == "Editor surface" }
            val result = action(screen, surface)
            assertEquals(result.toString(), "editor_not_visible", (result as? ActionResult.Failed)?.reason)
            assertTrue(screen.snapshot().elements.any { it.name == "Writes: 0" })
        }
    }

    private suspend fun withEditor(collapseAfterWrite: Boolean = false, check: suspend (AndroidScreen) -> Unit) {
        val agent = DeviceSupport.newAgent()
        try {
            val a11y = DeviceSupport.service()
            val shell = DeviceSupport.shell()
            var fallbackCalls = 0
            val guardedShell = object : INuoyiShell by shell {
                override fun setClipText(text: String): Int {
                    fallbackCalls++
                    return ShellService.CLIP_FAILED
                }
                override fun restoreClip(): Boolean = true
                override fun injectCtrlKey(displayId: Int, keyCode: Int): Int {
                    fallbackCalls++
                    return AsciiTextKeys.NOT_SENT
                }
                override fun injectKey(displayId: Int, keyCode: Int): Boolean {
                    fallbackCalls++
                    return false
                }
            }
            val screen = AndroidScreen(a11y, guardedShell, DeviceSupport.ready(agent.display))
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            val component = "$testPackage/${HiddenFocusedEditorActivity::class.java.name}"
            val output = DeviceSupport.sh(
                "am start -W -f 0x18000000 --display ${agent.display.displayId} -n $component " +
                    "--ez collapse_after_write $collapseAfterWrite",
            )
            check("Status: ok" in output) { output }
            val focus = withTimeout(8_000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) {
                    node = a11y.windowsOn(agent.display.displayId).firstNotNullOfOrNull {
                        it.root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf(ScreenReader::canSetText)
                    }
                    if (node == null) delay(100)
                }
                node
            }
            val bounds = Rect().also(focus::getBoundsInScreen)
            assertEquals(collapseAfterWrite, bounds.width() > 0)
            check(screen)
            assertEquals("No clipboard or keyboard fallback should run", 0, fallbackCalls)
        } finally {
            agent.release()
        }
    }
}
