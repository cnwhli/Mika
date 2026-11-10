package com.nuoyi.agent.ui

import com.nuoyi.agent.ChineseUi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ClipboardManager
import com.nuoyi.agent.about.CheckStatus
import com.nuoyi.agent.about.NuoyiLinks
import com.nuoyi.agent.about.ReleaseInfo
import com.nuoyi.agent.about.UpdateState
import com.nuoyi.agent.core.shell.BuiltinShellStatus
import com.nuoyi.agent.settings.AccessibilityState
import com.nuoyi.agent.settings.Prerequisites
import com.nuoyi.agent.settings.SystemStatus
import com.nuoyi.agent.ui.theme.NuoyiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AboutScreenTest {
    @get:Rule(order = Int.MIN_VALUE) val chinese = ChineseUi()

    @get:Rule val compose = createComposeRule()

    private val r020 = ReleaseInfo(
        "0.2.0", "Nuoyi 0.2.0", "### 新增\n- 附件面板", "2026-11-05",
        "https://github.com/wangzan101/Mika/releases/tag/v0.2.0", "https://github.com/x/nuoyi-0.2.0.apk",
    )

    private class Calls {
        val urls = mutableListOf<String>()
        var checks = 0
        var licenses = 0
    }

    private val sampleDiagnostics = "诺伊 诊断信息 / 诺伊 diagnostics\n\n[App]\nversion: 0.1.0 (build abc1234)\n"

    private fun about(update: UpdateState = UpdateState()): Calls {
        val calls = Calls()
        compose.setContent {
            NuoyiTheme {
                AboutScreen(
                    versionName = "0.1.0", build = "abc1234", beta = true, update = update, issueUrl = "issue://prefilled",
                    onCheck = { calls.checks++ }, onOpenUrl = { calls.urls += it },
                    onOpenLicense = { calls.licenses++ }, onBack = {},
                    diagnostics = { sampleDiagnostics },
                )
            }
        }
        return calls
    }

    private fun scrollTo(text: String) {
        compose.awaitOne(text).performScrollTo()
        compose.waitForIdle()
    }

    @Test
    fun showsHeroRowsNoteAndFooter() {
        about()
        compose.onNodeWithText("Nuoyi").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.TAGLINE).assertIsDisplayed()
        compose.onNodeWithText("版本 0.1.0").assertIsDisplayed()
        compose.onNodeWithText("Beta").assertIsDisplayed()
        compose.onNodeWithText("· 构建 abc1234").assertIsDisplayed()
        for (t in listOf("检查更新", "更新记录", "反馈问题", "开源仓库", "使用说明与参与方式", AboutCopy.DATA_NOTE, AboutCopy.AUTHOR)) {
            scrollTo(t)
            compose.onNodeWithText(t).assertIsDisplayed()
        }
        // 许可证只在页脚,不再单独一行
        compose.onAllNodesWithText("开源许可").assertCountEquals(0)
    }

    @Test
    fun externalRowsOpenLinks() {
        val calls = about()
        for (t in listOf("开源仓库", "使用说明与参与方式")) {
            scrollTo(t)
            compose.onNodeWithText(t).performClick()
        }
        assertEquals(listOf(NuoyiLinks.REPO, NuoyiLinks.README), calls.urls)
    }

    @Test
    fun footerOpensLicense() {
        val calls = about()
        compose.onNodeWithTag("about-license").performScrollTo().performClick()
        assertEquals(1, calls.licenses)
    }

    @Test
    fun feedbackSheetHasGitHubAndDiagnostics() {
        val calls = about()
        compose.onNodeWithText("反馈问题").performClick()
        compose.awaitOne(AboutCopy.FEEDBACK_SHEET_SUB)
        compose.onAllNodesWithTag("feedback-option").assertCountEquals(2)
        compose.onNodeWithText("复制诊断信息").assertIsDisplayed()
        compose.onNodeWithText(AboutCopy.FEEDBACK_GITHUB).performClick()
        compose.waitForIdle()
        assertEquals(listOf("issue://prefilled"), calls.urls)
    }

    /** 「复制诊断信息」先打开预览:整段文字看得见,点「复制」才进剪贴板。 */
    @Test
    fun feedbackDiagnosticsPreviewsThenCopies() {
        about()
        compose.onNodeWithText("反馈问题").performClick()
        compose.awaitOne("复制诊断信息").performClick()
        compose.awaitOne("不包含 API Key、聊天内容和屏幕内容")
        compose.waitUntil(5_000) { compose.onAllNodesWithText(sampleDiagnostics).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics-text").assertIsDisplayed()
        compose.onNodeWithTag("diagnostics-share").assertExists()
        compose.onNodeWithTag("diagnostics-copy").performClick()
        compose.waitForIdle()
        var clip: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val cm = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
            clip = cm.primaryClip?.getItemAt(0)?.text?.toString()
        }
        assertEquals(sampleDiagnostics, clip)
    }

    @Test
    fun checkRowStartsCheckWhenNoUpdate() {
        val calls = about(UpdateState(latest = r020.copy(version = "0.1.0")))
        compose.onNodeWithText(AboutCopy.UP_TO_DATE).assertIsDisplayed()
        compose.onNodeWithText("检查更新").performClick()
        assertEquals(1, calls.checks)
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun checkingShowsSpinner() {
        about(UpdateState(status = CheckStatus.Checking))
        compose.onNodeWithTag("update-spinner").assertExists()
    }

    @Test
    fun offlineShowsReason() {
        about(UpdateState(status = CheckStatus.Failed))
        compose.onNodeWithText(AboutCopy.OFFLINE).assertIsDisplayed()
    }

    @Test
    fun updateRowOpensUpdateSheet() {
        val calls = about(UpdateState(latest = r020, update = r020, status = CheckStatus.Done))
        compose.onNodeWithText("0.2.0 可更新").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("检查更新").performClick()
        compose.awaitOne("发现新版本 0.2.0")
        compose.onNodeWithText("当前 0.1.0 Beta · 2026-11-05 发布").assertExists()
        compose.onNodeWithText("以后再说").assertExists()
        compose.onNodeWithText("下载更新").assertExists()
        compose.waitForIdle()
        assertEquals(0, calls.checks)
        assertTrue(calls.urls.isEmpty())
    }

    @Test
    fun updateWithoutApkStillOpensReleasePage() {
        val r = r020.copy(apkUrl = null)
        val calls = about(UpdateState(latest = r, update = r))
        compose.onNodeWithText("检查更新").performClick()
        compose.awaitOne("从 GitHub 下载").performClick()
        assertEquals(listOf(r.pageUrl), calls.urls)
    }

    @Test
    fun downloadedUpdateOffersInstallAndDelete() {
        var installs = 0
        var deletes = 0
        compose.setContent {
            NuoyiTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.nuoyi.agent.about.ApkDownloadState(com.nuoyi.agent.about.DownloadPhase.Ready),
                        false, null, {}, { installs++ }, { deletes++ })
                }
            }
        }
        compose.onNodeWithText("安装更新").performClick()
        compose.onNodeWithText("删除下载").performClick()
        assertEquals(1, installs)
        assertEquals(1, deletes)
    }

    @Test
    fun downloadingShowsProgressAndCanBeCancelled() {
        var deletes = 0
        compose.setContent {
            NuoyiTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.nuoyi.agent.about.ApkDownloadState(com.nuoyi.agent.about.DownloadPhase.Downloading, 50, 100),
                        false, null, {}, {}, { deletes++ })
                }
            }
        }
        compose.onNodeWithText("正在下载 50%").assertIsNotEnabled()
        compose.onNodeWithText("删除下载").performClick()
        assertEquals(1, deletes)
    }

    @Test
    fun failedDownloadCanBeRetried() {
        var retries = 0
        compose.setContent {
            NuoyiTheme {
                androidx.compose.foundation.layout.Column {
                    UpdateDownloadActions(com.nuoyi.agent.about.ApkDownloadState(com.nuoyi.agent.about.DownloadPhase.Failed),
                        false, null, { retries++ }, {}, {})
                }
            }
        }
        compose.onNodeWithText("重新下载").performClick()
        assertEquals(1, retries)
    }

    @Test
    fun changelogSheetShowsLatestNotes() {
        val calls = about(UpdateState(latest = r020))
        compose.onNodeWithText("更新记录").performClick()
        compose.awaitOne(AboutCopy.CHANGELOG_ALL)
        compose.onNodeWithText("0.2.0 · 2026-11-05").assertExists()
        compose.onNodeWithText(AboutCopy.CHANGELOG_ALL).performClick()
        compose.waitForIdle()
        assertEquals(listOf(NuoyiLinks.RELEASES), calls.urls)
    }

    /** 侧栏最底下一行两个:设置靠左、关于 Nuoyi 靠右(红点在最右边);有新版时关于带红点。 */
    @Test
    fun drawerFooterHasSettingsAndAbout() {
        val calls = mutableListOf<String>()
        compose.setContent {
            NuoyiTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {},
                    onSettings = { calls += "settings" }, onOpen = {},
                    onAbout = { calls += "about" }, aboutDot = true,
                )
            }
        }
        compose.onNodeWithText("设置").assertIsDisplayed()
        compose.onNodeWithText("关于 诺伊").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
        val footer = compose.onNodeWithTag("drawer-footer").getUnclippedBoundsInRoot()
        // 量文字本身(未合并的树):合并后的可点行节点比文字宽,量它判断不了左右半边
        val settings = compose.onNodeWithText("设置", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val about = compose.onNodeWithText("关于 诺伊", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val dot = compose.onNodeWithTag("update-dot", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val mid = (footer.left + footer.right) / 2
        assertTrue(settings.right < mid)
        assertTrue(about.left > mid)
        // 关于那一项贴着右边:红点离行的右边只隔两层内边距(行 12dp + 项 12dp)
        assertTrue(footer.right - dot.right <= 25.dp)
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("关于 诺伊").performClick()
        assertEquals(listOf("settings", "about"), calls)
    }

    @Test
    fun drawerFooterNoDotWithoutUpdate() {
        compose.setContent {
            NuoyiTheme {
                ChatDrawer(
                    Prerequisites.Check(emptyList()), DrawerSections(emptyList(), emptyList()), canSwitch = true,
                    onNew = {}, onPlans = {}, onMemory = {}, onBrowser = {}, onSettings = {}, onOpen = {},
                )
            }
        }
        compose.onNodeWithText("关于 诺伊").assertIsDisplayed()
        compose.onAllNodesWithTag("update-dot", useUnmergedTree = true).assertCountEquals(0)
    }

    /** 设置首页「关于 Nuoyi」一行:有新版写「x 可更新」带红点。 */
    @Test
    fun settingsAboutRowShowsUpdate() {
        compose.setContent {
            NuoyiTheme {
                SettingsContent(
                    status = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true),
                    androidRelease = "14", mainModel = "DeepSeek", onBack = {}, onProblem = {}, onOpenModels = {},
                    versionName = "0.1.0", updateVersion = "0.2.0",
                )
            }
        }
        scrollTo("0.2.0 可更新")
        compose.onNodeWithText("0.2.0 可更新").assertIsDisplayed()
        compose.onNodeWithTag("update-dot", useUnmergedTree = true).assertExists()
    }
}
