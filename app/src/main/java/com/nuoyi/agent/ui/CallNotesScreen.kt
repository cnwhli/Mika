package com.nuoyi.agent.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.content.ContextCompat
import com.nuoyi.agent.NuoyiApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.nuoyi.agent.callnotes.CallNote
import com.nuoyi.agent.callnotes.CallSessionStore
import com.nuoyi.agent.callnotes.MinutesAnalyzer
import com.nuoyi.agent.callnotes.TodoItem
import com.nuoyi.agent.callnotes.TodoStore
import com.nuoyi.agent.callnotes.applyMinutesActions
import com.nuoyi.agent.settings.ProviderStore
import com.nuoyi.agent.ui.components.ButtonKind
import com.nuoyi.agent.ui.components.ButtonSize
import com.nuoyi.agent.ui.components.FooterNote
import com.nuoyi.agent.ui.components.GroupCard
import com.nuoyi.agent.ui.components.GroupLabel
import com.nuoyi.agent.ui.components.ListRow
import com.nuoyi.agent.ui.components.NuoyiButton
import com.nuoyi.agent.ui.components.PageScaffold
import com.nuoyi.agent.ui.components.RowChevron
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiIcons
import com.nuoyi.agent.ui.theme.NuoyiMascot
import com.nuoyi.agent.ui.theme.rememberMascotBitmap
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import com.nuoyi.agent.voice.SpeechEvent
import com.nuoyi.agent.voice.SpeechFailure
import com.nuoyi.agent.voice.SpeechSession
import com.nuoyi.agent.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通话纪要:把系统 AI 通话纪要文件 / 一段录音,交给主模型抽取成「摘要 + 待办 + 日程 + 提醒」。
 * 第三方应用大多拿不到系统级通话录音,所以这里走「调取厂商已生成的通话纪要文件」路线:
 * 荣耀 Magic、小米等系统本身就会生成通话纪要,把它们所在的文件夹选进来即可;
 * 录音则复用 App 既有的语音输入(系统/豆包/FunASR/服务商)转写后同样整理。
 * 结果落盘:日程写系统日历、提醒走系统闹钟通知、待办存 App 本地。
 */
@Composable
fun CallNotesScreen(store: ProviderStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as NuoyiApp }
    val voice = remember { app.voice }
    val scope = rememberCoroutineScope()

    var summary by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var todos by remember { mutableStateOf(TodoStore.of(context).list()) }
    var recording by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }
    var recError by remember { mutableStateOf<String?>(null) }
    val analyzed = remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<SpeechSession?>(null) }
    var newTodo by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf(CallSessionStore.of(context).list()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var sessionNotes by remember { mutableStateOf<List<CallNote>>(emptyList()) }
    LaunchedEffect(Unit) { sessions = CallSessionStore.of(context).list() }

    fun refresh() { todos = TodoStore.of(context).list() }

    suspend fun analyze(text: String) {
        if (text.isBlank()) return
        analyzed.value = true
        withContext(Dispatchers.IO) {
            val actions = MinutesAnalyzer(store).analyze(text)
            if (actions.error != null) {
                withContext(Dispatchers.Main) { status = "分析失败：${actions.error}" }
                return@withContext
            }
            val msg = applyMinutesActions(context, store, actions)
            withContext(Dispatchers.Main) {
                summary = actions.summary.ifBlank { null }
                status = msg
                refresh()
            }
        }
    }

    fun startRecording() {
        val s = voice.start()
        session = s
        liveText = ""
        analyzed.value = false
        recError = null
        recording = true
        scope.launch {
            s.events.collect { ev ->
                when (ev) {
                    is SpeechEvent.Partial -> liveText = ev.stable + ev.tail
                    is SpeechEvent.Converting -> { /* 服务商转写中,等 Final */ }
                    is SpeechEvent.Final -> {
                        liveText = ev.text
                        recording = false
                        if (ev.text.isNotBlank()) analyze(ev.text)
                    }
                    is SpeechEvent.Failed -> {
                        recording = false
                        recError = when (ev.why) {
                            is SpeechFailure.NoPermission -> "没有麦克风权限"
                            is SpeechFailure.MicUnavailable -> "麦克风被其他应用占用"
                            is SpeechFailure.Network -> "网络错误"
                            else -> "无法识别（${ev.why}）"
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun stopRecording() {
        session?.release()
        session = null
        recording = false
        if (!analyzed.value && liveText.isNotBlank()) scope.launch { analyze(liveText) }
    }

    fun addTodo() {
        val v = newTodo.trim()
        if (v.isNotBlank()) { TodoStore.of(context).add(v); newTodo = ""; refresh() }
    }

    // 日历权限(写日程用)→ 再开文件夹选择器;录音权限单独要
    val treeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            scope.launch {
                busy = true
                status = "正在读取通话纪要文件…"
                val text = com.nuoyi.agent.callnotes.readMinutesFromTree(context, uri)
                busy = false
                if (text.isBlank()) {
                    status = "没有读到可分析的文本（支持 .txt / .md / .json）"
                    return@launch
                }
                analyze(text)
            }
        }
    }
    val calPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        treeLauncher.launch(null)
    }
    val recPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else recError = "需要麦克风权限才能录音"
    }

    PageScaffold("通话纪要", onBack) {
        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            NuoyiMascot(MascotMood.Listening, size = 48.dp, customBitmap = rememberMascotBitmap())
        }

        GroupLabel("记录")
        GroupCard {
            val hasCal = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
            ListRow(
                NuoyiIcons.Doc, "导入系统通话纪要",
                "选荣耀 / 小米等系统生成的通话纪要文件夹，自动整理成日程、提醒、待办",
                first = true,
                onClick = { if (hasCal) treeLauncher.launch(null) else calPerms.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
            ) { RowChevron() }
            ListRow(
                NuoyiIcons.Mic,
                if (recording) "正在录音…点此停止" else "录音纪要",
                if (recording) liveText.ifBlank { "说话中…" } else "录一段语音，自动转写并整理",
                onClick = {
                    if (recording) stopRecording()
                    else {
                        val hasRec = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                        if (hasRec) startRecording() else recPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
            ) { if (!recording) RowChevron() }
        }

        GroupLabel("通话记录")
        GroupCard {
            if (sessions.isEmpty()) {
                ListRow(null, "还没有通话记录", "通话结束会在这里留一条时间线", first = true) {}
            } else {
                sessions.forEachIndexed { i, s ->
                    val expanded = expandedId == s.id
                    val dur = if (s.endMs > s.startMs) fmtDuration(s.endMs - s.startMs) else null
                    ListRow(
                        NuoyiIcons.Phone,
                        (if (s.isIncoming) "来电 · " else "去电 · ") + (s.number ?: "未知号码"),
                        fmtDate(s.startMs) + (dur?.let { " · 通话 $it" } ?: "") + (if (s.noteCount > 0) " · ${s.noteCount} 条要点" else ""),
                        first = i == 0,
                        onClick = {
                            expandedId = if (expanded) null else s.id
                            if (expandedId != null) sessionNotes = CallSessionStore.of(context).notes(s.id)
                        },
                        trailing = { RowChevron() },
                    )
                    if (expanded) {
                        if (sessionNotes.isEmpty()) {
                            Text("通话中没记要点", Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp), style = NuoyiType.Caption, color = NuoyiTheme.colors.ink3)
                        } else {
                            sessionNotes.forEach { n ->
                                Text(
                                    "• ${n.text}",
                                    Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                                    style = NuoyiType.Caption, color = NuoyiTheme.colors.ink2,
                                )
                            }
                            NuoyiButton(
                                "整理成待办 / 提醒",
                                onClick = { scope.launch { analyze(sessionNotes.joinToString("\n") { it.text }) } },
                                size = ButtonSize.Small,
                                kind = ButtonKind.Tonal,
                                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }

        if (busy) FooterNote("正在分析…")
        recError?.let { FooterNote(it) }
        summary?.let {
            GroupLabel("摘要")
            GroupCard { ListRow(null, it, first = true) {} }
        }
        if (status.isNotBlank()) FooterNote(status)

        GroupLabel("待办（本地）")
        GroupCard {
            if (todos.isEmpty()) {
                ListRow(null, "还没有待办", "导入纪要或下方手动添加", first = true) {}
            } else {
                todos.forEachIndexed { i, t ->
                    TodoRow(t, first = i == 0,
                        onToggle = { TodoStore.of(context).toggle(t.id); refresh() },
                        onDelete = { TodoStore.of(context).remove(t.id); refresh() })
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                newTodo, { newTodo = it }, Modifier.weight(1f),
                placeholder = { Text("手动添加待办…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addTodo() }),
            )
            NuoyiButton("添加", onClick = { addTodo() }, size = ButtonSize.Medium, modifier = Modifier.padding(start = 8.dp))
        }

        FooterNote(
            "第三方应用大多拿不到系统级通话录音，所以这里走「调取厂商已生成的通话纪要文件」路线——荣耀 Magic、小米等系统本身就会生成通话纪要，" +
                "把它们所在的文件夹选进来即可。分析结果会在本地生成日程（写入系统日历）、提醒（系统闹钟通知）和待办（本应用本地记录）。",
        )
    }
}

/** 一条待办:点一下切换完成,右边「删除」直接删。 */
@Composable
private fun TodoRow(item: TodoItem, first: Boolean, onToggle: () -> Unit, onDelete: () -> Unit) {
    ListRow(
        null, (if (item.done) "✓ " else "• ") + item.text,
        subtitle = item.dueMs?.let { "提醒 ${fmtDate(it)}" },
        first = first,
        onClick = onToggle,
        trailing = {
            Text("删除", Modifier.clickable { onDelete() }.padding(8.dp), style = NuoyiType.Caption, color = NuoyiTheme.colors.bad)
        },
    )
}

/** 把 SAF 树里的文本文件(.txt/.md/.json)读出来拼成一个大字符串交给模型(见 [com.nuoyi.agent.callnotes.readMinutesFromTree])。 */
private suspend fun readMinutesFromTree(context: Context, treeUri: Uri): String =
    com.nuoyi.agent.callnotes.readMinutesFromTree(context, treeUri)

/** 时间戳 → 「MM-dd HH:mm」。 */
private fun fmtDate(ms: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(ms))

/** 毫秒时长 → 「MM:SS」或「H:MM:SS」。 */
private fun fmtDuration(ms: Long): String {
    val total = (ms / 1000).toInt().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
