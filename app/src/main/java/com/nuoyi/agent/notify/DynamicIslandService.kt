package com.nuoyi.agent.notify

import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.R
import com.nuoyi.agent.callnotes.CallSessionStore
import com.nuoyi.agent.callnotes.CallState
import com.nuoyi.agent.callnotes.CallStateFlow
import com.nuoyi.agent.callnotes.CallStateMonitor
import com.nuoyi.agent.settings.FeatureFlags
import com.nuoyi.agent.ui.MainActivity
import com.nuoyi.agent.ui.CallLiveActivity
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiIcons
import com.nuoyi.agent.ui.theme.NuoyiMascot
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 系统级灵动岛:把 [NotificationCenter.active] 的通知、以及实时通话状态,显示在屏幕顶部居中的胶囊里(像 iOS 灵动岛),
 * 点一下回 App,长按划掉。它本身不依赖系统通知栏权限;若没有「显示在其他应用上层」权限就不显示。
 * 用前台服务保活(常驻低优先通知只用来保活,胶囊显示不靠它);被系统清掉时 [onTaskRemoved] 会再把自己拉起来。
 */
class DynamicIslandService : Service() {
    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    /** 胶囊窗口当前的布局参数;拖动时实时改 x/y 并写回,用于保存/恢复位置。 */
    private var params: WindowManager.LayoutParams? = null
    /** 是否避开状态栏/挖孔区;设置页改开关后由 IslandContent 订阅实时同步。 */
    private var avoidStatusBar = true
    /** 监听横竖屏/分屏等配置变化,Service 本身没有 onConfigurationChanged 回调。 */
    private var configCallback: ComponentCallbacks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, NotificationCenter.islandServiceNotification(this))
        // 顺带开始监听通话状态(需要 READ_PHONE_STATE,没有则静默跳过)
        CallStateMonitor.start(this)
        avoidStatusBar = FeatureFlags.get(this).islandAvoidStatusBar()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val compose = ComposeView(ContextThemeWrapper(this, R.style.Theme_Nuoyi)).apply { setContent { NuoyiTheme { IslandContent() } } }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = statusBarTop()
        }
        // 之前拖动过就按保存时的屏幕尺寸等比还原;否则保持顶部居中(拖动后会切到绝对坐标)
        val dm = resources.displayMetrics
        val saved = FeatureFlags.get(this).islandPosForScreen(dm.widthPixels, dm.heightPixels)
        if (saved != null) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = saved.first
            params.y = saved.second
        }
        try {
            this.params = params
            wm!!.addView(compose, params)
            view = compose
            // 视图测量完成后再 clamp 一次,确保胶囊完整落在当前屏幕内(旋转/分屏后尤其重要)
            clampToScreen(params)
            wm!!.updateViewLayout(compose, params)
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: Throwable) {
            stopSelf()
        }
        // Service 没有 onConfigurationChanged,用 ComponentCallbacks 监听横竖屏/分屏变化
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = relayoutForScreen()
            override fun onLowMemory() = Unit
        }
        registerComponentCallbacks(cb)
        configCallback = cb
    }

    override fun onDestroy() {
        configCallback?.let { unregisterComponentCallbacks(it) }
        configCallback = null
        CallStateMonitor.stop()
        view?.let { wm?.removeView(it) }
        view = null
        super.onDestroy()
    }

    /**
     * 横竖屏 / 分屏 / 外接屏变化时重算位置:按保存时的比例还原,再 clamp 到新屏幕内。
     * 没拖动过的胶囊仍保持顶部居中,只更新离顶距离(横竖屏状态栏高度可能不同)。
     */
    private fun relayoutForScreen() {
        val p = params ?: return
        val v = view ?: return
        val dm = resources.displayMetrics
        val saved = FeatureFlags.get(this).islandPosForScreen(dm.widthPixels, dm.heightPixels)
        if (saved == null) {
            p.y = statusBarTop()
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = saved.first
            p.y = saved.second
        }
        clampToScreen(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** 把窗口坐标限制在屏幕内;开启「避开状态栏」时 y 下限抬到状态栏下方。 */
    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val w = view?.width ?: 0
        val h = view?.height ?: 0
        val dm = resources.displayMetrics
        val low = minIslandY()
        p.x = p.x.coerceIn(0, max(0, dm.widthPixels - w))
        p.y = p.y.coerceIn(low, max(low, dm.heightPixels - h))
    }

    private fun minIslandY(): Int = if (avoidStatusBar) statusBarTop() else 0

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 被系统清掉时立刻把自己拉起来,尽量保证系统级胶囊常驻(仍需用户授予自启动白名单才真正有效)
        try {
            startForegroundService(Intent(this, DynamicIslandService::class.java))
        } catch (_: Throwable) {
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 状态栏高度 + 一点间距,作为胶囊离顶边的像素。 */
    private fun statusBarTop(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        return h + (10 * resources.displayMetrics.density).toInt()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.nuoyi.agent.action.OPEN"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun openCallLive() {
        val intent = Intent(this, CallLiveActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    /**
     * 给胶囊加「可拖动 + 点按 + 长按」手势:拖动改窗口位置并记住,点按=onTap,长按=onLongPress。
     * 用两个独立 [pointerInput]:点按/长按与拖动并行,移动超过 touchSlop 后 tap 自动取消、交给拖动,
     * 这样不用自己起协程判长按,也不用手写 await 循环。[key] 在内容变化时重建手势,避免闭包 capture 旧数据。
     */
    private fun Modifier.capsuleDrag(
        key: Any?,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ): Modifier = this
        .pointerInput(key) {
            detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
        }
        .pointerInput(key) {
            detectDragGestures(
                onDragStart = { ensureAbsoluteGravity() },
                onDrag = { change, dragAmount ->
                    params?.let { p ->
                        p.x = (p.x + dragAmount.x).roundToInt()
                        p.y = (p.y + dragAmount.y).roundToInt()
                        clampToScreen(p)
                        view?.let { v -> wm?.updateViewLayout(v, p) }
                    }
                    change.consume()
                },
                onDragEnd = { persistIslandPos() },
                onDragCancel = { persistIslandPos() },
            )
        }

    /** 首次拖动时把「居中」的 gravity 转成绝对左上坐标,方便之后用 x/y 自由定位。 */
    private fun ensureAbsoluteGravity() {
        val p = params ?: return
        if ((p.gravity and Gravity.CENTER_HORIZONTAL) != 0) {
            val w = view?.width ?: 0
            val dm = resources.displayMetrics
            p.x = ((dm.widthPixels - w) / 2f).roundToInt()
            p.gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun persistIslandPos() {
        val p = params ?: return
        val dm = resources.displayMetrics
        // 连屏幕尺寸一起存,下次横竖屏/分屏时才能按同一比例还原
        FeatureFlags.get(this).setIslandPos(p.x, p.y, dm.widthPixels, dm.heightPixels)
    }

    @Composable
    private fun IslandContent() {
        val active by NotificationCenter.active.collectAsState()
        val call by CallStateFlow.state.collectAsState()
        val flags = FeatureFlags.get(this@DynamicIslandService)
        val idlePill by flags.islandIdlePillChanges().collectAsState(initial = flags.islandIdlePill())
        val callMode by flags.islandCallModeChanges().collectAsState(initial = flags.islandCallMode())
        val avoidStatus by flags.islandAvoidStatusBarChanges().collectAsState(initial = flags.islandAvoidStatusBar())
        LaunchedEffect(avoidStatus) {
            avoidStatusBar = avoidStatus
            // 开关一改立即把胶囊挪出/放回状态栏区域,不用等重启服务
            params?.let { p ->
                clampToScreen(p)
                view?.let { v -> runCatching { wm?.updateViewLayout(v, p) } }
            }
        }

        when {
            callMode && (call.offhook || call.ringing) -> CallPill(call)
            active.isNotEmpty() -> NoticePill(active.last())
            idlePill -> IdlePill()
            else -> Spacer(Modifier.size(1.dp))
        }
    }

    @Composable
    private fun CallPill(call: CallState) {
        val c = NuoyiTheme.colors
        val isCall = call.offhook
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(call.offhook, call.startMs) {
            if (call.offhook) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
        }
        val durMs = if (call.offhook) (now - call.startMs).coerceAtLeast(0) else 0
        val dur = formatDuration(durMs)
        val label = if (call.ringing) "来电" else "通话中"
        val sub = if (call.ringing) (call.number ?: "未知号码") else if (call.number != null) "${call.number} · $dur" else dur
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .shadow(14.dp, RoundedCornerShape(22.dp), clip = false)
                .clip(RoundedCornerShape(22.dp))
                .border(1.5.dp, c.accent.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                .background(c.surface.copy(alpha = 0.98f))
                .capsuleDrag(
                    call.startMs,
                    onTap = { openCallLive() },
                    onLongPress = { NotificationCenter.post(this@DynamicIslandService, NuoyiNotification("call-hint", "通话中", "点胶囊可记要点 / 让 AI 助理记下待办", tone = Tone.Info)) },
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    Icon(NuoyiIcons.Phone, null, tint = c.accent, modifier = Modifier.size(20.dp))
                }
                NuoyiMascot(if (isCall) MascotMood.Listening else MascotMood.Happy, size = 38.dp)
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    Text(label, style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = NuoyiType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun NoticePill(shown: NuoyiNotification) {
        val c = NuoyiTheme.colors
        val mood = when (shown.tone) {
            Tone.Success -> MascotMood.Happy
            Tone.Warn, Tone.Alert -> MascotMood.Listening
            else -> MascotMood.Idle
        }
        val tint = when (shown.tone) {
            Tone.Success -> c.ok
            Tone.Warn -> Color(0xFFFF9F0A)
            Tone.Alert -> c.bad
            else -> c.accent
        }
        val dwell = NotificationCenter.defaultDwellMs
        val progress = remember { Animatable(1f) }
        LaunchedEffect(shown.id, dwell) {
            progress.snapTo(1f)
            if (dwell > 0) progress.animateTo(0f, tween(dwell.toInt(), easing = LinearEasing))
            else progress.snapTo(1f)
        }
        Box(
            Modifier
                .widthIn(max = 360.dp)
                .shadow(14.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .border(1.5.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                .background(c.surface.copy(alpha = 0.98f))
                .capsuleDrag(
                    shown.id,
                    onTap = { openApp() },
                    onLongPress = { NotificationCenter.dismiss(shown.id) },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NuoyiMascot(mood, size = 44.dp)
                Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    Text(shown.title, style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold),
                        color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shown.text, style = NuoyiType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(progress.value)
                    .height(3.dp).background(tint.copy(alpha = 0.9f)),
            )
        }
    }

    @Composable
    private fun IdlePill() {
        val c = NuoyiTheme.colors
        Box(
            Modifier
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface.copy(alpha = 0.95f))
                .capsuleDrag(Unit, onTap = { openApp() }, onLongPress = {})
                .padding(8.dp),
        ) {
            NuoyiMascot(MascotMood.Idle, size = 30.dp)
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }
}
