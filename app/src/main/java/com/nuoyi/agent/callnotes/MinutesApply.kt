package com.nuoyi.agent.callnotes

import android.content.Context
import com.nuoyi.agent.cap.phone.a11y.CalendarBulkWriter
import com.nuoyi.agent.core.uistate.ActionResult
import com.nuoyi.agent.core.uistate.SystemAction
import com.nuoyi.agent.settings.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** 把 [MinutesAnalyzer] 抽出来的结构化结果落盘:日程写系统日历、提醒走系统闹钟、待办存本地。返回给用户看的状态文案。 */
suspend fun applyMinutesActions(context: Context, store: ProviderStore, actions: MinutesActions): String = withContext(Dispatchers.IO) {
    val sb = StringBuilder()
    if (actions.events.isNotEmpty()) {
        val evs = actions.events.filter { it.beginMs > 0 }.map {
            SystemAction.AddCalendarEvent(it.title, it.beginMs, it.endMs, it.allDay, it.location, it.description)
        }
        if (evs.isNotEmpty()) {
            val res = CalendarBulkWriter.insert(context, evs)
            sb.append("日程 ${evs.size} 条 → ")
            sb.append(
                if (res.result is ActionResult.Ok) "已写入系统日历"
                else "写入失败（${(res.result as? ActionResult.Failed)?.reason ?: "未知"}，可在系统设置里给本应用日历权限）",
            )
            sb.append("；")
        }
    }
    actions.todos.forEach { TodoStore.of(context).add(it) }
    val now = System.currentTimeMillis()
    actions.reminders.forEach { r ->
        val note = if (r.atMs != null && r.atMs > 0) "${r.text}（提醒 ${fmt(r.atMs)}）" else r.text
        TodoStore.of(context).add(note)
        if (r.atMs != null && r.atMs > now) {
            CallNoteReminder.schedule(context, UUID.randomUUID().toString(), r.text, r.atMs)
        }
    }
    sb.append("待办 ${actions.todos.size} 条；提醒 ${actions.reminders.size} 条。")
    sb.toString()
}

private fun fmt(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))
