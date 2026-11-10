package com.nuoyi.agent.callnotes

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 一条待办;dueMs 为提醒时间(毫秒),为空表示无具体时间。 */
data class TodoItem(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val done: Boolean = false,
    val createdMs: Long = System.currentTimeMillis(),
    val dueMs: Long? = null,
)

/**
 * 本地待办清单(诺伊自己的,不依赖系统)。存在 files/todos.json,用 org.json 读写,不引额外依赖。
 * 通话纪要/录音分析出来的待办与提醒都落在这里。
 */
class TodoStore(private val dir: File) {
    private val file = File(dir, "todos.json")

    fun list(): List<TodoItem> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                TodoItem(
                    id = o.optString("id").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                    text = o.optString("text"),
                    done = o.optBoolean("done"),
                    createdMs = o.optLong("createdMs", System.currentTimeMillis()),
                    dueMs = if (o.has("dueMs")) o.optLong("dueMs") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(text: String, dueMs: Long? = null): TodoItem {
        val item = TodoItem(text = text.trim(), dueMs = dueMs)
        if (item.text.isBlank()) return item
        save(list() + item)
        return item
    }

    fun toggle(id: String) = save(list().map { if (it.id == id) it.copy(done = !it.done) else it })

    fun remove(id: String) = save(list().filter { it.id != id })

    private fun save(items: List<TodoItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("text", it.text)
                put("done", it.done)
                put("createdMs", it.createdMs)
                it.dueMs?.let { d -> put("dueMs", d) }
            })
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        fun of(context: Context) = TodoStore(context.filesDir)
    }
}
