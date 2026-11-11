package com.nuoyi.agent

import com.nuoyi.agent.record.DetailedTraces
import com.nuoyi.agent.R
import com.nuoyi.agent.i18n.ResourceTexts
import com.nuoyi.agent.i18n.UiText
import com.nuoyi.agent.i18n.AppLanguage
import com.nuoyi.agent.i18n.tr
import com.nuoyi.agent.ui.ChatCopy
import android.app.Application
import android.net.Uri
import android.util.Log
import com.nuoyi.agent.chat.AndroidBranchWorkspace
import com.nuoyi.agent.chat.ConversationDeleter
import com.nuoyi.agent.chat.ConversationStore
import com.nuoyi.agent.chat.SharedDraftStore
import com.nuoyi.agent.core.cap.sandbox.SandboxStatus
import com.nuoyi.agent.cap.sandbox.AndroidSandbox
import com.nuoyi.agent.cap.sandbox.Attachments
import com.nuoyi.agent.ui.AttachFiles
import com.nuoyi.agent.cap.sandbox.ShareExporter
import com.nuoyi.agent.cap.web.WebFiles
import com.nuoyi.agent.cap.sandbox.RuntimePreparer
import com.nuoyi.agent.cap.sandbox.SandboxDirs
import com.nuoyi.agent.cap.sandbox.SandboxState
import com.nuoyi.agent.cap.sandbox.SkillsSync
import com.nuoyi.agent.capsule.AppForeground
import com.nuoyi.agent.capsule.CapsuleController
import com.nuoyi.agent.confirm.ConfirmationNotifier
import com.nuoyi.agent.memory.AppLearningActions
import com.nuoyi.agent.memory.MemoryRuntime
import com.nuoyi.agent.settings.ApprovalControl
import com.nuoyi.agent.settings.ApprovalStore
import com.nuoyi.agent.settings.FeatureFlags
import com.nuoyi.agent.cap.phone.PhoneServices
import com.nuoyi.agent.replay.FileRouteStore
import com.nuoyi.agent.settings.ProviderStore
import com.nuoyi.agent.core.run.Outbox
import com.nuoyi.agent.core.run.RunStore
import com.nuoyi.agent.core.usage.UsageLedger
import com.nuoyi.agent.task.FileOutboxStore
import com.nuoyi.agent.task.FileRunStateStore
import com.nuoyi.agent.task.LocalChannel
import com.nuoyi.agent.task.RunRecovery
import com.nuoyi.agent.task.TaskRecovery
import com.nuoyi.agent.task.TriggerOutcomes
import com.nuoyi.agent.task.TaskService
import com.nuoyi.agent.task.TaskCenterState
import com.nuoyi.agent.task.TaskRun
import com.nuoyi.agent.teach.TeachRuntime
import com.nuoyi.agent.notify.DynamicIslandService
import com.nuoyi.agent.notify.NotificationCenter
import com.nuoyi.agent.notify.NuoyiNotification
import com.nuoyi.agent.notify.Tone
import com.nuoyi.agent.core.agent.TaskState
import com.nuoyi.agent.ui.ChatViewModel
import com.nuoyi.agent.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.nuoyi.agent.cap.web.cdp.WebDevtools
import com.nuoyi.agent.cap.web.WebOwner
import com.nuoyi.agent.cap.web.SiteRegistry
import com.nuoyi.agent.cap.sandbox.SandboxOwner
import com.nuoyi.agent.core.workspace.SandboxTree
import com.nuoyi.agent.task.TaskEnvironment
import com.nuoyi.agent.task.ResourceScheduler
import com.nuoyi.agent.task.RemoteTaskHub
import com.nuoyi.agent.task.FileChildRunStore
import com.nuoyi.agent.task.TriggerRuntime
import android.content.Intent

/**
 * App 容器:进程里共用的服务都在这里建一份,经各自的 Owner 接口交给安卓入口(界面、服务、广播、provider),
 * 其余代码经构造参数拿到。助手进程(HelperMain、ShellService)没有这个对象,不用这里的任何服务。
 */
class NuoyiApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 手机能力的服务:内置助手、共用的 shell 连接、配对、「去设置」提示。 */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** 网页标签的 DevTools 会话入口:整个进程一份;连不上时经内置助手代连。 */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** 各任务的标签池共用的标签总数上限。 */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }

    /** 微信渠道：凭证、监听和渠道适配器整个进程一份；任务仍由 TaskService 执行。 */
    val wechat by lazy {
        com.nuoyi.agent.wechat.WechatRuntime(this,
            com.nuoyi.agent.wechat.WechatStore(java.io.File(filesDir, "wechat"),
                com.nuoyi.agent.settings.KeystoreKeyCipher("nuoyi-wechat-account")))
    }

    /** 本机授权的主屏唤醒/解锁；电脑连接和无人值守定时任务共用同一条确定性流程。 */
    val deviceAccess by lazy { com.nuoyi.agent.device.AndroidDeviceAccess(this) { phone.hub.shell.value } }

    /**
     * 电脑连接(MCP):开关、访问令牌(和服务商的 Key 一样加密存)、监听。局域网那种默认关着;
     * USB 那种由电脑经 adb 打开,只到这次关机为止(开机编号和 shell 里 `settings get global boot_count` 读到的一样)。
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        val settings = com.nuoyi.agent.settings.McpSettings(settingsDir, com.nuoyi.agent.settings.KeystoreKeyCipher("nuoyi-mcp-token"))
        val history = com.nuoyi.agent.task.PhoneHistory(conversations, com.nuoyi.agent.record.RunStore(this), detailedTraces.store,
            allowed = { settings.historyEnabled.value }, live = { remoteTasks.list(100) })
        com.nuoyi.agent.mcp.McpRuntime(this, settings,
            com.nuoyi.agent.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.nuoyi.agent.core.mcp.McpUsb.INFO_FILE) } },
                migrateTaskOwner = remoteTasks::migrateUsbOwner),
            remoteTasks, deviceAccess,
            com.nuoyi.agent.settings.RelaySettings(settingsDir, com.nuoyi.agent.settings.KeystoreKeyCipher("nuoyi-remote-relay")),
            history)
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }

    /** 外部 MCP server 的设置(与「连接电脑」的 inbound 设置分开)。 */
    val mcpServers by lazy {
        com.nuoyi.agent.settings.McpServerStore(
            java.io.File(filesDir, "settings"),
            com.nuoyi.agent.settings.KeystoreKeyCipher("nuoyi-mcp-servers"),
            stdio = com.nuoyi.agent.core.cap.mcp.McpStdioLauncher { workspace, argv ->
                val process = AndroidSandbox(this, SandboxState.get(this)).openProcess(workspace, argv, null)
                object : com.nuoyi.agent.core.cap.mcp.McpStdioProcess {
                    override val stdin get() = process.stdin
                    override val stdout get() = process.stdout
                    override val stderr get() = process.stderr
                    override fun close() = process.close()
                }
            },
        )
    }
    override val tabBudget get() = resources.tabBudget

    /** 任务结束后留在后台接着运行的那一页;关掉时在来源对话那一轮记下原因(对话页那一轮下面显示「后台网页已关闭」)。 */
    override val pageKeeper: com.nuoyi.agent.cap.web.PageKeeper by lazy {
        com.nuoyi.agent.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("NuoyiApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** 记忆、个人技能、开关与结果卡片提示。 */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** 本机的 token 用量账本:经模型配置建出的每个模型的每次调用都记在这里。 */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** 模型配置与 Key;建出的模型都套上计量,记到 [usage]。 */
    override val providers: ProviderStore by lazy {
        ProviderStore.create(this, usage, "Nuoyi/${BuildConfig.VERSION_NAME}", usage::providerTokensToday)
    }

    /** 语音转文字的选择:整个进程一份,设置页改了输入框立刻看得到(两份实例互相看不到对方的 set)。 */
    val speech: com.nuoyi.agent.voice.SpeechSettings by lazy { com.nuoyi.agent.voice.SpeechSettings.get(this) }

    /** 语音输入的入口,输入框和悬浮胶囊共用。 */
    val voice: com.nuoyi.agent.voice.VoiceInput by lazy { com.nuoyi.agent.voice.VoiceInput(this, speech, providers) }

    /** 用 ChatGPT 登录:跑在 App 的作用域里,期间开前台服务撑住进程(见 [com.nuoyi.agent.settings.ChatGptSignIns])。 */
    val chatGptSignIn by lazy {
        com.nuoyi.agent.settings.ChatGptSignIns(scope,
            keepAlive = { com.nuoyi.agent.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("NuoyiChatGpt", msg, e) })
    }

    /** 经验路线。 */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** 异常退出后留下的系统改动的恢复;在跑的任务登记在这里。 */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** 运行账本与送达账本:整个进程一份,任务服务和启动时的恢复共用(同一个文件不能有两份实例)。 */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** 同对话下一件任务的持久队列:用户明确排队的和引导 fallback 都在这里,任务服务与界面共用。 */
    val followUpQueue: com.nuoyi.agent.task.FollowUpQueue by lazy {
        com.nuoyi.agent.task.FollowUpQueue(
            com.nuoyi.agent.task.FileFollowUpQueueStore(java.io.File(filesDir, "followups-v1.json")),
        )
    }

    /** 没结束的运行的善后(标中断、补送、告知):进程里只做一次,App 启动和任务服务启动都调它。 */
    val runRecovery: RunRecovery by lazy {
        val app = this
        val local = LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId())
        }
        val labels = com.nuoyi.agent.ui.ToolLabels(com.nuoyi.agent.core.assembly.knownTools())
        RunRecovery(runStore, outbox, { id -> if (id == LocalChannel.ID) local else wechat.channelFor(id) }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope, records = com.nuoyi.agent.record.RunStore(app).reader(), label = labels::label,
            appLabel = { com.nuoyi.agent.confirm.ConfirmationNotifier.appLabel(app, it) })
    }

    /** 站点登记表(登录过的网站等):设置页和标签池共用。 */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/` 的树锁:安装、重置、命令、下载、附件都在它的许可里写。 */
    override val sandboxTree = SandboxTree()

    /** 任务从 App 拿的模型来源、评测钩子、屏幕画面的开关。 */
    override val tasks = TaskEnvironment()

    /** 沙箱状态:安装服务更新的,设置页与 Agent 看到的是同一份。 */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** 对话记录:整个进程一份,任务服务、聊天界面、结果卡片的按钮共用。 */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** 最近一次系统分享尚未发送的草稿和它的私有图片副本。 */
    val sharedDrafts: SharedDraftStore by lazy { SharedDraftStore(java.io.File(filesDir, "shared-drafts")) }

    /** 删对话(侧栏长按):停掉它的当前任务并删工作区,保留定时任务。 */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            forgetRemoteTasks = { id ->
                if (com.nuoyi.agent.core.remote.RemoteTaskService.isConversation(id)) remoteTasks.forget(id)
            },
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            deleteWorkspace = { id ->
                // 工作区里有附件、用户发来的屏幕、任务写的文件;沙箱里的命令碰得到这里,和命令、导入、重置互斥着删
                SandboxDirs(app).deleteWorkspace(id)
            },
        )
    }

    /** 用户发来的屏幕(跟着各自的对话存在工作区里)。 */
    val userScreens: com.nuoyi.agent.chat.UserScreenFiles by lazy { com.nuoyi.agent.chat.UserScreenFiles(this) }

    /** 全局的询问 / 自主:整个进程一份,设置页和各任务共用。 */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.nuoyi.agent.settings.AppAccessStore by lazy {
        com.nuoyi.agent.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** 全局的询问 / 自主和各任务自己的那一份;开发者选项里的「自主模式」经它改。 */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** 各 Run 的队列与展示状态跟 App 进程走，Service 重建时继续使用同一份。 */
    val taskState = TaskCenterState()

    /** Nuoyi 自己的界面此刻看不看得见。 */
    val foreground = AppForeground()

    /** 检查更新:查到的新版本跟进程走,侧栏、设置首页、关于页的红点看同一份。 */
    val updates: com.nuoyi.agent.about.UpdateChecker by lazy {
        com.nuoyi.agent.about.UpdateChecker(java.io.File(filesDir, "update-v1.json"), BuildConfig.VERSION_NAME,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList())
    }

    internal val apkUpdater by lazy { com.nuoyi.agent.about.ApkUpdater(this) }
    internal val automaticUpdates by lazy { com.nuoyi.agent.about.AutomaticUpdates(this, apkUpdater) }

    /** 浮在别的 App 上的悬浮胶囊。 */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** 人工示范。onCreate 里建好；在那之前的无障碍事件不采集。 */
    lateinit var teach: TeachRuntime
        private set
    val teachReady: Boolean get() = ::teach.isInitialized

    /** 对话页的状态跟进程走:界面关了再打开,正在跑的任务的步骤还在。 */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun enqueueFollowUp(conversationId: String, messageId: String, text: String, startIfIdle: Boolean) =
                    TaskService.enqueueFollowUp(app, conversationId, messageId, text, startIfIdle = startIfIdle)
                override fun cancelFollowUp(conversationId: String, messageId: String): Boolean =
                    app.followUpQueue.cancel(messageId) != null
                override fun drainFollowUps(conversationId: String) = TaskService.drainFollowUps(app, conversationId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // 按下那一刻就换成该对话任务的编号:之后切了对话、或那个任务换了,动作也不会落到别的任务上
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
                override fun unmuteIn(conversationId: String) { taskState.run(conversationId)?.unmute() }
            },
            conversations,
            followUps = followUpQueue.changes,
            serviceBusy = taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // 虚拟屏上打开过 App,或在用内置浏览器:画中画才有画面可看(只建了空屏的不算)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // 按对话取它自己的任务,不用服务对外那一份(并行时那是显示中的别的任务)
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // 附件进对话的工作区,不需要运行环境已准备好:设备支持就给附件按钮
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(id), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(id) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = { id -> followUpQueue.dropConversation(id); conversationDeleter.delete(id) },
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.nuoyi.agent.core.cap.web.KeptPageClose.User) },
            branchWorkspace = AndroidBranchWorkspace(app),
            // 标题后面加当前界面语言的「分支」后缀;源标题太长时先截短,后缀留着
            branchTitle = { base -> ChatCopy.branchTitle(base, tr(R.string.chat_branch_title_suffix)) },
            sharedDrafts = sharedDrafts,
            sharedDraftUri = { name ->
                sharedDrafts.imageFile(name)?.let { file ->
                    androidx.core.content.FileProvider.getUriForFile(app, com.nuoyi.agent.chat.AttachFilesProvider.authority(app.packageName), file).toString()
                }.orEmpty()
            },
            teaching = { teach.blocksTasks() },
            onTeachingBlocked = {
                android.widget.Toast.makeText(app, tr(R.string.teach_blocked_task), android.widget.Toast.LENGTH_LONG).show()
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // 上次进程在复制或绑定分享草稿途中退出时留下的临时文件;目录至多保留一份有效草稿。
        runCatching { sharedDrafts.cleanup() }
        // 界面以外的文字(通知、服务、悬浮胶囊)按 App 语言取
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        teach = TeachRuntime(this, taskState)
        teach.recover()
        registerActivityLifecycleCallbacks(foreground)
        val flags = FeatureFlags.get(this)
        // 用户点了「完全退出」:进程可能被无障碍服务重新拉起。胶囊照样接线(开关按退出算关着,见 CapsuleController),
        // 但运行环境准备和没结束的运行的善后先不做,免得刚退出就冒出一条通知;用户下次打开 Nuoyi 时补上(见 [resumeAfterQuit])。
        val quit = flags.quitRequested()
        // 设置 › 网络代理:Nuoyi 自己的连接从第一个请求起就按它走;SOCKS 代理的账号密码经全进程的认证器给
        com.nuoyi.agent.settings.NetProxyStore.get(this)
        com.nuoyi.agent.core.net.NetProxy.shared.installSocksAuth()
        // 内置浏览器:设了代理才给 WebView 设上(读设置要解密,放到后台;没设代理不为此加载 WebView)
        scope.launch(Dispatchers.IO) { runCatching { com.nuoyi.agent.cap.web.WebProxy.apply(this@NuoyiApp) } }
        capsule.start()
        // 任务跑完(成功/失败/取消)时发一条通知:进系统通知栏,也进屏幕顶部的灵动岛胶囊。
        observeTaskNotifications()
        // 有「显示在其他应用上层」权限就启动灵动岛;没有则胶囊不显示,其余功能不受影响。
        // 灵动岛胶囊的停留时长跟着设置走,改了实时生效。
        NotificationCenter.defaultDwellMs = flags.islandDwellMs()
        scope.launch { flags.islandDwellChanges().collect { NotificationCenter.defaultDwellMs = it } }
        val islandWanted = flags.islandEnabled()
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        val phoneOk = checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (overlayOk && islandWanted) {
            runCatching { startForegroundService(Intent(this, DynamicIslandService::class.java)) }
        }
        // 首次缺权限时主动提示一次:否则用户不进设置页,根本不知道胶囊为什么没出现
        if (islandWanted && (!overlayOk || !phoneOk) && !flags.islandPermHintShown()) {
            flags.setIslandPermHintShown(true)
            val missing = mutableListOf<String>()
            if (!overlayOk) missing += "显示在其他应用上层"
            if (!phoneOk) missing += "读取通话状态"
            scope.launch(Dispatchers.Main) {
                runCatching {
                    NotificationCenter.post(this@NuoyiApp, NuoyiNotification(
                        id = "island-perm-hint-${System.currentTimeMillis()}",
                        title = "灵动岛胶囊还没生效",
                        text = "缺少「${missing.joinToString("、")}」权限，点这条通知可直接到设置里开启",
                        tone = Tone.Warn,
                        timeoutMillis = 0,
                        openRoute = com.nuoyi.agent.ui.OpenRoutes.NOTIFICATIONS,
                    ))
                }
            }
        }
        // 内置助手的真实现,并开始看着助手(Nuoyi 进程起来后,还活着的助手会自己交接 Binder)
        phone.shellManager.start()
        // 连接一可用就补上默认打开的几项能力
        phone.recovery.start()
        // 告诉内置助手:Nuoyi 被杀后要不要把它拉起来(胶囊一直显示或有任务在跑时才要;退出期间都不要)
        com.nuoyi.agent.cap.phone.setup.RestoreWishSync(
            phone.hub, flags.capsuleModeChanges(), taskState.busy, phone.recoverySettings.consent,
            phone.recovery.accessibility, flags.quitChanges(),
        ).start()
        // 上次异常退出时留下的改动(App 被静音)在这里恢复
        scope.launch { taskRecovery.recover() }
        // 上次进程被杀时没结束的运行:现在就标为中断并告知,不等任务服务起来
        if (!quit) runRecovery.recover().let { if (it.isNotEmpty()) Log.w("NuoyiTask", "${it.size} runs interrupted by process restart") }
        // 用户已绑定并打开微信渠道时恢复监听；完全退出期间由各运行入口统一拒绝补起
        wechat.ensureRunning()
        // 先对一下设备时区;退出期间不排闹钟,重新打开时再补排
        if (!quit) scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.syncTimeZone(this@NuoyiApp); TriggerRuntime.rearm(this@NuoyiApp) } }
        // 给电脑跑的「经 USB 连接」脚本;顺带清掉上次开机留下的 USB 连接信息
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("NuoyiMcp", "cannot write USB script", it) } }
        // 上次导出写到一半被杀留下的空壳条目
        scope.launch { runCatching { ShareExporter(this@NuoyiApp).cleanPending() } }
        // 上次进程留下的网页上传副本
        scope.launch { runCatching { WebFiles.clearStale(this@NuoyiApp, SandboxDirs(this@NuoyiApp).gate) } }
        // 更新已经装上后,关于页下好的安装包
        scope.launch(Dispatchers.IO) { runCatching { apkUpdater.dropInstalled() } }
        // 运行环境没准备好(首次启动、Nuoyi 升级后)就在后台准备,不联网、对用户不可见
        if (!quit) RuntimePreparer.ensure(this)
        // 沙箱就绪(启动时已就绪,或刚装好)就把技能目录核对一次:App 更新带来的脚本马上能用
        scope.launch {
            SandboxState.get(this@NuoyiApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@NuoyiApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@NuoyiApp)) { state.status.value is SandboxStatus.Ready } }
        }
    }

    /** 用户重新打开 Nuoyi:补上退出时略过的启动工作(见 [com.nuoyi.agent.quit.NuoyiQuit])。 */
    fun resumeAfterQuit() {
        runRecovery.recover()
        RuntimePreparer.ensure(this)
        mcp.ensureRunning()
        wechat.ensureRunning()
        scope.launch(Dispatchers.IO) {
            runCatching {
                TriggerRuntime.syncTimeZone(this@NuoyiApp)
                TriggerRuntime.rearm(this@NuoyiApp)
            }.onFailure { Log.w("NuoyiTrigger", "could not restore schedules after quit", it) }
        }
    }

    /**
     * 盯着每个正在跑的任务:某个任务刚进入终态(成功/失败/取消)就发一条通知(同时进系统通知栏和灵动岛)。
     * [taskState.runs] 只增删任务、不重发任务内部的状态变化,所以这里对每条运行单独收它的 [TaskRun.state] 流来抓终态。
     * 用「对话:槽位」当通知 id,同一任务只提示一次;任务从列表消失后清掉记录,下次再跑还能提醒。
     */
    private fun observeTaskNotifications() {
        scope.launch {
            val jobs = LinkedHashMap<String, Job>()
            val watched = LinkedHashMap<String, TaskRun>()
            val notified = mutableSetOf<String>()
            taskState.runs.collect { list ->
                val present = list.associateBy { "${it.conversationId}:${it.slot}" }
                // 任务不见了:停掉它的监听、清掉已提醒记录
                jobs.keys.filter { it !in present }.forEach { k ->
                    jobs.remove(k)?.cancel()
                    watched.remove(k)
                    notified.remove(k)
                }
                for ((key, run) in present) {
                    // 任务实例没换就复用原监听器,换了(极少见)就重建
                    if (jobs[key] != null && watched[key] === run) continue
                    jobs.remove(key)?.cancel()
                    watched[key] = run
                    jobs[key] = launch {
                        run.state.collect { st ->
                            if (st is TaskState.Terminal && notified.add(key)) {
                                val (title, tone) = when (st) {
                                    is TaskState.Succeeded -> "任务完成" to Tone.Success
                                    is TaskState.Failed -> "任务失败" to Tone.Alert
                                    is TaskState.Cancelled -> "已取消" to Tone.Info
                                }
                                val text = run.goal.takeIf { g -> g.isNotBlank() } ?: "一个任务结束了"
                                NotificationCenter.post(
                                    this@NuoyiApp,
                                    NuoyiNotification(id = "task-$key", title = title, text = text, tone = tone),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
