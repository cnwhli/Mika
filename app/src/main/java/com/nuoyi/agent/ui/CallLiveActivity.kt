package com.nuoyi.agent.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.callnotes.CallNote
import com.nuoyi.agent.callnotes.CallSessionStore
import com.nuoyi.agent.callnotes.CallStateFlow
import com.nuoyi.agent.callnotes.CallStateMonitor
import com.nuoyi.agent.callnotes.MinutesAnalyzer
import com.nuoyi.agent.callnotes.applyMinutesActions
import com.nuoyi.agent.callnotes.readMinutesFromTree
import com.nuoyi.agent.settings.ProviderStore
import com.nuoyi.agent.ui.components.ButtonKind
import com.nuoyi.agent.ui.components.NuoyiButton
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiMascot
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通话中的轻量速记台(AI 通话助理)。从灵动岛胶囊点「记一笔」进来:
 * 显示实时通话时长,可随手记要点,也可下指令(如「提醒我下午3点跟进」),
 * 诺伊会用主模型抽成待办/提醒/日程并落盘。通话结束后可一键整理系统通话纪要。
 *
 * 注意:第三方应用拿不到通话双方的声音,这里不录音、不转写对方,只负责「通话中记 + 挂断后整理」。
 */
class CallLiveActivity : androidx.activity.ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { NuoyiTheme { CallLiveScreen(onClose = { finish() }) } }
    }
}

@Composable
private fun CallLiveScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProviderStore.get(context) }
    val scope = rememberCoroutineScope()

    val call by CallStateFlow.state.collectAsState()
    val sessionId = remember { CallStateMonitor.currentSessionId() }
    val active = call.offhook || call.ringing

    var note by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(sessionId?.let { CallSessionStore.of(context).notes(it) } ?: emptyList<CallNote>()) }

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(call.offhook, call.startMs) {
        if (call.offhook) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val dur = if (call.offhook) formatDuration(now - call.startMs) else ""

    fun refreshNotes() { sessionId?.let { notes = CallSessionStore.of(context).notes(it) } }

    suspend fun runAnalyze(text: String) {
        if (text.isBlank()) return
        withContext(Dispatchers.IO) {
            val actions = MinutesAnalyzer(store).analyze(text)
            if (actions.error != null) {
                withContext(Dispatchers.Main) { status = "分析失败：${actions.error}" }
                return@withContext
            }
            val msg = applyMinutesActions(context, store, actions)
            withContext(Dispatchers.Main) { status = msg; refreshNotes() }
        }
    }

    suspend fun capture(text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        sessionId?.let { CallSessionStore.of(context).addNote(it, t, "note") }
        runAnalyze(t)
    }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                busy = true
                status = "正在读取通话纪要文件…"
                val text = readMinutesFromTree(context, uri)
                busy = false
                if (text.isBlank()) {
                    status = "没有读到可分析的文本（支持 .txt / .md / .json）"
                    return@launch
                }
                runAnalyze(text)
            }
        }
    }

    Surface(color = NuoyiTheme.colors.surface, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) { Text("关闭", color = NuoyiTheme.colors.accent) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NuoyiMascot(if (active) MascotMood.Listening else MascotMood.Idle, size = 32.dp)
                        Text(
                            if (call.ringing) "来电中" else if (call.offhook) "通话中 $dur" else "通话已结束",
                            style = NuoyiType.Title,
                            color = NuoyiTheme.colors.ink,
                        )
                    }
                    if (call.number != null) Text(call.number ?: "", style = NuoyiType.Hint, color = NuoyiTheme.colors.ink2)
                }
                Box(Modifier.size(48.dp))
            }

            Text(
                if (active) "通话中随手记下的要点，挂断后会留在「通话纪要」里。" else "已可整理这次通话：导入系统生成的纪要，或回顾刚才记下的要点。",
                style = NuoyiType.Body, color = NuoyiTheme.colors.ink2,
            )

            OutlinedTextField(
                note, { note = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("记一笔 / 下指令，如「提醒我下午3点跟进 X」") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val t = note; note = ""; scope.launch { capture(t) }
                }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuoyiButton("记下来", onClick = { val t = note; note = ""; scope.launch { capture(t) } })
                NuoyiButton("整理系统纪要", onClick = { treeLauncher.launch(null) }, kind = ButtonKind.Secondary)
            }

            if (busy) Text("正在分析…", style = NuoyiType.Hint, color = NuoyiTheme.colors.ink2)
            if (status.isNotBlank()) Text(status, style = NuoyiType.Body, color = NuoyiTheme.colors.ink)

            if (notes.isNotEmpty()) {
                Text("本次通话记下 ${notes.size} 条", style = NuoyiType.Caption, color = NuoyiTheme.colors.ink2)
                Column(Modifier.fillMaxWidth()) {
                    notes.forEach { n ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Text("· ", color = NuoyiTheme.colors.ink2)
                            Text(n.text, style = NuoyiType.Body, color = NuoyiTheme.colors.ink, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
