package com.wangzan101.mika.voice

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.core.voice.VoiceCapabilities
import com.wangzan101.mika.core.voice.VoiceConsent
import com.wangzan101.mika.core.voice.VoiceDisclosure
import com.wangzan101.mika.core.voice.WakeWordReject
import com.wangzan101.mika.core.voice.WakeWordResult

/**
 * 语音设置页，也是系统「默认数字助手」设置项里的入口（见 voice_interaction_service.xml）。
 *
 * 两件事必须在这里做完，否则 [VoiceSessionHost] 的第一道闸会拒绝开麦：
 * 1. 逐项看完并勾选隐私告知（整屏截图、麦克风、免费档训练、额度来源）；
 * 2. 引导用户去系统设置把本产品选为默认数字助手 —— App 自己申请不了这个角色。
 *
 * 自定义唤醒词和常驻开关只在个人版显示；公版连输入控件都不出现，
 * 即便有人拿到这个类去反射调用，core 的 VoiceCapabilities 也仍会拒绝。
 */
class VoiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { VoiceSetupScreen(VoiceRuntime.capabilities) }
        }
    }
}

@Composable
private fun VoiceSetupScreen(caps: VoiceCapabilities) {
    val context = LocalContext.current
    val store = remember { VoiceConsentStore(context) }
    var consent by remember { mutableStateOf(store.read()) }
    var message by remember { mutableStateOf("") }
    var alwaysOn by remember { mutableStateOf(VoiceRuntime.alwaysOnEnabled(context)) }
    var newWord by remember { mutableStateOf(VoiceRuntime.wakeWords().last()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("语音与免手设置", style = MaterialTheme.typography.headlineSmall)
        Text(
            "公版由系统数字助手唤起：设置里的「默认应用 › 数字助手应用」选中本产品后，" +
                "用系统手势 / 电源键 / 耳机键叫起，进入会话后全程不用碰屏幕。",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            if (AssistantSetup.isDefaultAssistant(context)) "已是默认数字助手" else "尚未设为默认数字助手",
            style = MaterialTheme.typography.titleMedium,
        )
        Button(onClick = {
            runCatching { context.startActivity(AssistantSetup.defaultAppsSettings()) }
        }) { Text("去系统设置设为默认助手") }

        Text("开始之前请逐项确认", style = MaterialTheme.typography.titleMedium)
        VoiceDisclosure.checklist().forEach { item ->
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = flag(consent, item.key),
                    onCheckedChange = { v -> consent = withFlag(consent, item.key, v) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall)
                    Text(item.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Button(onClick = {
            val allChecked = VoiceDisclosure.checklist().all { flag(consent, it.key) }
            if (!allChecked) {
                message = "请先逐项看完并勾选上面的说明"
            } else {
                store.write(consent.copy(acknowledged = true))
                consent = store.read()
                message = "已保存，现在可以用语音了"
            }
        }) { Text("保存授权并启用语音") }

        if (caps.allowCustomWakeWord) {
            Text("个人版：自定义唤醒词", style = MaterialTheme.typography.titleMedium)
            Text(
                "当前生效：${VoiceRuntime.wakeWords().joinToString(" / ")}。出厂词始终保留，改坏了也能喊回来。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = newWord,
                onValueChange = { newWord = it },
                label = { Text("2-8 个中文或英数字，不能是控制语或别的助手") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message = when (val r = VoiceRuntime.changeWakeWord(context, newWord)) {
                        is WakeWordResult.Ok -> "唤醒词已改为「${r.word}」"
                        is WakeWordResult.Bad -> rejectText(r.reason)
                    }
                }) { Text("保存唤醒词") }
                Button(onClick = {
                    VoiceRuntime.changeWakeWord(context, "")
                    newWord = caps.fixedWakeWord
                    message = "已恢复出厂唤醒词"
                }) { Text("恢复默认") }
            }
        } else {
            Text("公版唤醒词", style = MaterialTheme.typography.titleMedium)
            Text(
                "固定为「${caps.fixedWakeWord}」，不可修改。Android 12 起系统热词只对系统级助手开放，" +
                    "第三方应用没有「喊一声就醒」；这个固定词只用于会话内剥掉前缀。",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (caps.allowAlwaysOnListening) {
            Text("个人版：常驻唤醒", style = MaterialTheme.typography.titleMedium)
            Text(
                "开启后前台服务会持续听唤醒词，命中即进入语音会话，全程免手。耗电明显，" +
                    "且锁屏或后台时系统可能掐断麦克风；通知栏可随时关闭。仅限自用侧载。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("常驻唤醒", modifier = Modifier.weight(1f))
                Switch(checked = alwaysOn, onCheckedChange = { on ->
                    val ok = runCatching {
                        if (on) VoiceRuntime.startAlwaysOn(context) else VoiceRuntime.stopAlwaysOn(context)
                    }.isSuccess
                    if (ok) {
                        alwaysOn = on
                        message = if (on) "已开启，通知栏可见" else "已关闭"
                    } else {
                        message = "启动失败：请在应用前台打开本页再点开关"
                    }
                })
            }
        }

        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun flag(c: VoiceConsent, key: String): Boolean = when (key) {
    "mic" -> c.microphone
    "screen" -> c.screenCapture
    "training" -> c.freeTierTrainingUnderstood
    "quota" -> c.ownCredentialsConfirmed
    else -> false
}

private fun withFlag(c: VoiceConsent, key: String, value: Boolean): VoiceConsent = when (key) {
    "mic" -> c.copy(microphone = value)
    "screen" -> c.copy(screenCapture = value)
    "training" -> c.copy(freeTierTrainingUnderstood = value)
    "quota" -> c.copy(ownCredentialsConfirmed = value)
    else -> c
}

private fun rejectText(reason: WakeWordReject): String = when (reason) {
    WakeWordReject.EditionLocked -> "公版不允许自定义唤醒词"
    WakeWordReject.Empty -> "唤醒词不能为空"
    WakeWordReject.TooShort -> "太短了，至少 2 个字"
    WakeWordReject.TooLong -> "太长了，最多 8 个字"
    WakeWordReject.IllegalChar -> "只能用中文、英文字母和数字，不要带标点"
    WakeWordReject.ReservedWord -> "和「确认／取消」等控制语撞车了，换一个"
    WakeWordReject.CompetingAssistant -> "和别的语音助手撞车了，换一个"
    WakeWordReject.AllDigits -> "不能全是数字"
}
