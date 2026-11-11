package com.nuoyi.agent.callnotes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import com.nuoyi.agent.R
import com.nuoyi.agent.cap.phone.a11y.ScreenAccessibility
import com.nuoyi.agent.notify.EXTRA_OPEN_ROUTE
import com.nuoyi.agent.ui.MainActivity
import com.nuoyi.agent.ui.OpenRoutes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 「纪要收件箱」里的一条:一段从别处拿到的通话纪要文字。
 *
 * 厂商的 AI 通话纪要是系统 App 里的私有数据 —— 没有对外文件夹、不生成文本文件、也不给批量导出,
 * 第三方应用无论申请什么权限都读不到它的数据库(沙盒隔离)。唯一能拿到文字的三条合法通道是:
 * 用户在厂商 App 里点「分享」、复制文字、或者让无障碍服务读屏幕上的字。
 * 所以这里不假设「有文件可读」,改成把一次一次收到的文字攒成自己的库,再由诺伊统一整理 / 全量导出。
 */
data class MinutesClip(
    val id: String,
    val text: String,
    /** share / screen / clipboard / file:这段文字是怎么进来的。 */
    val source: String,
    /** 分享来源或抓屏时的前台包名,便于回头核对。 */
    val from: String,
    val atMs: Long,
)

/** 本地持久化,存在 files/calls/minutes_inbox.json。完全离线,不上传。 */
class MinutesInbox(private val dir: File) {
    private val file = File(dir, "minutes_inbox.json")

    /** 同一段文字重复收会跳过,返回 null;否则返回新收进来的那条。 */
    fun add(text: String, source: String, from: String = ""): MinutesClip? {
        val t = text.trim()
        if (t.isBlank()) return null
        val all = readAll()
        if (all.any { it.text == t }) return null
        val clip = MinutesClip(UUID.randomUUID().toString(), t.take(20_000), source, from, System.currentTimeMillis())
        val arr = JSONArray()
        (all + clip).forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("text", it.text)
                put("source", it.source)
                put("from", it.from)
                put("atMs", it.atMs)
            })
        }
        runCatching {
            dir.mkdirs()
            file.writeText(arr.toString())
        }
        return clip
    }

    /** 按收录先后(旧的在前),整理时按人读的顺序拼接。 */
    fun list(): List<MinutesClip> = readAll().sortedBy { it.atMs }

    fun delete(id: String) = write(readAll().filter { it.id != id })

    fun clear() = write(emptyList())

    /** 所有片段拼成一份完整文本,交给 MinutesAnalyzer 一次性整理。 */
    fun combined(): String = list().joinToString("\n\n") { "【纪要 ${fmt(it.atMs)}】\n${it.text}" }

    fun exportText(): String = buildString {
        append("诺伊 · 通话纪要收件箱（全量导出）\n")
        append("导出时间：${fmt(System.currentTimeMillis())}\n")
        append("共 ${list().size} 段\n\n")
        append(combined())
    }

    fun exportJson(): String {
        val root = JSONObject().apply {
            put("exportedAt", System.currentTimeMillis())
            put("count", list().size)
            put("clips", JSONArray().also { arr ->
                list().forEach {
                    arr.put(JSONObject().apply {
                        put("id", it.id)
                        put("source", it.source)
                        put("from", it.from)
                        put("atMs", it.atMs)
                        put("text", it.text)
                    })
                }
            })
        }
        return root.toString(2)
    }

    private fun readAll(): List<MinutesClip> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                MinutesClip(
                    id = o.optString("id"),
                    text = o.optString("text"),
                    source = o.optString("source", "share"),
                    from = o.optString("from"),
                    atMs = o.optLong("atMs"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(clips: List<MinutesClip>) {
        val arr = JSONArray()
        clips.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id); put("text", it.text); put("source", it.source)
                put("from", it.from); put("atMs", it.atMs)
            })
        }
        runCatching {
            dir.mkdirs()
            file.writeText(arr.toString())
        }
    }

    companion object {
        fun of(context: Context): MinutesInbox = MinutesInbox(File(context.filesDir, "calls"))

        fun fmt(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms))
    }
}

/**
 * 「抓取别的 App 屏幕上的纪要」:一条常驻通知 + 两个动作。
 * 用户停留在厂商通话纪要页面,下拉通知点「抓取当前屏」,广播在**不切换前台 Activity** 的情况下
 * 让无障碍服务读当前窗口的文字并存进收件箱;翻到下一通电话再点一次即可逐条收录。
 */
object MinutesCapture {
    const val ACTION_CAPTURE = "com.nuoyi.agent.action.CAPTURE_MINUTES"
    const val ACTION_DONE = "com.nuoyi.agent.action.CAPTURE_MINUTES_DONE"
    private const val NOTIFICATION_ID = 4211
    private const val CHANNEL = "nuoyi-minutes"

    /** 屏幕上的按钮/标签,抓屏时丢掉,免得污染纪要正文。 */
    private val NOISE = setOf(
        "返回", "分享", "复制", "更多", "全选", "编辑", "删除", "保存", "确定", "取消",
        "通话纪要", "通话记录", "详情", "展开", "收起", "搜索", "播放", "暂停", "关闭",
    )

    /** 发(或更新)那条常驻抓取通知;[message] 是最近一次抓取的结果提示。 */
    fun postNotice(context: Context, message: String? = null) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "通话纪要抓取", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "在别的 App 里逐条抓取通话纪要文字"
                },
            )
        }
        fun pi(action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
            context, req, Intent(context, MinutesCaptureReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, 3,
            Intent(context, MainActivity::class.java).apply {
                action = "com.nuoyi.agent.action.OPEN"
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_ROUTE, OpenRoutes.CALL_NOTES)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val count = MinutesInbox.of(context).list().size
        val note = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(message ?: "逐条收录通话纪要")
            .setContentText(
                message?.let { "已收录 $count 段 · 翻到下一通再点「抓取当前屏」" }
                    ?: "切到厂商通话纪要页面，点下面的「抓取当前屏」；已收录 $count 段",
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "厂商纪要 App 不给文件夹、也不给批量导出。用这条通知把屏幕上的纪要文字一段段收进诺伊，" +
                    "之后在「通话纪要」页可一键整理全部、导出全部 TXT / JSON。\n" +
                    "需要已开启「诺伊」无障碍服务。",
            ))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_notification, "抓取当前屏", pi(ACTION_CAPTURE, 1))
            .addAction(R.drawable.ic_notification, "完成", pi(ACTION_DONE, 2))
            .build()
        nm.notify(NOTIFICATION_ID, note)
    }

    fun dismiss(context: Context) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NOTIFICATION_ID)
    }

    /** 真正抓屏并存库,返回给用户看的结果文案。 */
    fun captureAndStore(context: Context): String {
        val service = ScreenAccessibility.instance.value
            ?: return "没读到：请先在 系统设置 → 无障碍 里打开「诺伊」"
        val (root, pkg) = activeWindow(service, context.packageName)
            ?: return "没读到：当前没有可读取的应用窗口"
        val raw = ArrayList<String>(256)
        collectText(root, raw, 0)
        val lines = raw
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 2 && it !in NOISE && it.length <= 2_000 }
            .distinct()
            .take(400)
        val text = lines.joinToString("\n")
        if (text.isBlank()) return "这一屏没读到文字（有些厂商页面是图片/自绘控件，试试分享或复制）"
        val clip = MinutesInbox.of(context).add(text, "screen", pkg)
            ?: return "这一屏和上次一样，没有重复收录"
        val count = MinutesInbox.of(context).list().size
        return "已收录第 $count 段（来源 ${pkg.substringAfterLast('.')}）"
    }

    /** 优先取前台 Activity 的窗口;若恰好抓到自己的浮窗,就在窗口列表里找别的应用窗口。 */
    private fun activeWindow(service: ScreenAccessibility, ownPkg: String): Pair<AccessibilityNodeInfo, String>? {
        val primary = runCatching { service.rootInActiveWindow }.getOrNull()
        if (primary != null && primary.packageName?.toString() != ownPkg) {
            return primary to (primary.packageName?.toString().orEmpty())
        }
        val windows = runCatching { service.windows }.getOrNull().orEmpty()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = runCatching { w.root }.getOrNull() ?: continue
            val p = r.packageName?.toString().orEmpty()
            if (p.isNotEmpty() && p != ownPkg) return r to p
        }
        return primary?.let { it to (it.packageName?.toString().orEmpty()) }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40 || out.size >= 500) return
        val t = runCatching { node.text?.toString()?.trim() }.getOrNull()
        if (!t.isNullOrEmpty()) out.add(t)
        val d = runCatching { node.contentDescription?.toString()?.trim() }.getOrNull()
        if (!d.isNullOrEmpty() && d != t) out.add(d)
        val n = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until n) collectText(runCatching { node.getChild(i) }.getOrNull(), out, depth + 1)
    }
}

/** 通知上两个动作的落点;抓屏遍历放在后台线程,避免阻塞广播主线程。 */
class MinutesCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == MinutesCapture.ACTION_DONE) {
            MinutesCapture.dismiss(context)
            return
        }
        if (intent.action != MinutesCapture.ACTION_CAPTURE) return
        val pending = goAsync()
        Thread {
            try {
                val msg = MinutesCapture.captureAndStore(context.applicationContext)
                MinutesCapture.postNotice(context.applicationContext, msg)
            } catch (e: Throwable) {
                MinutesCapture.postNotice(context.applicationContext, "抓取失败：${e.message ?: "未知错误"}")
            } finally {
                pending.finish()
            }
        }.start()
    }
}
