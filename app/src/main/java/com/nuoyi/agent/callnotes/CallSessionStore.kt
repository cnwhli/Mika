package com.nuoyi.agent.callnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一次通话的记录(起止时间、号码、通话中记下的要点)。 */
data class CallSession(
    val id: String,
    val number: String?,
    val isIncoming: Boolean,
    val startMs: Long,
    val endMs: Long,
    val noteCount: Int = 0,
)

/** 通话中记下的一个要点;kind 区分「随手记(note)」与「AI 指令(command)」。 */
data class CallNote(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val text: String,
    val atMs: Long = System.currentTimeMillis(),
    val kind: String = "note",
)

/**
 * 本地通话记录(诺伊自己的,不依赖系统)。存在 files/calls/ 下:
 * call_sessions.json 是每次通话,call_notes.json 是通话中记下的要点。
 * 配合系统级灵动岛,可形成「通话 → 实时要点 → 挂断后整理纪要 → 时间线」的闭环。
 */
class CallSessionStore(private val dir: File) {
    private val file = File(dir, "call_sessions.json")
    private val notesFile = File(dir, "call_notes.json")

    fun begin(number: String?, isIncoming: Boolean, startMs: Long): String {
        val id = UUID.randomUUID().toString()
        save(list() + CallSession(id, number?.takeIf { it.isNotBlank() }, isIncoming, startMs, endMs = 0))
        return id
    }

    fun end(id: String, endMs: Long) = save(list().map { if (it.id == id) it.copy(endMs = endMs) else it })

    fun addNote(sessionId: String, text: String, kind: String = "note") {
        val t = text.trim()
        if (t.isBlank() || sessionId.isBlank()) return
        val arr = if (notesFile.exists()) runCatching { JSONArray(notesFile.readText()) }.getOrDefault(JSONArray()) else JSONArray()
        arr.put(JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("sessionId", sessionId)
            put("text", t)
            put("atMs", System.currentTimeMillis())
            put("kind", kind)
        })
        runCatching { notesFile.writeText(arr.toString()) }
        save(list().map { if (it.id == sessionId) it.copy(noteCount = it.noteCount + 1) else it })
    }

    fun list(): List<CallSession> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                CallSession(
                    id = o.optString("id"),
                    number = o.optString("number").takeIf { it.isNotBlank() },
                    isIncoming = o.optBoolean("incoming"),
                    startMs = o.optLong("startMs"),
                    endMs = o.optLong("endMs"),
                    noteCount = o.optInt("noteCount", 0),
                )
            }.sortedByDescending { it.startMs }
        }.getOrDefault(emptyList())
    }

    fun notes(sessionId: String): List<CallNote> {
        if (!notesFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(notesFile.readText())
            List(arr.length()) { i -> arr.getJSONObject(i) }
                .filter { it.optString("sessionId") == sessionId }
                .map {
                    CallNote(
                        id = it.optString("id"),
                        sessionId = it.optString("sessionId"),
                        text = it.optString("text"),
                        atMs = it.optLong("atMs"),
                        kind = it.optString("kind", "note"),
                    )
                }
                .sortedBy { it.atMs }
        }.getOrDefault(emptyList())
    }

    private fun save(items: List<CallSession>) {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id)
                put("number", s.number ?: "")
                put("incoming", s.isIncoming)
                put("startMs", s.startMs)
                put("endMs", s.endMs)
                put("noteCount", s.noteCount)
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        fun of(context: Context) = CallSessionStore(File(context.filesDir, "calls"))
    }
}
