package com.nuoyi.agent.about

import com.nuoyi.agent.R
import com.nuoyi.agent.i18n.tr
import java.net.URLEncoder

/**
 * Nuoyi 对外的地址都在这里:仓库、问题、检查更新用的两个来源。换地址只改这一处。
 *
 * 注意：[REPO] / [RELEASES] / [NEW_ISSUE] / [LATEST_RELEASE_API] 目前仍指向**上游**
 * 仓库，按 Apache-2.0 作为来源署名保留。对外分发前请把发布相关的三个换成自有渠道，
 * 再把 [UPDATES_CHANNEL_READY] 改 true；在那之前 App 不会自动去上游查更新，
 * 更不会下载安装上游的 APK（否则会用上游的签名/版本覆盖用户手上的诺伊）。
 */
object NuoyiLinks {
    /** 自有发布渠道接好之前保持 false，见类注释。测试注入自定义 releaseApi 不受它影响。 */
    const val UPDATES_CHANNEL_READY = false

    const val REPO = "https://github.com/wangzan101/Mika"
    const val REPO_LABEL = "github.com/wangzan101/Mika"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"
    /** 检查更新只问 GitHub 发布页(不登录,每小时有次数限制)。 */
    const val LATEST_RELEASE_API = "https://api.github.com/repos/wangzan101/Mika/releases/latest"

    /** 反馈时预先填进问题模板的设备与版本信息。 */
    data class Device(
        val manufacturer: String,
        val model: String,
        val androidRelease: String,
        val sdk: Int,
        val versionName: String,
        val build: String,
    )

    /** 预先填好的问题正文:机型、安卓版本、Nuoyi 版本三行,下面留空几段给用户写。 */
    fun issueBody(d: Device): String {
        val model = if (d.model.startsWith(d.manufacturer, ignoreCase = true)) d.model else "${d.manufacturer} ${d.model}"
        return buildString {
            append(tr(R.string.issue_body, model.trim(), d.androidRelease, d.sdk, d.versionName, d.build))
        }
    }

    /** 新建问题的地址,标题留空、正文预先填好;参数按 URL 编码(空格写成 %20)。 */
    fun issueUrl(d: Device): String = "$NEW_ISSUE?title=&body=${encode(issueBody(d))}"

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
