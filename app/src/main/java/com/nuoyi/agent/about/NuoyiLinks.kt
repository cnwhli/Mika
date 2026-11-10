package com.nuoyi.agent.about

import com.nuoyi.agent.BuildConfig
import com.nuoyi.agent.R
import com.nuoyi.agent.i18n.tr
import java.net.URLEncoder

/**
 * 诺伊对外的地址都在这里,换地址只改这一处。
 *
 * 发布渠道:官网 [SITE] 提供版本清单(api/latest*.json)与下载页,安装包托管在自有
 * 仓库 [REPO] 的 Releases。版本清单按发行版本分开,避免个人版被公版 APK 覆盖
 * (两者 applicationId 不同)。清单在官网不可用时可从 [LATEST_RELEASE_MIRROR] 读取同一份内容。
 *
 * 来源署名:本项目基于 wangzan101/Mika(Apache-2.0)二次开发,见 LICENSE / NOTICE 与
 * [UPSTREAM_REPO]。[UPSTREAM_REPO] 只作署名,绝不用于检查更新或下载安装包。
 */
object NuoyiLinks {
    /** 自有渠道接好之前保持 false;关掉后 App 不再访问官网清单(测试注入的地址不受影响)。 */
    const val UPDATES_CHANNEL_READY = true

    /** 对外发布渠道:官网。 */
    const val SITE = "https://noy.127068.xyz"
    const val DOWNLOAD_PAGE = "$SITE/download/"

    /** 源码与安装包托管仓库。 */
    const val REPO = "https://github.com/cnwhli/nuoyi"
    const val REPO_LABEL = "github.com/cnwhli/nuoyi"
    const val README = "$REPO#readme"
    const val RELEASES = "$REPO/releases"
    const val NEW_ISSUE = "$REPO/issues/new"

    /** Apache-2.0 上游来源,仅作署名。 */
    const val UPSTREAM_REPO = "https://github.com/wangzan101/Mika"

    /** 官网版本清单:GitHub releases/latest 的字段格式,由 site/api/ 下的静态文件提供。 */
    const val LATEST_RELEASE_API = "$SITE/api/latest.json"
    const val PERSONAL_LATEST_RELEASE_API = "$SITE/api/latest-personal.json"
    /** 官网暂时不可达时的同内容镜像;走 raw 文件,不受 GitHub API 限流。 */
    const val LATEST_RELEASE_MIRROR = "https://raw.githubusercontent.com/cnwhli/nuoyi/main/site/api/latest.json"
    const val PERSONAL_LATEST_RELEASE_MIRROR = "https://raw.githubusercontent.com/cnwhli/nuoyi/main/site/api/latest-personal.json"

    /** 依当前发行版本挑清单:个人版只收 personal 包,公版只收 public 包。 */
    fun latestReleaseApi(edition: String = BuildConfig.VOICE_EDITION): String =
        if (edition == "personal") PERSONAL_LATEST_RELEASE_API else LATEST_RELEASE_API

    fun latestReleaseMirror(edition: String = BuildConfig.VOICE_EDITION): String =
        if (edition == "personal") PERSONAL_LATEST_RELEASE_MIRROR else LATEST_RELEASE_MIRROR

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
