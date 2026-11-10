package com.nuoyi.agent.notify

import android.app.Service
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.gestures.detectTapGestures
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

/**
 * 系统级灵动岛:把 [NotificationCenter.active] 的通知、以及实时通话状态,显示在屏幕顶部居中的胶囊里(像 iOS 灵动岛),
 * 点一下回 App,长按划掉。它本身不依赖系统通知栏权限;若没有「显示在其他应用上层」权限就不显示。
 * 用前台服务保活(常驻低优先通知只用来保活,胶囊显示不靠它);被系统清掉时 [onTaskRemoved] 会再把自己拉起来。
 */
class DynamicIslandService : Service() {
    private var wm: WindowManager? = null
    private var view: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(1, NotificationCenter.islandServiceNotification(this))
        // 顺带开始监听通话状态(需要 READ_PHONE_STATE,没有则静默跳过)
        CallStateMonitor.start(this)
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
        try {
            wm!!.addView(compose, params)
            view = compose
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: Throwable) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        CallStateMonitor.stop()
        view?.let { wm?.removeView(it) }
        view = null
        super.onDestroy()
    }

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

    @Composable
    private fun IslandContent() {
        val active by NotificationCenter.active.collectAsState()
        val call by CallStateFlow.state.collectAsState()
        val flags = FeatureFlags.get(this@DynamicIslandService)
        val idlePill by flags.islandIdlePillChanges().collectAsState(initial = flags.islandIdlePill())
        val callMode by flags.islandCallModeChanges().collectAsState(initial = flags.islandCallMode())

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
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { openCallLive() },
                        onLongPress = { NotificationCenter.post(this@DynamicIslandService, NuoyiNotification("call-hint", "通话中", "点胶囊可记要点 / 让 AI 助理记下待办", tone = Tone.Info)) },
                    )
                }
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
                .pointerInput(shown.id) {
                    detectTapGestures(
                        onTap = { openApp() },
                        onLongPress = { NotificationCenter.dismiss(shown.id) },
                    )
                }
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
                .pointerInput(Unit) { detectTapGestures(onTap = { openApp() }) }
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
