package com.nuoyi.agent.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.CountDownTimer
import androidx.core.app.NotificationCompat
import com.nuoyi.agent.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 通知的语气,决定灵动岛胶囊上吉祥物的表情和系统通知的颜色。 */
enum class Tone { Info, Success, Warn, Alert }

/** Extra key: 点击通知后要跳转到的页面路由(如 "callnotes"),由 MainActivity 读取。 */
const val EXTRA_OPEN_ROUTE = "com.nuoyi.agent.extra.OPEN_ROUTE"

/** 一条诺伊自有通知(任务完成、需要你处理等);同时进系统通知栏和顶部的灵动岛胶囊。 */
data class NuoyiNotification(
    val id: String,
    val title: String,
    val text: String,
    val tone: Tone = Tone.Info,
    val atMillis: Long = System.currentTimeMillis(),
    /** 灵动岛胶囊停留多久后自动收起。 */
    val timeoutMillis: Long = 6000,
    /** 点击后跳转到的页面路由(见 [EXTRA_OPEN_ROUTE]);为空则只打开 App 首页。 */
    val openRoute: String? = null,
)

/**
 * 诺伊的通知中枢:任何地方想提醒用户,都走 [post];它一边发系统通知(状态栏),一边把通知放进 [active],
 * 顶部的灵动岛胶囊([com.nuoyi.agent.notify.DynamicIslandService])读 [active] 显示。
 * 不用系统通知也能显示灵动岛(胶囊读的是 [active],不依赖通知栏权限);发系统通知只是为了在锁屏/状态栏也能看到。
 */
object NotificationCenter {
    private const val CHANNEL = "nuoyi"
    private const val ISLAND_CHANNEL = "nuoyi-island"
    /** 通知自带的 timeoutMillis 用这个值表示「走默认」,由 [defaultDwellMs] 决定。 */
    private const val DEFAULT_TIMEOUT = 6000L

    private val _active = MutableStateFlow<List<NuoyiNotification>>(emptyList())
    /** 此刻应该在灵动岛显示的(至多保留最近 3 条),按到达先后。 */
    val active: StateFlow<List<NuoyiNotification>> = _active.asStateFlow()
    private val timers = LinkedHashMap<String, CountDownTimer>()

    /** 灵动岛胶囊默认停留时长(毫秒),可被设置页覆盖;0 = 不自动收起。 */
    var defaultDwellMs = 6000L

    /** 发一条通知:进系统通知栏 + 灵动岛。同一 id 先顶掉旧的。 */
    fun post(context: Context, n: NuoyiNotification) {
        _active.value = (_active.value.filter { it.id != n.id } + n).takeLast(3)
        postSystem(context, n)
        timers[n.id]?.cancel()
        // timeoutMillis == DEFAULT_TIMEOUT 表示「用默认停留时长」,否则用调用方指定的(例如长文案给更久)
        val dwell = if (n.timeoutMillis == DEFAULT_TIMEOUT) defaultDwellMs else n.timeoutMillis
        if (dwell <= 0) return // 不自动收起:等用户点开或长按划掉
        val timer = object : CountDownTimer(dwell, dwell) {
            override fun onTick(p0: Long) = Unit
            override fun onFinish() = dismiss(n.id)
        }
        timers[n.id] = timer
        timer.start()
    }

    /** 从灵动岛(和系统通知)撤掉一条。 */
    fun dismiss(id: String) {
        _active.value = _active.value.filter { it.id != id }
        timers.remove(id)?.cancel()
    }

    private fun postSystem(context: Context, n: NuoyiNotification) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = "com.nuoyi.agent.action.OPEN"
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (n.openRoute != null) putExtra(EXTRA_OPEN_ROUTE, n.openRoute)
        }
        val pi = PendingIntent.getActivity(context, n.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.nuoyi.agent.R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.text)
            .setColor(toneColor(n.tone))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        nm.notify(n.id.hashCode(), note.build())
    }

    /** 灵动岛前台服务的常驻低优先通知(只用来保活,胶囊本身不靠它显示)。 */
    fun islandServiceNotification(context: Context): android.app.Notification {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(nm)
        val intent = Intent(context, MainActivity::class.java).apply { action = "com.nuoyi.agent.action.OPEN" }
        val pi = PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, ISLAND_CHANNEL)
            .setSmallIcon(com.nuoyi.agent.R.drawable.ic_notification)
            .setContentTitle("诺伊灵动岛运行中")
            .setContentText("通知会在屏幕顶部以灵动岛形式出现")
            .setColor(0xFF0A7CFF.toInt())
            .setContentIntent(pi)
            .setOngoing(true)
            .setShowWhen(false)
            .build()
    }

    private fun ensureChannels(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "诺伊", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "诺伊的任务进展与提醒"
            })
        }
        if (nm.getNotificationChannel(ISLAND_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ISLAND_CHANNEL, "灵动岛", NotificationManager.IMPORTANCE_MIN).apply {
                description = "灵动岛胶囊的后台保活通知"
                setShowBadge(false)
            })
        }
    }

    private fun toneColor(t: Tone): Int = when (t) {
        Tone.Info -> 0xFF0A7CFF.toInt()
        Tone.Success -> 0xFF1F9D55.toInt()
        Tone.Warn -> 0xFFFF9F0A.toInt()
        Tone.Alert -> 0xFFE5483D.toInt()
    }
}
