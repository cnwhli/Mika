package com.nuoyi.agent.ui

import com.nuoyi.agent.i18n.tr
import com.nuoyi.agent.i18n.trPlural
import com.nuoyi.agent.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.nuoyi.agent.settings.FeatureFlags
import com.nuoyi.agent.settings.ThemeMode
import com.nuoyi.agent.ui.components.NuoyiSwitch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.nuoyi.agent.ui.components.NuoyiDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import com.nuoyi.agent.i18n.AppLanguage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuoyi.agent.capsule.CapsuleIdleOpacity
import com.nuoyi.agent.capsule.CapsuleMode
import com.nuoyi.agent.core.shell.BuiltinShellStatus
import com.nuoyi.agent.settings.ChannelRow
import com.nuoyi.agent.settings.Prerequisites
import com.nuoyi.agent.settings.Problem
import com.nuoyi.agent.settings.SystemStatus
import com.nuoyi.agent.settings.notificationOverview
import com.nuoyi.agent.notify.NotificationCenter
import com.nuoyi.agent.notify.NuoyiNotification
import com.nuoyi.agent.notify.Tone
import com.nuoyi.agent.task.ParallelLimit
import com.nuoyi.agent.ui.components.FooterNote
import com.nuoyi.agent.ui.components.GroupCard
import com.nuoyi.agent.ui.components.GroupLabel
import com.nuoyi.agent.ui.components.ListRow
import com.nuoyi.agent.ui.components.PageScaffold
import com.nuoyi.agent.ui.components.RadioRow
import com.nuoyi.agent.ui.components.RowChevron
import com.nuoyi.agent.ui.components.RowValue
import com.nuoyi.agent.ui.components.TileTone
import com.nuoyi.agent.ui.components.ValueTone
import com.nuoyi.agent.ui.theme.NuoyiIcons
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import com.nuoyi.agent.core.cap.phone.OperatingPace
import com.nuoyi.agent.core.usage.TokenBudgetLimits
import kotlin.math.roundToInt
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.nuoyi.agent.settings.MascotStore
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiMascot
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// ---------- 手机操作 ----------

/** 设置 › 手机操作:权限与配对的状态,重新配对、解除配对。Root 能力集中在高级与其他的独立页面。 */
@Composable
fun PhoneOpsScreen(
    status: SystemStatus,
    androidRelease: String,
    onProblem: (Problem) -> Unit,
    onRepair: () -> Unit,
    onUnpair: () -> Unit,
    onBack: () -> Unit,
) {
    val check = Prerequisites.check(status, hasMainModel = true)
    var confirmUnpair by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_phone_ops), onBack) {
        GroupLabel(stringResource(R.string.settings_status))
        GroupCard {
            val (a11yText, a11yTone) = StatusCopy.accessibilityValue(status.accessibility)
            ListRow(
                NuoyiIcons.Hand, stringResource(R.string.settings_a11y), stringResource(R.string.settings_a11y_sub), first = true,
                onClick = check.problems.firstOrNull { it is Problem.Accessibility }?.let { { onProblem(it) } },
            ) { RowValue(a11yText, a11yTone) }
            // 安卓 12 以下手机功能不可用,不列这一行(系统版本那一行已说明)
            if (status.sdkInt >= Prerequisites.MIN_SDK) {
                val helper = status.builtinShell
                val (hText, hTone) = PhoneSetupCopy.statusValue(helper)
                val reconnect = PhoneSetupCopy.canReconnect(helper)
                ListRow(
                    NuoyiIcons.Link, PhoneSetupCopy.ROW_TITLE,
                    if (reconnect && helper is BuiltinShellStatus.Failed) PhoneSetupCopy.failure(helper.reason).title
                    else PhoneSetupCopy.statusLine(helper, status.usbDebugging, status.rootStart),
                    onClick = { onProblem(Problem.PhoneHelper(helper)) },
                ) { if (reconnect) RowValue(PhoneSetupCopy.RECONNECT, ValueTone.Link) else RowValue(hText, hTone) }
            }
            val tooOld = status.sdkInt < Prerequisites.MIN_SDK
            val focusWarning = Problem.FocusMayLeak in check.problems
            ListRow(
                NuoyiIcons.Phone, stringResource(R.string.settings_system_version),
                if (focusWarning) stringResource(R.string.settings_android_focus, androidRelease) else stringResource(R.string.settings_android, androidRelease),
                onClick = when {
                    tooOld -> ({ onProblem(Problem.SystemTooOld) })
                    focusWarning -> ({ onProblem(Problem.FocusMayLeak) })
                    else -> null
                },
            ) {
                when {
                    tooOld -> RowValue(stringResource(R.string.pill_unsupported), ValueTone.Bad)
                    focusWarning -> RowValue(stringResource(R.string.pill_available), ValueTone.Plain)
                    else -> RowValue(stringResource(R.string.settings_meets), ValueTone.Ok)
                }
            }
        }
        if (status.sdkInt >= Prerequisites.MIN_SDK) {
            GroupLabel(stringResource(R.string.phone_channel))
            GroupCard {
                ListRow(NuoyiIcons.Link, PhoneSetupCopy.REPAIR, PhoneSetupCopy.REPAIR_SUBTITLE, first = true, onClick = onRepair) {
                    RowChevron()
                }
                val paired = status.builtinShell != BuiltinShellStatus.NotPaired
                ListRow(
                    NuoyiIcons.Close, PhoneSetupCopy.UNPAIR, PhoneSetupCopy.UNPAIR_SUBTITLE,
                    titleColor = if (paired) NuoyiTheme.colors.bad else NuoyiTheme.colors.ink3,
                    tone = if (paired) TileTone.Bad else TileTone.Neutral,
                    onClick = if (paired) ({ confirmUnpair = true }) else null,
                )
            }
            FooterNote(PhoneSetupCopy.usageNote(status.helperRestart))
        }
    }
    if (confirmUnpair) {
        NuoyiDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text(PhoneSetupCopy.UNPAIR_TITLE) },
            text = { Text(PhoneSetupCopy.UNPAIR_BODY) },
            confirmButton = {
                TextButton({ confirmUnpair = false; onUnpair() }) { Text(PhoneSetupCopy.UNPAIR_CONFIRM, color = NuoyiTheme.colors.bad) }
            },
            dismissButton = { TextButton({ confirmUnpair = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

// ---------- 通知 ----------

/**
 * 设置 › 通知:只看状态、去系统里改。顶上是 App 通知总开关,下面三个任务通知频道各一行(什么时候发 + 现在会不会弹出),
 * 点哪一行都由 [onFix] 打开对应的系统设置页(总开关没开时先请求权限或打开应用通知页)。
 */
@Composable
fun NotificationsScreen(
    status: SystemStatus,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    islandEnabled: Boolean = true,
    onIslandEnabled: (Boolean) -> Unit = {},
    islandCallMode: Boolean = true,
    onIslandCallMode: (Boolean) -> Unit = {},
    islandIdlePill: Boolean = false,
    onIslandIdlePill: (Boolean) -> Unit = {},
    islandAvoidStatusBar: Boolean = true,
    onIslandAvoidStatusBar: (Boolean) -> Unit = {},
    islandHugCutout: Boolean = true,
    onIslandHugCutout: (Boolean) -> Unit = {},
    islandExpandCard: Boolean = true,
    onIslandExpandCard: (Boolean) -> Unit = {},
    islandDwellMs: Long = 6000L,
    onIslandDwell: (Long) -> Unit = {},
) {
    val o = status.notificationOverview()
    val dwellOptions = listOf(
        4000L to "4 秒",
        6000L to "6 秒（推荐）",
        10000L to "10 秒",
        0L to "常驻（不自动收起）",
    )
    PageScaffold(StatusCopy.NOTIFICATIONS, onBack) {
        val context = LocalContext.current
        // 灵动岛浮窗需要的系统授权:悬浮窗(显示在其他应用上层)与读取通话状态
        var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var phoneStateGranted by remember {
            mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
        }
        val overlayLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            overlayGranted = Settings.canDrawOverlays(context)
        }
        val phoneLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            phoneStateGranted = granted
            // 授权后重启灵动岛服务,让通话监听(CallStateMonitor.start 在服务 onCreate 里)重新生效
            if (granted && islandEnabled && Settings.canDrawOverlays(context)) {
                context.stopService(Intent(context, com.nuoyi.agent.notify.DynamicIslandService::class.java))
                context.startForegroundService(Intent(context, com.nuoyi.agent.notify.DynamicIslandService::class.java))
            }
        }
        // 从系统设置页返回、或在别处改了权限后,重新读一次授权状态,避免界面一直停在「未授权」
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    overlayGranted = Settings.canDrawOverlays(context)
                    phoneStateGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                }
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        GroupCard {
            ListRow(
                NuoyiIcons.Bell, stringResource(R.string.settings_notif_master),
                if (o.appAllowed) StatusCopy.NOTIF_APP_SUBTITLE_ON else StatusCopy.NOTIF_APP_SUBTITLE_OFF,
                first = true, tone = if (o.appAllowed) TileTone.Neutral else TileTone.Bad,
                onClick = if (o.appAllowed) null else ({ onFix(Fix.AllowNotifications) }),
            ) {
                if (o.appAllowed) RowValue(stringResource(R.string.settings_allowed), ValueTone.Ok) else RowValue(StatusCopy.HEADS_UP_ACTION, ValueTone.Link)
            }
        }
        GroupLabel(stringResource(R.string.settings_task_notifs))
        GroupCard {
            ChannelRowItem(StatusCopy.ATTENTION_CHANNEL, StatusCopy.NOTIF_ATTENTION_WHEN, o.attention, Fix.OpenAttentionChannel, onFix, first = true)
            ChannelRowItem(StatusCopy.RESULT_CHANNEL, StatusCopy.NOTIF_RESULT_WHEN, o.result, Fix.OpenResultChannel, onFix)
            ChannelRowItem(StatusCopy.PROGRESS_CHANNEL, StatusCopy.NOTIF_PROGRESS_WHEN, o.progress, Fix.OpenProgressChannel, onFix)
        }
        GroupLabel("系统授权（灵动岛胶囊）")
        GroupCard {
            ListRow(
                NuoyiIcons.Shield, "显示在其他应用上层", "灵动岛浮窗需要此项；未授权时胶囊无法显示在别的 App 之上",
                first = true, tone = if (overlayGranted) TileTone.Neutral else TileTone.Bad,
                onClick = if (!overlayGranted) ({
                    overlayLauncher.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                }) else null,
            ) {
                if (overlayGranted) RowValue("已授权", ValueTone.Ok) else RowValue("去授权", ValueTone.Link)
            }
            ListRow(
                NuoyiIcons.Shield, "读取通话状态", "用于通话中在胶囊显示「通话中」与时长；关掉则通话模式不可用",
                tone = if (phoneStateGranted) TileTone.Neutral else TileTone.Bad,
                onClick = if (!phoneStateGranted) ({ phoneLauncher.launch(Manifest.permission.READ_PHONE_STATE) }) else null,
            ) {
                if (phoneStateGranted) RowValue("已授权", ValueTone.Ok) else RowValue("去授权", ValueTone.Link)
            }
        }
        GroupLabel("灵动岛胶囊")
        GroupCard {
            ListRow(
                NuoyiIcons.Bell, "启用灵动岛胶囊", "在屏幕顶部显示通知胶囊；关闭则停止浮窗服务",
                first = true, onClick = { onIslandEnabled(!islandEnabled) },
            ) {
                NuoyiSwitch(islandEnabled, onIslandEnabled, enabled = overlayGranted)
            }
            if (islandEnabled) {
                ListRow(
                    NuoyiIcons.Phone, "通话模式", "通话进行中显示「通话中 + 时长」，并可随手记要点",
                    onClick = if (phoneStateGranted) ({ onIslandCallMode(!islandCallMode) }) else null,
                ) {
                    NuoyiSwitch(islandCallMode, onIslandCallMode, enabled = phoneStateGranted)
                }
                ListRow(
                    NuoyiIcons.Mic, "空闲常驻小胶囊", "没通知时也留一个可点的小胶囊，点一下回 App（默认关，以免一直挡视线）",
                    onClick = { onIslandIdlePill(!islandIdlePill) },
                ) {
                    NuoyiSwitch(islandIdlePill, onIslandIdlePill)
                }
                ListRow(
                    NuoyiIcons.Lines, "点按展开通知卡片", "多条通知会堆叠成一个胶囊（角标显示条数）；点一下展开成卡片，可逐条查看、清除或直达对应页面。关掉则点按直接回 App",
                    onClick = { onIslandExpandCard(!islandExpandCard) },
                ) {
                    NuoyiSwitch(islandExpandCard, onIslandExpandCard)
                }
                ListRow(
                    NuoyiIcons.Spark, "贴合前摄挖孔（类灵动岛）",
                    "把胶囊对齐到挖孔的位置与宽度、允许画进挖孔区，尽量做出「岛」的视觉效果；展开卡片自动回到屏幕中间。" +
                        "注意：Android 不允许第三方 App 把界面塞进系统状态栏，这不是 iOS 那种系统级灵动岛，部分厂商仍会裁掉状态栏里的部分。" +
                        "手动拖过胶囊后暂时以你的位置为准，点「复位胶囊位置」即可恢复贴合。",
                    onClick = { onIslandHugCutout(!islandHugCutout) },
                ) {
                    NuoyiSwitch(islandHugCutout, onIslandHugCutout)
                }
                ListRow(
                    NuoyiIcons.Shield, "避开状态栏 / 挖孔区", "胶囊最低只能拖到状态栏下方，不会压住时钟或前摄；横竖屏、分屏会按比例还原位置（贴合挖孔开启时由挖孔决定位置）",
                    onClick = { onIslandAvoidStatusBar(!islandAvoidStatusBar) },
                ) {
                    NuoyiSwitch(islandAvoidStatusBar, onIslandAvoidStatusBar)
                }
                ListRow(
                    NuoyiIcons.Spark, "预览三条堆叠通知", "连发三条示例通知，点胶囊展开卡片，试堆叠、逐条清除、直达页面与拖动",
                    onClick = {
                        val t = System.currentTimeMillis()
                        NotificationCenter.post(context, NuoyiNotification(
                            id = "island-preview-$t-1",
                            title = "任务完成",
                            text = "已把通话纪要整理成 3 条待办，点这一行可直达通话纪要",
                            tone = Tone.Success,
                            openRoute = "callnotes",
                            timeoutMillis = 0,
                        ))
                        NotificationCenter.post(context, NuoyiNotification(
                            id = "island-preview-$t-2",
                            title = "需要你确认",
                            text = "点这一行可直接跳到通知与灵动岛设置页",
                            tone = Tone.Warn,
                            openRoute = OpenRoutes.NOTIFICATIONS,
                            timeoutMillis = 0,
                        ))
                        NotificationCenter.post(context, NuoyiNotification(
                            id = "island-preview-$t-3",
                            title = "灵动岛预览",
                            text = "点胶囊展开卡片 · 按住可拖动 · 长按划掉最新一条",
                            tone = Tone.Info,
                            timeoutMillis = 0,
                        ))
                    },
                ) { RowChevron() }
                ListRow(
                    NuoyiIcons.Wrench, "复位胶囊位置", "把拖到别处的胶囊恢复默认：开了贴合挖孔就回到挖孔位置，否则回到屏幕顶部居中（清掉拖动记录）",
                    onClick = {
                        FeatureFlags.get(context).setIslandPos(-1, -1)
                        if (Settings.canDrawOverlays(context)) {
                            val svc = Intent(context, com.nuoyi.agent.notify.DynamicIslandService::class.java)
                            context.stopService(svc)
                            context.startForegroundService(svc)
                        }
                    },
                ) { RowChevron() }
            }
            dwellOptions.forEachIndexed { i, (ms, label) ->
                RadioRow(
                    label, selected = islandDwellMs == ms, first = false,
                    onClick = { onIslandDwell(ms) },
                    subtitle = if (ms == 0L) "通知会一直停在屏幕顶部，直到你点开或长按划掉" else "通知到达后约 $label 自动收起",
                )
            }
        }
        GroupLabel("使用与常驻")
        GroupCard {
            ListRow(
                null, "胶囊可以拖动", "按住胶囊或卡片顶部拖到屏幕任意位置，位置会被记住；点一下展开通知卡片逐条查看，长按划掉最新一条。",
                first = true,
            ) {}
            ListRow(
                null, "胶囊不出现？", "确认上面「显示在其他应用上层」已授权，并在系统设置里把诺伊加入自启动 / 后台运行白名单，避免被省电策略杀掉。",
            ) {}
        }
        if (!overlayGranted) {
            FooterNote("还没授权「显示在其他应用上层」：灵动岛胶囊无法显示在别的 App 之上，请先点上面「去授权」。")
        }
        if (!phoneStateGranted) {
            FooterNote("还没授权「读取通话状态」：通话模式不可用。授权后胶囊服务会自动重启以生效。")
        }
        FooterNote(StatusCopy.NOTIF_FOOTER)
    }
}

@Composable
private fun ChannelRowItem(title: String, whenText: String, row: ChannelRow, fix: Fix, onFix: (Fix) -> Unit, first: Boolean = false) {
    val (text, tone) = StatusCopy.channelLevelValue(row)
    ListRow(
        NuoyiIcons.Bell, title, whenText, first = first,
        tone = if (row.warn) TileTone.Warn else TileTone.Neutral,
        onClick = { onFix(fix) },
    ) {
        RowValue(text, tone)
        RowChevron()
    }
}

// ---------- 任务运行 ----------

/** 并行任务的设置:用户设的上限 [max](1–3),和设备此刻允许的 [limit]。 */
data class ParallelSetting(val max: Int, val limit: ParallelLimit)

/**
 * 设置 › 任务运行:自主模式默认关;屏幕常亮、被操作的 App 静音默认开,可以关;[parallel] 不为空(实验开关打开了)时多一行同时进行的任务数。
 * 下面一组是操作节奏:快 / 标准(默认) / 像人,选了下一个任务起生效。
 */
@Composable
fun DuringWorkScreen(
    keepScreenOn: Boolean, onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean, onMuteTarget: (Boolean) -> Unit,
    parallel: ParallelSetting?, onParallelMax: (Int) -> Unit, onBack: () -> Unit,
    autonomous: Boolean = false, onAutonomous: (Boolean) -> Unit = {},
    operatingPace: OperatingPace = OperatingPace.DEFAULT, onOperatingPace: (OperatingPace) -> Unit = {},
    selfConfig: Boolean = false, onSelfConfig: (Boolean) -> Unit = {},
    stepLimit: Int? = null, onStepLimit: (Int?) -> Unit = {},
    tokenBudget: Long? = null, onTokenBudget: (Long?) -> Unit = {},
) {
    var editingLimit by remember { mutableStateOf(false) }
    var customLimit by remember { mutableStateOf("") }
    var editingTokenBudget by remember { mutableStateOf(false) }
    var customTokenBudget by remember { mutableStateOf("") }
    PageScaffold(stringResource(R.string.settings_during_work), onBack) {
        GroupLabel(AutonomyCopy.GROUP)
        GroupCard {
            ListRow(null, AutonomyCopy.TITLE, AutonomyCopy.SUBTITLE, first = true, onClick = { onAutonomous(!autonomous) }) {
                NuoyiSwitch(autonomous, onAutonomous, Modifier.testTag("work-autonomous"))
            }
        }
        GroupCard {
            ListRow(NuoyiIcons.Sun, stringResource(R.string.settings_keep_on), stringResource(R.string.settings_keep_on_sub), first = true, onClick = { onKeepScreenOn(!keepScreenOn) }) {
                NuoyiSwitch(keepScreenOn, onKeepScreenOn, Modifier.testTag("work-keep-screen-on"))
            }
            ListRow(NuoyiIcons.Mute, stringResource(R.string.settings_mute), stringResource(R.string.settings_mute_sub), onClick = { onMuteTarget(!muteTarget) }) {
                NuoyiSwitch(muteTarget, onMuteTarget, Modifier.testTag("work-mute-target"))
            }
            ListRow(
                NuoyiIcons.Clock, stringResource(R.string.settings_step_limit),
                stepLimit?.let { trPlural(R.plurals.chat_steps_n, it) }
                    ?: stringResource(R.string.settings_step_limit_off),
                // 当前是自己填的数时,输入框里带着它
                onClick = { customLimit = stepLimit?.takeIf { it !in STEP_LIMIT_PRESETS }?.toString().orEmpty(); editingLimit = true },
            )
            ListRow(
                NuoyiIcons.Clock, stringResource(R.string.settings_task_token_budget),
                tokenBudget?.let(::tokenBudgetNumber) ?: stringResource(R.string.settings_token_budget_unlimited),
                onClick = {
                    customTokenBudget = tokenBudget?.takeIf { it !in TokenBudgetLimits.PRESETS }?.toString().orEmpty()
                    editingTokenBudget = true
                },
            )
            parallel?.let { ParallelRow(it, onParallelMax) }
        }
        if (editingLimit) StepLimitDialog(
            current = stepLimit,
            custom = customLimit,
            onCustom = { customLimit = it.filter(Char::isDigit).take(3) },
            onPick = { onStepLimit(it); editingLimit = false },
            onDismiss = { editingLimit = false },
        )
        if (editingTokenBudget) TokenBudgetDialog(
            title = stringResource(R.string.settings_task_token_budget),
            description = stringResource(R.string.settings_task_token_budget_sub),
            current = tokenBudget,
            custom = customTokenBudget,
            onCustom = { customTokenBudget = it.filter(Char::isDigit).take(9) },
            onPick = { onTokenBudget(it); editingTokenBudget = false },
            onDismiss = { editingTokenBudget = false },
        )
        // 允许 Nuoyi 改自己的设置:默认关,打开后任务里才有 settings_list、settings_set
        GroupCard {
            ListRow(
                NuoyiIcons.Spark, stringResource(R.string.settings_self_config),
                stringResource(R.string.settings_self_config_sub),
                first = true,
                onClick = { onSelfConfig(!selfConfig) },
            ) {
                NuoyiSwitch(selfConfig, onSelfConfig, Modifier.testTag("work-self-config"))
            }
        }
        GroupLabel(stringResource(R.string.settings_pace))
        GroupCard {
            OperatingPace.entries.forEachIndexed { i, p ->
                RadioRow(paceLabel(p), selected = p == operatingPace, onClick = { onOperatingPace(p) }, subtitle = paceDetail(p), first = i == 0)
            }
        }
        FooterNote(stringResource(R.string.settings_pace_foot))
    }
}

/** 步数上限的几个现成选项;别的数在输入框里填。 */
private val STEP_LIMIT_PRESETS = listOf(30, 60, 120, 240)

@Composable
private fun StepLimitDialog(
    current: Int?,
    custom: String,
    onCustom: (String) -> Unit,
    onPick: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val customValue = custom.toIntOrNull()?.takeIf { it in 1..FeatureFlags.MAX_TASK_STEP_LIMIT }
    NuoyiDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_step_limit)) },
        text = {
            Column {
                Text(stringResource(R.string.settings_step_limit_sub), Modifier.padding(bottom = 8.dp))
                RadioRow(stringResource(R.string.settings_step_limit_off), selected = current == null, onClick = { onPick(null) }, first = true)
                STEP_LIMIT_PRESETS.forEach { n ->
                    RadioRow(trPlural(R.plurals.chat_steps_n, n), selected = current == n, onClick = { onPick(n) })
                }
                OutlinedTextField(
                    value = custom, onValueChange = onCustom, singleLine = true,
                    label = { Text(stringResource(R.string.settings_step_limit_custom)) },
                    isError = custom.isNotEmpty() && customValue == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (custom.isNotEmpty() && customValue == null) {
                    Text(stringResource(R.string.settings_step_limit_invalid), Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { customValue?.let(onPick) }, enabled = customValue != null) {
                Text(stringResource(R.string.settings_step_limit_custom_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun paceLabel(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast
        OperatingPace.STANDARD -> R.string.settings_pace_standard
        OperatingPace.HUMAN -> R.string.settings_pace_human
    },
)

@Composable
private fun paceDetail(p: OperatingPace): String = stringResource(
    when (p) {
        OperatingPace.FAST -> R.string.settings_pace_fast_detail
        OperatingPace.STANDARD -> R.string.settings_pace_standard_detail
        OperatingPace.HUMAN -> R.string.settings_pace_human_detail
    },
)

/** 同时进行几个任务:设备不支持时灰掉、写明原因,上限固定为 1。 */
@Composable
private fun ParallelRow(p: ParallelSetting, onMax: (Int) -> Unit) {
    val supported = p.limit.reason == null
    ListRow(
        NuoyiIcons.Phone, stringResource(R.string.settings_parallel),
        subtitle = p.limit.reason ?: stringResource(R.string.settings_parallel_sub),
        titleColor = if (supported) NuoyiTheme.colors.ink else NuoyiTheme.colors.ink3,
    ) {
        if (!supported) {
            RowValue(trPlural(R.plurals.settings_count_tasks, 1))
            return@ListRow
        }
        Text(
            "−", Modifier.clickable(enabled = p.max > 1) { onMax(p.max - 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max > 1) NuoyiTheme.colors.accent else NuoyiTheme.colors.ink3,
        )
        RowValue(trPlural(R.plurals.settings_count_tasks, p.max))
        Text(
            "+", Modifier.clickable(enabled = p.max < 3) { onMax(p.max + 1) }.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 18.sp, color = if (p.max < 3) NuoyiTheme.colors.accent else NuoyiTheme.colors.ink3,
        )
    }
}

// ---------- 悬浮胶囊 ----------

/** 设置 › 悬浮胶囊的文字。 */
object CapsuleSettingsCopy {
    val TITLE: String get() = tr(R.string.capsule_settings_title)
    val SUBTITLE: String get() = tr(R.string.capsule_settings_sub)
    val FOOTNOTE: String get() = tr(R.string.capsule_settings_foot)
    /** 系统可能关掉后台的 App,胶囊跟着不见:请用户在系统设置里放行。 */
    val BACKGROUND: String get() = tr(R.string.capsule_settings_background)
    val IDLE_OPACITY: String get() = tr(R.string.capsule_idle_opacity)
    val IDLE_OPACITY_DETAIL: String get() = tr(R.string.capsule_idle_opacity_detail)

    fun label(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always)
    }

    fun detail(mode: CapsuleMode): String = when (mode) {
        CapsuleMode.Off -> tr(R.string.capsule_mode_off_detail)
        CapsuleMode.DuringTasks -> tr(R.string.capsule_mode_tasks_detail)
        CapsuleMode.Always -> tr(R.string.capsule_mode_always_detail)
    }
}

/** 设置 › 悬浮胶囊:关闭 / 有任务时显示(默认) / 一直显示,选了当场生效。 */
@Composable
fun CapsuleScreen(
    mode: CapsuleMode,
    onMode: (CapsuleMode) -> Unit,
    onBack: () -> Unit,
    idleOpacity: CapsuleIdleOpacity = CapsuleIdleOpacity.DEFAULT,
    onIdleOpacity: (CapsuleIdleOpacity) -> Unit = {},
) {
    PageScaffold(CapsuleSettingsCopy.TITLE, onBack) {
        GroupCard {
            CapsuleMode.entries.forEachIndexed { i, m ->
                RadioRow(
                    CapsuleSettingsCopy.label(m), selected = m == mode, onClick = { onMode(m) },
                    subtitle = CapsuleSettingsCopy.detail(m), first = i == 0,
                )
            }
        }
        GroupLabel(CapsuleSettingsCopy.IDLE_OPACITY)
        GroupCard {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                ListRow(
                    NuoyiIcons.Photo, CapsuleSettingsCopy.IDLE_OPACITY, CapsuleSettingsCopy.IDLE_OPACITY_DETAIL,
                    first = true, titleColor = if (mode == CapsuleMode.Off) NuoyiTheme.colors.ink3 else androidx.compose.ui.graphics.Color.Unspecified,
                ) { RowValue("${idleOpacity.percent}%") }
                Slider(
                    value = CapsuleIdleOpacity.entries.indexOf(idleOpacity).toFloat(),
                    onValueChange = {
                        val index = it.roundToInt().coerceIn(CapsuleIdleOpacity.entries.indices)
                        onIdleOpacity(CapsuleIdleOpacity.entries[index])
                    },
                    valueRange = 0f..3f,
                    steps = 2,
                    enabled = mode != CapsuleMode.Off,
                    modifier = Modifier.testTag("capsule-idle-opacity"),
                )
            }
        }
        FooterNote(CapsuleSettingsCopy.FOOTNOTE)
        FooterNote(CapsuleSettingsCopy.BACKGROUND)
    }
}

/** 设置 › 语言:跟随系统、简体中文、繁體中文、English;选了马上换,界面跟着重建。 */
@Composable
fun LanguageScreen(language: AppLanguage, onLanguage: (AppLanguage) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    // 跟着设置走:任务里改了外观,这页也对得上
    val themeMode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    PageScaffold(stringResource(R.string.appearance_title), onBack) {
        // 语言和外观放在同一页:都是「界面长什么样」
        GroupLabel(stringResource(R.string.appearance_group))
        GroupCard {
            ThemeMode.entries.forEachIndexed { idx, m ->
                RadioRow(
                    appearanceLabel(m), selected = m == themeMode, first = idx == 0,
                    subtitle = if (m == ThemeMode.System) stringResource(R.string.appearance_system_detail) else null,
                    onClick = {
                        // 界面跟着设置当场换颜色,不重建,页面和滚动位置都不丢
                        if (m != themeMode) flags.setThemeMode(m)
                    },
                )
            }
        }
        GroupLabel(stringResource(R.string.language_title))
        GroupCard {
            AppLanguage.entries.forEachIndexed { i, l ->
                RadioRow(
                    languageLabel(l), selected = l == language, onClick = { if (l != language) onLanguage(l) },
                    subtitle = if (l == AppLanguage.System) stringResource(R.string.language_system_detail) else null, first = i == 0,
                )
            }
        }
    }
}

/** 语言的名字:每种语言总用它自己的写法(日本語、Español…),「跟随系统」按当前语言写。 */
@Composable
fun languageLabel(l: AppLanguage): String = stringResource(
    when (l) {
        AppLanguage.System -> R.string.language_system
        AppLanguage.SimplifiedChinese -> R.string.language_simplified_chinese
        AppLanguage.TraditionalChinese -> R.string.language_traditional_chinese
        AppLanguage.Arabic -> R.string.language_arabic
        AppLanguage.English -> R.string.language_english
        AppLanguage.Japanese -> R.string.language_japanese
        AppLanguage.Korean -> R.string.language_korean
        AppLanguage.Spanish -> R.string.language_spanish
        AppLanguage.Portuguese -> R.string.language_portuguese
        AppLanguage.Russian -> R.string.language_russian
        AppLanguage.Indonesian -> R.string.language_indonesian
    },
)

// ---------- 隐私与数据 ----------

/** 设置 › 网站登录。 */
@Composable
fun WebSitesScreen(state: WebSitesState, onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_web_logins), onBack) { WebSitesSection(state, labeled = false) }
}

// ---------- 高级 ----------

/** 设置 › 文件与计算环境:允许脚本联网、网络代理、已用空间(按对话清理工作区)、重置运行环境。 */
@Composable
fun SandboxScreen(onBack: () -> Unit) {
    PageScaffold(stringResource(R.string.settings_sandbox), onBack) { AdvancedSettings() }
}

/** 设置 › 记住的操作路线:[count] 条,可以全部清除(先确认);[count] 为空表示还没读出来。 */
@Composable
fun RoutesScreen(count: Int?, onClear: () -> Unit, onBack: () -> Unit) {
    var confirmClear by remember { mutableStateOf(false) }
    PageScaffold(stringResource(R.string.settings_routes), onBack) {
        GroupCard {
            ListRow(
                NuoyiIcons.Route, stringResource(R.string.routes_saved),
                if (count == null) stringResource(R.string.loading) else trPlural(R.plurals.routes_count, count), first = true,
            ) {
                if (count != null && count > 0) Text(
                    stringResource(R.string.routes_clear_all), Modifier.clickable { confirmClear = true }.padding(4.dp),
                    style = NuoyiType.Value, color = NuoyiTheme.colors.bad,
                )
            }
        }
    }
    if (confirmClear) {
        NuoyiDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.routes_clear_title)) },
            text = { Text(stringResource(R.string.routes_clear_body)) },
            confirmButton = { TextButton({ confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) } },
            dismissButton = { TextButton({ confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** 设置 › 恢复与修复:手机操作的几个授权开关,下面是兼容性自检的入口。 */
@Composable
fun RecoveryScreen(onBack: () -> Unit, onOpenSelfCheck: () -> Unit = {}) {
    PageScaffold(stringResource(R.string.settings_recovery), onBack) {
        RecoverySwitches()
        GroupCard {
            ListRow(
                NuoyiIcons.Bug, stringResource(R.string.selfcheck_title), stringResource(R.string.selfcheck_row_sub), first = true,
                onClick = onOpenSelfCheck,
            ) { RowChevron() }
        }
    }
}

/** 外观的当前说法(设置页和这一行都用)。 */
fun appearanceLabel(mode: ThemeMode): String = tr(
    when (mode) {
        ThemeMode.System -> R.string.appearance_system
        ThemeMode.Light -> R.string.appearance_light
        ThemeMode.Dark -> R.string.appearance_dark
    },
)

// ---------- 吉祥物 ----------

/**
 * 设置 › 吉祥物:上传一张自己生成的图片(去豆包 / 即梦 / 通义万相等平台生成,见 docs/mascot-prompts.md)替换内置形象,
 * 或一键恢复默认。上传的图存到 App 私有目录,所有用吉祥物的地方都会改画它。
 */
@Composable
fun MascotScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { MascotStore.get(context) }
    val path by store.path.collectAsState()
    val bitmap = remember(path) { if (store.useCustom && path != null) store.loadBitmap()?.asImageBitmap() else null }
    var enabled by remember { mutableStateOf(store.useCustom) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            store.setCustomPath(context, uri)
            enabled = true
        } else {
            enabled = false
        }
    }
    PageScaffold(stringResource(R.string.mascot_title), onBack) {
        GroupLabel(stringResource(R.string.mascot_title))
        GroupCard {
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (bitmap != null) {
                    Image(bitmap = bitmap, contentDescription = null, Modifier.size(120.dp), contentScale = ContentScale.Fit)
                } else {
                    NuoyiMascot(MascotMood.Idle, size = 120.dp)
                }
            }
        }
        GroupLabel(stringResource(R.string.mascot_settings))
        GroupCard {
            ListRow(
                NuoyiIcons.Spark, stringResource(R.string.mascot_use_custom), stringResource(R.string.mascot_use_custom_sub),
                first = true,
            ) {
                NuoyiSwitch(checked = enabled, onCheckedChange = { on ->
                    enabled = on
                    if (on) launcher.launch("image/*") else store.clear()
                })
            }
            if (store.useCustom) {
                ListRow(
                    NuoyiIcons.Wrench, stringResource(R.string.mascot_reset), first = false,
                ) {
                    Text(
                        stringResource(R.string.mascot_reset_action),
                        Modifier.clickable { store.clear(); enabled = false }.padding(4.dp),
                        style = NuoyiType.Value, color = NuoyiTheme.colors.bad,
                    )
                }
            }
        }
        Text(
            stringResource(R.string.mascot_hint),
            Modifier.padding(16.dp, 8.dp),
            style = NuoyiType.Caption, color = NuoyiTheme.colors.ink3,
        )
    }
}
