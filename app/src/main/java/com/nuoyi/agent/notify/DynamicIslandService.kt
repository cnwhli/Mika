package com.nuoyi.agent.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.R
import com.nuoyi.agent.ui.MainActivity
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiMascot
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 灵动岛:把 [NotificationCenter.active] 里的通知显示在屏幕顶部居中的胶囊里(像 iOS 灵动岛那样),
 * 点一下进 App。本身不依赖系统通知栏权限;若没有「显示在其他应用上层」权限就不显示(启动前由调用方判断)。
 * 用前台服务保活(常驻低优先通知只用来保活,胶囊显示不靠它);没有通知时窗口收成 1dp,不挡视线。
 */
class DynamicIslandService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wm: WindowManager? = null
    private var view: ComposeView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, NotificationCenter.islandServiceNotification(this))
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
        view?.let { wm?.removeView(it) }
        view = null
        super.onDestroy()
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

    @Composable
    private fun IslandContent() {
        val active by NotificationCenter.active.collectAsState()
        val shown = active.lastOrNull()
        if (shown == null) {
            Spacer(Modifier.size(1.dp))
            return
        }
        val c = NuoyiTheme.colors
        val mood = when (shown.tone) {
            Tone.Success -> MascotMood.Happy
            Tone.Warn, Tone.Alert -> MascotMood.Listening
            else -> MascotMood.Idle
        }
        // 按语气给胶囊上色:成功绿、警告橙、失败红、其余用品牌蓝
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
            // 停留时长进度条:从左往右收,走到头就自动收起;长按胶囊可立即划掉
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(progress.value)
                    .height(3.dp).background(tint.copy(alpha = 0.9f)),
            )
        }
    }
}
