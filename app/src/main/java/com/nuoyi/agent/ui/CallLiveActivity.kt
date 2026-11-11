package com.nuoyi.agent.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.callnotes.CallNote
import com.nuoyi.agent.callnotes.CallSessionStore
import com.nuoyi.agent.callnotes.CallStateFlow
import com.nuoyi.agent.callnotes.CallStateMonitor
import com.nuoyi.agent.callnotes.MinutesAnalyzer
import com.nuoyi.agent.callnotes.applyMinutesActions
import com.nuoyi.agent.callnotes.readMinutesFromTree
import com.nuoyi.agent.callnotes.readMinutesFromUri
import com.nuoyi.agent.callnotes.MinutesFile
import com.nuoyi.agent.callnotes.discoverMinutes
import com.nuoyi.agent.settings.FeatureFlags
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** 文件夹 / 单文件两条入口读到的文本,统一走这里:置忙 → 读 → 分析。 */
    suspend fun ingest(reader: suspend () -> String) {
        busy = true
        status = "正在读取通话纪要文件…"
        val text = reader()
        busy = false
        if (text.isBlank()) {
            status = "没有读到可分析的文本（支持 .txt / .md / .json）"
            return
        }
        runAnalyze(text)
    }

    val flags = remember { FeatureFlags.get(context) }
    // 上次选过的纪要文件夹,下次打开选择器直接定位到那里
    val lastTree = remember { flags.lastMinutesTree()?.let { runCatching { Uri.parse(it) }.getOrNull() } }

    // 自动发现:已授权过的纪要文件夹会在打开页面时静默列出,不用每次重新选目录;
    // MediaStore 能读到的系统纪要文件也一起补进来,查不到(机型限制)时退回手动选择。
    var candidates by remember { mutableStateOf(emptyList<MinutesFile>()) }
    var scanning by remember { mutableStateOf(false) }
    suspend fun refreshCandidates() {
        scanning = true
        val since = call.startMs.takeIf { it > 0 } ?: (System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
        candidates = discoverMinutes(context, since)
        scanning = false
    }
    LaunchedEffect(Unit) { refreshCandidates() }

    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            flags.setLastMinutesTree(uri.toString())
            scope.launch {
                ingest { readMinutesFromTree(context, uri) }
                refreshCandidates()
            }
        }
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch { ingest { readMinutesFromUri(context, uri) } }
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NuoyiButton("整理纪要文件夹", onClick = { treeLauncher.launch(lastTree) }, kind = ButtonKind.Secondary)
                NuoyiButton("选单个文件", onClick = { fileLauncher.launch(arrayOf("text/plain", "text/markdown", "application/json", "*/*")) }, kind = ButtonKind.Tonal)
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "自动发现的纪要（${candidates.size}）",
                    style = NuoyiType.Caption, color = NuoyiTheme.colors.ink2,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { scope.launch { refreshCandidates() } }) {
                    Text("刷新", color = NuoyiTheme.colors.accent)
                }
            }
            when {
                scanning -> Text("正在查找通话纪要…", style = NuoyiType.Hint, color = NuoyiTheme.colors.ink3)
                candidates.isEmpty() -> Text(
                    "暂时没找到可自动读取的纪要。第一次用请先点「整理纪要文件夹」授权一次目录，之后这里会自动列出；部分机型只允许读自己授权过的文件夹。",
                    style = NuoyiType.Hint, color = NuoyiTheme.colors.ink3,
                )
                else -> Column(Modifier.fillMaxWidth()) {
                    candidates.take(6).forEach { f ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { scope.launch { ingest { readMinutesFromUri(context, f.uri) } } }
                                .padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("· ", color = NuoyiTheme.colors.ink2)
                            Column(Modifier.weight(1f)) {
                                Text(f.name, style = NuoyiType.Body, color = NuoyiTheme.colors.ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${fmtMinutesTime(f.lastModified)} · ${f.source}",
                                    style = NuoyiType.Hint, color = NuoyiTheme.colors.ink3)
                            }
                            Text("整理", style = NuoyiType.Hint, color = NuoyiTheme.colors.accent)
                        }
                    }
                    if (candidates.size > 6) Text("还有 ${candidates.size - 6} 个…", style = NuoyiType.Hint, color = NuoyiTheme.colors.ink3)
                }
            }

            Text(
                "系统通话纪要常见位置：小米/红米「录音机 › 通话录音」、华为/荣耀「实用工具 › 通话纪要」、OPPO/vivo「录音 › 通话」。也可直接选导出的 .txt / .md / .json 文件。",
                style = NuoyiType.Hint, color = NuoyiTheme.colors.ink3,
            )

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

private fun fmtMinutesTime(ms: Long): String =
    if (ms <= 0L) "时间未知" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ms))
