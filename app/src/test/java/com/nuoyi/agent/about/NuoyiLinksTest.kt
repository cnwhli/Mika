package com.nuoyi.agent.about

import org.junit.Rule
import com.nuoyi.agent.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class NuoyiLinksTest {
    @get:Rule val texts = ChineseTexts()

    private val device = NuoyiLinks.Device("Google", "Pixel 8", "14", 34, "0.1.0", "65fcf92")

    @Test
    fun bodyHasThreePrefilledLines() {
        val lines = NuoyiLinks.issueBody(device).lines()
        assertEquals("- 机型：Google Pixel 8", lines[0])
        assertEquals("- 安卓版本：14（SDK 34）", lines[1])
        assertEquals("- 诺伊 版本：0.1.0（构建 65fcf92）", lines[2])
        assertTrue(lines.contains("### 遇到了什么问题"))
        assertTrue(lines.contains("### 做了哪几步"))
        assertTrue(lines.contains("### 期望是什么样"))
        // 诊断信息不自动带上:只提示用户去哪里复制
        assertTrue(lines.contains("### 诊断信息"))
        assertTrue(lines.contains("（可选）在 诺伊「关于 › 反馈问题 › 复制诊断信息」复制后粘贴在这里"))
    }

    /** 型号里已经带厂商名时不重复写。 */
    @Test
    fun modelNotDoubled() {
        val body = NuoyiLinks.issueBody(device.copy(manufacturer = "samsung", model = "SAMSUNG SM-S9180"))
        assertTrue(body.startsWith("- 机型：SAMSUNG SM-S9180\n"))
    }

    @Test
    fun issueUrlIsEncoded() {
        val url = NuoyiLinks.issueUrl(device)
        assertTrue(url.startsWith("${NuoyiLinks.NEW_ISSUE}?title=&body="))
        assertTrue(url.startsWith("https://github.com/cnwhli/nuoyi/issues/new?"))
        val query = url.substringAfter('?')
        assertFalse("no raw spaces or newlines", query.any { it == ' ' || it == '\n' || it == '+' })
        assertFalse("no raw non-ASCII", query.any { it.code > 127 })
        // 能被当成合法 URI 解析,正文解码回原样
        URI(url)
        val body = URLDecoder.decode(query.substringAfter("body="), "UTF-8")
        assertEquals(NuoyiLinks.issueBody(device), body)
    }

    /** 公版与个人版的清单分开:两个版本 applicationId 不同,不能互相覆盖安装。 */
    @Test
    fun eachEditionHasItsOwnManifestAndMirror() {
        assertEquals("https://noy.127068.xyz/api/latest.json", NuoyiLinks.latestReleaseApi("public"))
        assertEquals("https://noy.127068.xyz/api/latest-personal.json", NuoyiLinks.latestReleaseApi("personal"))
        assertTrue(NuoyiLinks.latestReleaseMirror("public").endsWith("/site/api/latest.json"))
        assertTrue(NuoyiLinks.latestReleaseMirror("personal").endsWith("/site/api/latest-personal.json"))
    }

    /** 上游只作 Apache-2.0 署名,发布与更新绝不再指向它。 */
    @Test
    fun upstreamIsAttributionOnly() {
        assertEquals("https://github.com/wangzan101/Mika", NuoyiLinks.UPSTREAM_REPO)
        for (url in listOf(NuoyiLinks.SITE, NuoyiLinks.REPO, NuoyiLinks.RELEASES, NuoyiLinks.NEW_ISSUE,
            NuoyiLinks.LATEST_RELEASE_API, NuoyiLinks.PERSONAL_LATEST_RELEASE_API)) {
            assertFalse(url.contains("wangzan101"))
        }
        assertEquals("https://noy.127068.xyz", NuoyiLinks.SITE)
        assertEquals("https://github.com/cnwhli/nuoyi/releases", NuoyiLinks.RELEASES)
    }
}
