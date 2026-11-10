package com.wangzan101.mika.core.voice

/**
 * 对外分发时的告知与授权状态。
 *
 * GUI Agent 和「按住说话」不一样：它会把整屏截图送到模型服务商，语音又是常驻入口。
 * 这类数据一旦公开给外部用户，必须在第一次开麦之前把话讲清楚，并且**运行时也卡住**，
 * 不能只在设置页贴一段文字就算数。
 *
 * 另一件必须写死的事：免费额度只用服务商**官方公开**的 free tier，或者用户自己填的 Key。
 * 不接、不借、不逆向任何客户端（IDE 插件、编辑器、订阅制助手）赠送的额度，
 * 也不做批量注册、共享 Key。这不是道德洁癖，是公开分发能不能活下去的前提。
 */

data class VoiceConsent(
    /** 用户读过并点了「知道了」。 */
    val acknowledged: Boolean = false,
    /** 麦克风权限与用途已授权。 */
    val microphone: Boolean = false,
    /** 知道任务过程中会截取整屏发给模型服务商。 */
    val screenCapture: Boolean = false,
    /** 知道部分免费档会把请求用于训练，且已自行选择是否接受。 */
    val freeTierTrainingUnderstood: Boolean = false,
    /** 确认用的是自己的 Key／官方免费档，而不是别人的客户端额度。 */
    val ownCredentialsConfirmed: Boolean = false,
) {
    companion object {
        /** 仅供预览／测试，真实启动不得用它。 */
        fun granted() = VoiceConsent(
            acknowledged = true,
            microphone = true,
            screenCapture = true,
            freeTierTrainingUnderstood = true,
            ownCredentialsConfirmed = true,
        )
    }
}

data class DisclosureItem(val key: String, val title: String, val detail: String, val required: Boolean)

object VoiceDisclosure {
    const val QUOTA_POLICY =
        "免费额度仅来自服务商官方公开的免费档，或你自己填写的 API Key。本产品不借用、不转发、不逆向任何第三方客户端赠送的额度，也不提供共享 Key 或批量注册。"

    const val TRAINING_POLICY =
        "部分免费档默认会把请求用于模型训练。开启前请自行确认该服务商的数据政策；涉及隐私屏幕时请改用不留存数据的档位或你自己的付费 Key。"

    const val SCREEN_POLICY =
        "任务执行过程中会截取当前屏幕并发送给所选模型服务商，用于判断下一步点哪里。支付、密码、聊天内容等敏感界面请不要交给它处理。"

    const val MIC_POLICY =
        "语音入口会打开麦克风并把识别到的文字（或音频，取决于你选的识别服务）交给识别服务商；公版只在被系统助手唤起时听，个人版可配置为常驻热词。"

    fun checklist(): List<DisclosureItem> = listOf(
        DisclosureItem("mic", "麦克风", MIC_POLICY, required = true),
        DisclosureItem("screen", "整屏截图", SCREEN_POLICY, required = true),
        DisclosureItem("training", "免费档的数据使用", TRAINING_POLICY, required = true),
        DisclosureItem("quota", "免费额度来源", QUOTA_POLICY, required = true),
    )

    /** 开麦之前最后一道闸：少任何一项都不能启动语音。 */
    fun canUseVoice(c: VoiceConsent): Boolean = c.acknowledged && c.microphone &&
        c.screenCapture && c.freeTierTrainingUnderstood && c.ownCredentialsConfirmed

    /** 拒绝时播给用户听的话；不解释太多，只说去哪儿补齐。 */
    const val MISSING_CONSENT = "请先在设置里看完隐私说明并授权麦克风，才能使用语音。"

    /** 免费档训练开关关闭时，是否还允许把整屏截图送出去。由宿主据此禁用训练型免费档。 */
    fun allowsTrainingFreeTier(consent: VoiceConsent, freeTierTrainsByDefault: Boolean): Boolean =
        !freeTierTrainsByDefault || consent.freeTierTrainingUnderstood
}
