package com.nuoyi.agent.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.R
import com.nuoyi.agent.settings.MascotStore
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** 诺伊吉祥物的状态:直接对应 App 此刻在做什么。 */
enum class MascotMood { Idle, Listening, Thinking, Speaking, Happy }

private val Skin = Color(0xFFFFE2D2)
private val Sleeve = Color(0xFFFBE1E7)
private val Outline = Color(0xFF2F3038)
private val BrandBlue = Color(0xFF2A9DFF)
private val PaperWhite = Color(0xFFFFFFFF)
private val DeskWood = Color(0xFFF3E7D6)
private val MouthPink = Color(0xFFA6605E)

/**
 * 空闲时随机轮播的小动作。
 * HeadNod / HeadTilt / HeadShake / LookUp 是只作用在头部的动作:
 * 脖子以上按权重偏移、脖子以下保持不动,所以点头、歪头、摇头、抬头时身体不会跟着晃。
 */
private enum class IdleMove {
    Breathe, HeadNod, HeadTilt, HeadShake, LookUp,
    HeadScratch, TurnAround, Stretch, Wave, Blink,
}

/** 叠在插画上的手臂/手势。 */
private enum class ArmPose { None, Write, Listen, Gesture, Cheer, HeadScratch, Stretch, Wave }

private data class IdleMotion(val move: IdleMove, val progress: Float)

/** 头部某一帧的形变量:位移 + 转头时横向压窄。 */
private data class HeadMotion(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val squeeze: Float = 0f,
)

/**
 * 读出用户上传的自定义吉祥物位图(设置 › 吉祥物里上传的)。没上传或读不出返回 null,
 * 调用方照常画内置形象。位图会随设置变化实时刷新。
 */
@Composable
fun rememberMascotBitmap(): ImageBitmap? {
    val ctx = LocalContext.current
    val store = remember(ctx) { MascotStore.get(ctx) }
    val path by store.path.collectAsState()
    return remember(path) {
        if (store.useCustom && path != null) store.loadBitmap()?.asImageBitmap() else null
    }
}

/**
 * 内置吉祥物:以小女孩的发型、神态与粉色睡衣风格化绘制的 Q 版插画(透明背景 PNG)。
 * 随包发布、不依赖外部文件;卡通化形象,不含任何真人照片。
 */
@Composable
private fun rememberDefaultMascotBitmap(): ImageBitmap {
    val ctx = LocalContext.current
    return remember(ctx) {
        // 解码内置图也不能把 App 带崩:极少数 ROM / 低内存时 decodeResource 会抛异常或 OOM,
        // 兜不住就退回 1×1 占位(条带绘制对任意尺寸都安全),其余界面照常显示。
        runCatching {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.nuoyi_girl)?.asImageBitmap()
        }.getOrNull() ?: ImageBitmap(1, 1)
    }
}

/** 每隔一两秒随机换一个空闲小动作,并给出这一次动作 0→1 的进度。 */
@Composable
private fun rememberIdleMotion(): IdleMotion {
    var move by remember { mutableStateOf(IdleMove.Breathe) }
    var seq by remember { mutableStateOf(0) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextInt(1500, 2900).toLong())
            val all = IdleMove.values()
            move = all[Random.nextInt(all.size)]
            seq++
        }
    }
    LaunchedEffect(seq) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(durationMillis = 1200, easing = FastOutSlowInEasing))
    }
    val p by progress.asState()
    return IdleMotion(move, p)
}

/**
 * 头部权重:插画纵向比例 0.60 以上(头顶、眼睛、耳朵、脸)为 1,到 0.84(肩/领口)平滑降到 0。
 * 用平滑曲线而不是硬切,条带之间位移差很小,不会出现断层。
 */
private fun headWeight(yr: Float): Float {
    val t = ((0.84f - yr) / 0.24f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 把整张插画按水平条带绘制,每条带按它所在的高度施加头部形变。
 * 好处:不拆图、不裁剪,所以没有接缝、白边或双影;自定义上传的任意图片也能做头部动作。
 * 每条带的目标高度由变换后的上下边界取整相减得到,相邻带严格首尾相接,避免点头/抬头时露细缝。
 */
private fun DrawScope.drawMascotBands(image: ImageBitmap, s: Float, head: HeadMotion) {
    val bands = if (s < 110f) 24 else 48
    val iw = image.width
    val ih = image.height
    for (i in 0 until bands) {
        val y0 = (ih.toLong() * i / bands).toInt()
        val y1 = (ih.toLong() * (i + 1) / bands).toInt()
        val band = y1 - y0
        if (band <= 0) continue
        val w = headWeight((y0 + band / 2f) / ih)
        val squeeze = 1f - head.squeeze * w
        val dw = s * squeeze
        val dx = head.dx * w
        // y 方向用「变换后的上下边界取整再相减」求高度:相邻条带首尾严格相接,
        // 点头/抬头时不会露出 1px 细缝,也不会互相重叠出横向条纹。
        val yTop = (s * y0.toFloat() / ih + head.dy * headWeight(y0.toFloat() / ih)).roundToInt()
        val yBottom = (s * y1.toFloat() / ih + head.dy * headWeight(y1.toFloat() / ih)).roundToInt()
        drawImage(
            image = image,
            srcOffset = IntOffset(0, y0),
            srcSize = IntSize(iw, band),
            dstOffset = IntOffset((s * 0.5f - dw * 0.5f + dx).roundToInt(), yTop),
            dstSize = IntSize(
                dw.roundToInt().coerceAtLeast(1),
                (yBottom - yTop).coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.High,
        )
    }
}

/**
 * 诺伊吉祥物(动画版):身体动作与头部动作各自独立。
 * - [MascotMood.Thinking] 后台有任务在跑 → 低头写作业,桌面/纸/铅笔/笔画增长,头微垂并轻轻点头;
 * - [MascotMood.Idle] 空闲 → 随机轮播点头、歪头、摇头、抬头张望、摸头、转身、伸懒腰、招手、眨眼、呼吸;
 * - [MascotMood.Listening] 侧头倾听 + 手放耳边 + 声波; [MascotMood.Speaking] 边说边点头 + 手势;
 * - [MascotMood.Happy] 蹦跳 + 双手举起 + 脑袋轻晃 + 星星闪。
 *
 * 用户上传自定义图片时仍保留整体浮动/倾斜/转身与头部动作,但不叠加手臂和道具,以免和别的形象打架。
 */
@Composable
fun NuoyiMascot(
    mood: MascotMood = MascotMood.Idle,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    customBitmap: ImageBitmap? = null,
) {
    val defaultBitmap = rememberDefaultMascotBitmap()
    val bitmap = customBitmap ?: defaultBitmap
    val builtIn = customBitmap == null
    val idle = rememberIdleMotion()
    val t = rememberInfiniteTransition("mascot")
    val cycle by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(animation = tween(1600, easing = LinearEasing), repeatMode = RepeatMode.Restart),
    )
    val fast by t.animateFloat(
        0f, 1f,
        infiniteRepeatable(animation = tween(620, easing = LinearEasing), repeatMode = RepeatMode.Restart),
    )

    Canvas(modifier.size(size)) {
        val s = size.toPx()
        val p = idle.progress
        val amp = sin(p * PI).toFloat()                 // 0 → 1 → 0,一次小动作的幅度
        val breathe = sin(cycle * 2f * PI).toFloat()    // -1 → 1
        var yShift = breathe * s * 0.012f
        var tilt = 0f
        var scaleX = 1f
        var scaleY = 1f
        var head = HeadMotion()
        var arm = ArmPose.None
        var desk = false
        var sound = false
        var mouth = false
        var sparkle = false
        var blink = false

        when (mood) {
            MascotMood.Thinking -> {
                // 后台任务:伏案写作业,头微垂并随笔画轻轻点动
                desk = true
                arm = ArmPose.Write
                tilt = -3f + breathe * 1.5f
                yShift += s * 0.015f
                head = HeadMotion(
                    dx = -s * 0.006f,
                    dy = s * 0.022f + sin(fast * 2f * PI).toFloat() * s * 0.005f,
                    squeeze = 0.02f,
                )
            }
            MascotMood.Listening -> {
                // 侧过头把耳朵凑近声源
                arm = ArmPose.Listen
                sound = true
                tilt = 2f + breathe * 1.5f
                head = HeadMotion(dx = s * 0.024f, dy = -s * 0.004f, squeeze = 0.035f)
            }
            MascotMood.Speaking -> {
                // 说话时连续点头
                arm = ArmPose.Gesture
                mouth = true
                tilt = breathe * 2f
                head = HeadMotion(
                    dx = sin(cycle * 2f * PI).toFloat() * s * 0.008f,
                    dy = abs(sin(fast * 2f * PI).toFloat()) * s * 0.018f,
                )
            }
            MascotMood.Happy -> {
                // 蹦跳 + 脑袋左右轻晃
                arm = ArmPose.Cheer
                sparkle = true
                yShift -= abs(sin(cycle * 2f * PI).toFloat()) * s * 0.06f
                tilt = sin(cycle * 4f * PI).toFloat() * 5f
                head = HeadMotion(
                    dx = sin(cycle * 4f * PI).toFloat() * s * 0.018f,
                    dy = -s * 0.010f,
                )
            }
            MascotMood.Idle -> when (idle.move) {
                IdleMove.Breathe -> {
                    scaleY = 1f + amp * 0.02f
                    head = HeadMotion(dy = breathe * s * 0.004f)
                }
                IdleMove.HeadNod -> head = HeadMotion(
                    dy = sin(p * 3f * PI).toFloat() * s * 0.030f * amp,
                    squeeze = 0.015f * amp,
                )
                IdleMove.HeadTilt -> head = HeadMotion(
                    dx = s * 0.055f * amp,
                    dy = s * 0.006f * amp,
                    squeeze = 0.02f * amp,
                )
                IdleMove.HeadShake -> {
                    val swing = sin(p * 4f * PI).toFloat()
                    head = HeadMotion(
                        dx = swing * s * 0.042f * amp,
                        squeeze = abs(swing) * 0.065f * amp,
                    )
                }
                IdleMove.LookUp -> head = HeadMotion(
                    dx = s * 0.016f * amp,
                    dy = -s * 0.032f * amp,
                )
                IdleMove.HeadScratch -> {
                    arm = ArmPose.HeadScratch
                    tilt = -2f * amp
                    head = HeadMotion(dx = -s * 0.020f * amp, dy = -s * 0.004f * amp)
                }
                IdleMove.TurnAround -> {
                    scaleX = 1f - 0.7f * amp
                    tilt = sin(p * PI).toFloat() * 8f
                    head = HeadMotion(dx = sin(p * 2f * PI).toFloat() * s * 0.012f)
                }
                IdleMove.Stretch -> {
                    scaleY = 1f + 0.08f * amp
                    yShift -= amp * s * 0.03f
                    arm = ArmPose.Stretch
                    mouth = true
                    head = HeadMotion(dy = -s * 0.018f * amp)
                }
                IdleMove.Wave -> {
                    arm = ArmPose.Wave
                    tilt = 4f * amp
                    head = HeadMotion(dx = s * 0.014f * amp)
                }
                IdleMove.Blink -> {
                    blink = amp > 0.28f
                    head = HeadMotion(dy = s * 0.005f * amp)
                }
            }
        }

        val pivot = Offset(s / 2f, s * 0.82f)
        // 眨眼睛、小嘴、声波要跟着头一起动,按所在高度取权重
        val faceW = headWeight(0.56f)
        val mouthW = headWeight(0.70f)
        val earW = headWeight(0.45f)
        translate(0f, yShift) {
            rotate(tilt, pivot) {
                scale(scaleX, scaleY, pivot) {
                    drawMascotBands(bitmap, s, head)
                    if (builtIn) {
                        translate(head.dx * faceW, head.dy * faceW) {
                            if (blink) drawBlink(s)
                        }
                        translate(head.dx * mouthW, head.dy * mouthW) {
                            if (mouth) drawTalkingMouth(s, fast)
                        }
                        translate(head.dx * earW, head.dy * earW) {
                            if (sound) drawSoundWaves(s, cycle)
                        }
                    }
                }
                if (builtIn) {
                    if (desk) drawWritingDesk(s, fast)
                    if (sparkle) drawSparkles(s, cycle)
                    if (arm != ArmPose.None) drawArmPose(arm, s, fast, amp, head.dx)
                }
            }
        }
    }
}

/** 一条卡通手臂:肩 → 肘 → 手,末端是圆圆的肤色小手。 */
private fun DrawScope.drawLimb(shoulder: Offset, elbow: Offset, hand: Offset, s: Float) {
    val stroke = s * 0.085f
    val handR = s * 0.052f
    drawLine(color = Sleeve, start = shoulder, end = elbow, strokeWidth = stroke, cap = StrokeCap.Round)
    drawLine(color = Sleeve, start = elbow, end = hand, strokeWidth = stroke, cap = StrokeCap.Round)
    drawCircle(color = Outline, radius = handR * 1.12f, center = hand)
    drawCircle(color = Skin, radius = handR, center = hand)
}

private fun DrawScope.drawArmPose(pose: ArmPose, s: Float, phase: Float, amp: Float, headDx: Float) {
    val shoulderR = Offset(s * 0.73f, s * 0.86f)
    val shoulderL = Offset(s * 0.27f, s * 0.86f)
    val wobble = sin(phase * 6f * PI).toFloat()
    when (pose) {
        ArmPose.HeadScratch -> {
            // 摸头的手跟着脑袋一起偏,不然会摸空
            val hand = Offset(s * (0.40f + wobble * 0.025f * amp) + headDx * headWeight(0.18f), s * 0.18f)
            drawLimb(shoulderR, Offset(s * 0.82f, s * 0.54f), hand, s)
        }
        ArmPose.Wave -> {
            val hand = Offset(s * (0.87f + wobble * 0.05f), s * 0.42f)
            drawLimb(shoulderR, Offset(s * 0.86f, s * 0.66f), hand, s)
        }
        ArmPose.Listen -> {
            drawLimb(shoulderR, Offset(s * 0.85f, s * 0.68f), Offset(s * 0.82f + headDx * 0.4f, s * 0.46f), s)
        }
        ArmPose.Gesture -> {
            val hand = Offset(s * (0.78f + wobble * 0.025f), s * 0.74f)
            drawLimb(shoulderR, Offset(s * 0.83f, s * 0.81f), hand, s)
        }
        ArmPose.Cheer -> {
            drawLimb(shoulderL, Offset(s * 0.20f, s * 0.60f), Offset(s * 0.18f, s * 0.34f), s)
            drawLimb(shoulderR, Offset(s * 0.80f, s * 0.60f), Offset(s * 0.82f, s * 0.34f), s)
        }
        ArmPose.Stretch -> {
            drawLimb(shoulderL, Offset(s * 0.22f, s * 0.58f), Offset(s * 0.30f, s * 0.27f), s)
            drawLimb(shoulderR, Offset(s * 0.78f, s * 0.58f), Offset(s * 0.70f, s * 0.27f), s)
        }
        ArmPose.Write -> {
            val hand = Offset(s * (0.62f + wobble * 0.012f), s * (0.90f + wobble * 0.012f))
            drawLimb(shoulderR, Offset(s * 0.81f, s * 0.80f), hand, s)
            // 握着的铅笔
            drawLine(
                color = BrandBlue,
                start = hand,
                end = Offset(hand.x - s * 0.07f, hand.y - s * 0.09f),
                strokeWidth = s * 0.02f,
                cap = StrokeCap.Round,
            )
            drawCircle(color = Outline, radius = s * 0.012f, center = Offset(hand.x - s * 0.07f, hand.y - s * 0.09f))
        }
        ArmPose.None -> Unit
    }
}

/** 写作业时的桌面、纸、横线与一笔笔长出来的字迹。 */
private fun DrawScope.drawWritingDesk(s: Float, phase: Float) {
    val top = s * 0.90f
    drawRoundRect(
        color = DeskWood,
        topLeft = Offset(s * 0.04f, top),
        size = Size(s * 0.92f, s * 0.13f),
        cornerRadius = CornerRadius(s * 0.025f),
    )
    drawRoundRect(
        color = PaperWhite,
        topLeft = Offset(s * 0.26f, top - s * 0.012f),
        size = Size(s * 0.48f, s * 0.12f),
        cornerRadius = CornerRadius(s * 0.012f),
    )
    val lineColor = Color(0xFFD9E3EC)
    for (i in 0..2) {
        val y = top + s * (0.022f + i * 0.032f)
        drawLine(color = lineColor, start = Offset(s * 0.29f, y), end = Offset(s * 0.71f, y), strokeWidth = s * 0.006f)
    }
    val strokes = (phase * 9f).roundToInt()
    for (i in 0 until strokes) {
        val x = s * (0.30f + i * 0.044f)
        drawLine(
            color = Outline.copy(alpha = 0.55f),
            start = Offset(x, top + s * 0.038f),
            end = Offset(x + s * 0.024f, top + s * 0.018f),
            strokeWidth = s * 0.009f,
            cap = StrokeCap.Round,
        )
    }
}

/** 聆听时耳机旁一圈圈扩散的声波。 */
private fun DrawScope.drawSoundWaves(s: Float, phase: Float) {
    for (i in 0..2) {
        val r = s * (0.07f + i * 0.035f + phase * 0.02f)
        drawArc(
            color = BrandBlue.copy(alpha = 0.8f - i * 0.2f),
            startAngle = -55f,
            sweepAngle = 110f,
            useCenter = false,
            topLeft = Offset(s * 0.88f - r / 2f, s * 0.45f - r / 2f),
            size = Size(r, r),
            style = Stroke(s * 0.012f),
        )
    }
}

private fun DrawScope.drawSparkles(s: Float, phase: Float) {
    val a = 0.35f + 0.65f * abs(sin(phase * 4f * PI).toFloat())
    drawCircle(color = BrandBlue.copy(alpha = a), radius = s * 0.018f, center = Offset(s * 0.18f, s * 0.22f))
    drawCircle(color = BrandBlue.copy(alpha = a), radius = s * 0.013f, center = Offset(s * 0.83f, s * 0.30f))
    drawCircle(color = BrandBlue.copy(alpha = a), radius = s * 0.015f, center = Offset(s * 0.72f, s * 0.12f))
}

/** 说话/打哈欠时张合的小嘴。 */
private fun DrawScope.drawTalkingMouth(s: Float, phase: Float) {
    val h = s * (0.010f + 0.020f * abs(sin(phase * 6f * PI).toFloat()))
    drawOval(color = MouthPink, topLeft = Offset(s * 0.475f, s * 0.694f), size = Size(s * 0.05f, h))
}

/** 闭眼:用肤色盖住原来的大眼睛,再画两道上弯的弧。 */
private fun DrawScope.drawBlink(s: Float) {
    drawOval(color = Skin, topLeft = Offset(s * 0.345f, s * 0.515f), size = Size(s * 0.11f, s * 0.115f))
    drawOval(color = Skin, topLeft = Offset(s * 0.545f, s * 0.515f), size = Size(s * 0.11f, s * 0.115f))
    val style = Stroke(s * 0.013f, cap = StrokeCap.Round)
    drawArc(
        color = Outline, startAngle = 195f, sweepAngle = 150f, useCenter = false,
        topLeft = Offset(s * 0.355f, s * 0.545f), size = Size(s * 0.09f, s * 0.05f), style = style,
    )
    drawArc(
        color = Outline, startAngle = 195f, sweepAngle = 150f, useCenter = false,
        topLeft = Offset(s * 0.555f, s * 0.545f), size = Size(s * 0.09f, s * 0.05f), style = style,
    )
}
