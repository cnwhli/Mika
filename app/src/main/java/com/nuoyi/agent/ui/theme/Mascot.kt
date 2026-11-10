package com.nuoyi.agent.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 诺伊吉祥物的表情/动作状态。 */
enum class MascotMood { Idle, Listening, Thinking, Speaking, Happy }

private val HeadTop = Color(0xFFEAF6FF)
private val HeadBottom = Color(0xFFFFFFFF)
private val HeadStroke = Color(0xFFBFE0FF)
private val EarFill = Color(0xFFC9E6FF)
private val Eye = Color(0xFF20303A)
private val Glint = Color(0xFFFFFFFF)
private val Cheek = Color(0xFFFFD2DD)
private val AntennaStem = Color(0xFF6FC3FF)
private val AntennaOrb = Color(0xFF2A9DFF)

/**
 * 诺伊吉祥物:一个可爱的未来人。和启动图标用的矢量 [R.drawable.ic_mascot] 同造型,这里加了动作。
 * - [Idle] 轻轻上下呼吸、偶尔眨眼;
 * - [Listening] 天线灯呼吸、眼睛左右微动;
 * - [Thinking] 天线灯转圈、脑袋小幅点头;
 * - [Speaking] 嘴一张一合、脑袋轻晃;
 * - [Happy] 眼睛弯成 ^^、开心地蹦一下。
 * 颜色固定(不随深浅主题变),保证在哪都和图标是同一个形象。
 */
@Composable
fun NuoyiMascot(
    mood: MascotMood = MascotMood.Idle,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    val t = rememberInfiniteTransition("mascot")
    // 呼吸:整张图上下浮动
    val bob by t.animateFloat(0f, -1f, infiniteRepeatable(
        animation = keyframes { durationMillis = 1800; 0f at 0; -1f at 900; 0f at 1800 },
        repeatMode = RepeatMode.Restart,
    ))
    // 眨眼:大部分时间睁着,周期末快速闭一下
    val blink by t.animateFloat(1f, 1f, infiniteRepeatable(
        animation = keyframes {
            durationMillis = 4200
            1f at 0; 1f at 3700; 0.12f at 3820; 1f at 4000
        },
        repeatMode = RepeatMode.Restart,
    ))
    // 天线灯呼吸(0.4~1)
    val orb by t.animateFloat(0.45f, 1f, infiniteRepeatable(
        animation = keyframes { durationMillis = 1400; 0.45f at 0; 1f at 700; 0.45f at 1400 },
        repeatMode = RepeatMode.Restart,
    ))
    // 说话嘴型(0 闭 ~ 1 张)
    val mouth by t.animateFloat(0f, 1f, infiniteRepeatable(
        animation = keyframes {
            durationMillis = 360
            0f at 0; 1f at 90; 0.2f at 180; 0.9f at 270; 0f at 360
        },
        repeatMode = RepeatMode.Restart,
    ))
    // 开心蹦跳
    val hop by t.animateFloat(0f, -1f, infiniteRepeatable(
        animation = keyframes { durationMillis = 900; 0f at 0; -1f at 300; 0f at 600; 0f at 900 },
        repeatMode = RepeatMode.Restart,
    ))
    // 思考点头
    val nod by t.animateFloat(-0.04f, 0.04f, infiniteRepeatable(
        animation = keyframes { durationMillis = 1600; -0.04f at 0; 0.04f at 800; -0.04f at 1600 },
        repeatMode = RepeatMode.Restart,
    ))
    // 听/说时眼睛左右微动
    val look by t.animateFloat(-1f, 1f, infiniteRepeatable(
        animation = keyframes { durationMillis = 2200; -1f at 0; 1f at 1100; -1f at 2200 },
        repeatMode = RepeatMode.Restart,
    ))

    val moodBob = when (mood) {
        MascotMood.Happy -> hop
        MascotMood.Thinking -> nod
        else -> 0f
    }
    val eyeOpen = if (mood == MascotMood.Happy) 1f else blink
    val talk = if (mood == MascotMood.Speaking) mouth else 0f
    val orbGlow = if (mood == MascotMood.Listening || mood == MascotMood.Thinking) orb else 0.85f
    val eyeShift = if (mood == MascotMood.Listening || mood == MascotMood.Speaking) look * 1.4f else 0f

    Canvas(modifier.size(size)) {
        drawMascot(
            mood = mood,
            bobPx = (bob + moodBob) * size.toPx() * 0.04f,
            eyeOpen = eyeOpen,
            talk = talk,
            orbGlow = orbGlow,
            eyeShiftPx = eyeShift,
        )
    }
}

private fun DrawScope.drawMascot(
    mood: MascotMood,
    bobPx: Float,
    eyeOpen: Float,
    talk: Float,
    orbGlow: Float,
    eyeShiftPx: Float,
) {
    val w = size.width
    val cx = w / 2f
    // 头部圆心与半径(以宽度为单位,保持正方形比例)
    val headCx = cx
    val headCy = w * 0.56f + bobPx
    val headRx = w * 0.30f
    val headRy = w * 0.31f

    // 天线
    val stemTop = headCy - headRy - w * 0.02f
    val orbY = stemTop - w * 0.10f
    drawLine(
        color = AntennaStem,
        start = Offset(headCx, stemTop),
        end = Offset(headCx, orbY + w * 0.05f),
        strokeWidth = w * 0.035f,
        cap = StrokeCap.Round,
    )
    drawCircle(
        color = AntennaOrb,
        radius = w * 0.055f * (0.6f + orbGlow * 0.4f),
        center = Offset(headCx, orbY),
    )
    drawCircle(color = Glint, radius = w * 0.022f, center = Offset(headCx - w * 0.018f, orbY - w * 0.018f))

    // 两侧科技耳
    val earW = w * 0.10f
    val earH = w * 0.16f
    val earY = headCy - earH / 2
    drawRoundRect(
        color = EarFill,
        topLeft = Offset(headCx - headRx - earW * 0.5f, earY),
        size = Size(earW, earH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(earW * 0.4f),
    )
    drawRoundRect(
        color = EarFill,
        topLeft = Offset(headCx + headRx - earW * 0.5f, earY),
        size = Size(earW, earH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(earW * 0.4f),
    )

    // 头
    val headPath = Path().apply {
        // 用圆角路径近似:椭圆
        addOval(androidx.compose.ui.geometry.Rect(
            headCx - headRx, headCy - headRy, headCx + headRx, headCy + headRy))
    }
    drawPath(
        path = headPath,
        brush = Brush.verticalGradient(0f to HeadTop, 1f to HeadBottom, startY = headCy - headRy, endY = headCy + headRy),
    )
    drawPath(path = headPath, color = HeadStroke, style = Stroke(w * 0.02f))

    // 腮红
    drawCircle(color = Cheek, radius = w * 0.05f, center = Offset(headCx - headRx * 0.55f, headCy + headRy * 0.32f))
    drawCircle(color = Cheek, radius = w * 0.05f, center = Offset(headCx + headRx * 0.55f, headCy + headRy * 0.32f))

    // 眼睛
    val eyeRy = w * 0.085f * eyeOpen.coerceAtLeast(0.12f)
    val eyeRx = w * 0.062f
    val eyeY = headCy - headRy * 0.18f
    val eyeLx = headCx - headRx * 0.38f + eyeShiftPx
    val eyeRx2 = headCx + headRx * 0.38f + eyeShiftPx
    val eyeStroke = Stroke(w * 0.022f, cap = StrokeCap.Round)
    if (mood == MascotMood.Happy) {
        // ^^ 弯眼:上凸的弧
        val ew = eyeRx * 2
        val eh = eyeRy * 2
        drawArc(color = Eye, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(eyeLx - eyeRx, eyeY - eyeRy), size = Size(ew, eh), style = eyeStroke)
        drawArc(color = Eye, startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(eyeRx2 - eyeRx, eyeY - eyeRy), size = Size(ew, eh), style = eyeStroke)
    } else {
    drawOval(color = Eye, topLeft = Offset(eyeLx - eyeRx, eyeY - eyeRy), size = Size(eyeRx * 2, eyeRy * 2))
    drawOval(color = Eye, topLeft = Offset(eyeRx2 - eyeRx, eyeY - eyeRy), size = Size(eyeRx * 2, eyeRy * 2))
    drawCircle(color = Glint, radius = w * 0.02f, center = Offset(eyeLx + eyeRx * 0.3f, eyeY - eyeRy * 0.4f))
    drawCircle(color = Glint, radius = w * 0.02f, center = Offset(eyeRx2 + eyeRx * 0.3f, eyeY - eyeRy * 0.4f))
    }

    // 嘴
    val mouthY = headCy + headRy * 0.42f
    val mouthW = w * 0.12f
    if (talk > 0.05f) {
        drawOval(
            color = Eye,
            topLeft = Offset(headCx - mouthW * 0.5f, mouthY - w * 0.02f - talk * w * 0.03f),
            size = Size(mouthW, w * 0.025f + talk * w * 0.06f),
        )
    } else {
        val smile = Path().apply {
            moveTo(headCx - mouthW, mouthY)
            quadraticBezierTo(headCx, mouthY + w * 0.07f, headCx + mouthW, mouthY)
        }
        drawPath(smile, color = Eye, style = Stroke(w * 0.022f, cap = StrokeCap.Round))
    }
}
