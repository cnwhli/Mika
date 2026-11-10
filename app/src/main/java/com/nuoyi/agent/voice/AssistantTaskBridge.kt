package com.nuoyi.agent.voice

import android.content.Context
import com.nuoyi.agent.core.voice.VoiceTaskBridge
import com.nuoyi.agent.task.TaskService

/**
 * 把语音动作接到现有任务系统。状态摘要由宿主注入（默认 null），
 * 这样语音层不依赖 TaskState 的内部结构，改动任务循环时不会连坐。
 */
class AssistantTaskBridge(
    context: Context,
    private val active: () -> Boolean = { false },
    private val statusText: () -> String? = { null },
) : VoiceTaskBridge {
    private val app = context.applicationContext

    override fun hasActiveTask(): Boolean = runCatching { active() }.getOrDefault(false)

    override fun start(goal: String) {
        if (goal.isNotBlank()) runCatching { TaskService.start(app, goal) }
    }

    /** 插话在现有任务系统里也是一次 start；带上原对话编号才能落到同一个任务上。 */
    override fun steer(text: String) {
        if (text.isNotBlank()) runCatching { TaskService.start(app, text) }
    }

    override fun cancel() {
        runCatching { TaskService.cancel(app) }
    }

    override fun status(): String? = runCatching { statusText() }.getOrNull()
}
