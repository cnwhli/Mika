package com.nuoyi.agent.callnotes

import com.nuoyi.agent.core.model.Author
import com.nuoyi.agent.core.model.Message
import com.nuoyi.agent.core.model.ModelRole
import com.nuoyi.agent.core.model.Part
import com.nuoyi.agent.core.model.Role
import com.nuoyi.agent.core.model.ToolSpec
import com.nuoyi.agent.settings.ProviderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 从纪要原文抽取出的一条确定日程。 */
data class ExtractedEvent(
    val title: String,
    val beginMs: Long,
    val endMs: Long? = null,
    val allDay: Boolean = false,
    val location: String? = null,
    val description: String? = null,
)

/** 一条需要提醒的事。 */
data class ExtractedReminder(
    val text: String,
    val atMs: Long? = null,
)

/** 一份纪要经 LLM 行动化后的结构化结果。 */
data class MinutesActions(
    val summary: String = "",
    val todos: List<String> = emptyList(),
    val events: List<ExtractedEvent> = emptyList(),
    val reminders: List<ExtractedReminder> = emptyList(),
    val raw: String = "",
    val error: String? = null,
)

/**
 * 把通话转录 / 系统 AI 通话纪要原文,交给主模型抽取成「摘要 + 待办 + 日程 + 提醒」。
 * 复用 App 既有的 [ProviderStore.modelFor](ModelRole.Main) 与 ChatModel.complete,不另接一套模型。
 */
class MinutesAnalyzer(private val store: ProviderStore) {
    suspend fun analyze(text: String): MinutesActions = withContext(Dispatchers.IO) {
        val model = store.modelFor(ModelRole.Main)
        if (model == null) return@withContext MinutesActions(error = "no_model")
        runCatching {
            val messages = listOf(
                Message(Role.System, listOf(Part.Text(PROMPT)), Author.Mika),
                Message(Role.User, listOf(Part.Text(text)), Author.User),
            )
            val reply = model.complete("", messages, emptyList<ToolSpec>())
            val json = reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
            parse(json)
        }.getOrDefault(MinutesActions(error = "parse_failed"))
    }

    private fun parse(json: String): MinutesActions {
        val body = json.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching {
            val obj = JSONObject(body)
            val todos = obj.optJSONArray("todos")?.toStrList().orEmpty()
            val events = obj.optJSONArray("events")?.let { a ->
                List(a.length()) { i ->
                    val e = a.getJSONObject(i)
                    ExtractedEvent(
                        title = e.optString("title"),
                        beginMs = e.optLong("beginMs", 0L),
                        endMs = if (e.has("endMs")) e.optLong("endMs") else null,
                        allDay = e.optBoolean("allDay", false),
                        location = e.optString("location").takeIf { it.isNotBlank() },
                        description = e.optString("description").takeIf { it.isNotBlank() },
                    )
                }.filter { it.title.isNotBlank() }
            }.orEmpty()
            val reminders = obj.optJSONArray("reminders")?.let { a ->
                List(a.length()) { i ->
                    val r = a.getJSONObject(i)
                    ExtractedReminder(r.optString("text"), if (r.has("atMs")) r.optLong("atMs") else null)
                }.filter { it.text.isNotBlank() }
            }.orEmpty()
            MinutesActions(
                summary = obj.optString("summary"),
                todos = todos,
                events = events,
                reminders = reminders,
                raw = json,
            )
        }.getOrDefault(MinutesActions(raw = json, error = "parse_failed"))
    }

    private fun JSONArray.toStrList(): List<String> =
        List(length()) { i -> optString(i) }.filter { it.isNotBlank() }

    companion object {
        private val PROMPT = """你是一个"会议纪要行动化"助手。下面是一段通话转录或系统 AI 通话纪要原文。
请从中抽取：
1) summary：一句话中文摘要；
2) todos：需要做的事情（字符串数组，每条简短可执行）；
3) events：确定的日程（字段：title 标题、beginMs 开始时间毫秒时间戳、endMs 结束时间毫秒时间戳可空、allDay 是否全天、location 地点、description 说明）；
4) reminders：需要提醒的事（字段：text 提醒文字、atMs 提醒时间毫秒时间戳，可空）。
只输出 JSON，不要任何解释，格式如下：
{"summary":"...","todos":["..."],"events":[{"title":"...","beginMs":0,"endMs":0,"allDay":false,"location":"","description":""}],"reminders":[{"text":"...","atMs":0}]}
某项为空就给空数组。原文没给具体时刻时 beginMs/atMs 给 0。"""
    }
}
