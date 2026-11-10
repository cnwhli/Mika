package com.nuoyi.agent.voice

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.nuoyi.agent.R

/**
 * 系统语音会话窗口里那张「身份固定应答」卡片。
 *
 * 语音链路在 core 里直接短路到 TTS（不进模型、不联网、零用量），但「免手」不等于
 * 「屏幕上一片空白」：宿主收到固定回答时把用户原句、那句常量回答和本地标记一起摆出来。
 * 这里用平台 View 而不是 Compose，因为 VoiceInteractionSession 的内容视图是普通 View 树，
 * 且会话窗口用的是系统主题，颜色写死避免依赖 App 的 Material 主题。
 *
 * 回答文本本身不在这里写死：由宿主把 [com.nuoyi.agent.core.voice.ProductIdentity.REPLY] 传进来。
 * 外层保持透明，卡片只占顶部一小块，不遮挡用户当前正在看的应用。
 */
class VoiceIdentityCard(context: Context) {

    private val dp: (Int) -> Int = {
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, it.toFloat(), context.resources.displayMetrics).toInt()
    }

    private val question = TextView(context).apply {
        setTextColor(Color.parseColor("#96A3B8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        visibility = View.GONE
    }
    private val answer = TextView(context).apply {
        setTextColor(Color.parseColor("#EAF0FA"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        setTypeface(Typeface.DEFAULT_BOLD)
        visibility = View.GONE
    }
    private val badge = TextView(context).apply {
        text = context.getString(R.string.about_identity_badge)
        setTextColor(Color.parseColor("#6EE7C8"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        visibility = View.GONE
    }

    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(20), dp(16), dp(16))
        // 外层透明：即使系统把内容视图铺满窗口，也不会给屏幕盖一层黑底。
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#111A2C"))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.parseColor("#3A4A68"))
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(TextView(context).apply {
                text = context.getString(R.string.about_identity_title)
                setTextColor(Color.parseColor("#9B8CFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTypeface(Typeface.DEFAULT_BOLD)
            })
            addView(question, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(answer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            })
            addView(badge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /** 固定回答出现时把问答一起显示出来；问句用用户原话，不剥唤醒词。 */
    fun show(questionText: String, answerText: String) {
        question.text = "› $questionText"
        answer.text = answerText
        question.visibility = View.VISIBLE
        answer.visibility = View.VISIBLE
        badge.visibility = View.VISIBLE
    }
}
