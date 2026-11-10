package com.nuoyi.agent.cap.sandbox

import com.nuoyi.agent.core.workspace.NioFdSys
import com.nuoyi.agent.core.workspace.WorkspaceFileGate

/** JVM 测试里的关口(按路径的近似;手机上是 AndroidFdSys.gate)。 */
internal val JVM_GATE = WorkspaceFileGate(NioFdSys)
