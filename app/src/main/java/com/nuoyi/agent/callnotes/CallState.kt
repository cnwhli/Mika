package com.nuoyi.agent.callnotes

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前通话状态。第三方应用拿不到通话双方的声音(无通话录音权限、MediaProjection 也排除通话音频),
 * 所以这里只描述「有没有在通话 / 打了多久 / 是谁」,供灵动岛胶囊实时显示,并在挂断后提示整理纪要。
 */
data class CallState(
    /** 正在响铃(来电)。 */
    val ringing: Boolean = false,
    /** 通话进行中(已接听或已拨出)。 */
    val offhook: Boolean = false,
    /** 对方号码(部分系统在无 READ_CALL_LOG 时为空)。 */
    val number: String? = null,
    /** 是否为来电(true=来电,false=去电)。 */
    val isIncoming: Boolean = false,
    /** 本次通话开始(接听/拨出)的时间戳,毫秒。 */
    val startMs: Long = 0L,
)

/** 进程内唯一的通话状态流;DynamicIslandService 与 CallLiveActivity 都读它。 */
object CallStateFlow {
    private val _state = MutableStateFlow(CallState())
    val state: StateFlow<CallState> = _state.asStateFlow()
    fun update(s: CallState) { _state.value = s }
}
