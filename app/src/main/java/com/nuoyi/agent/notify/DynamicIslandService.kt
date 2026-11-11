package com.nuoyi.agent.notify

import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nuoyi.agent.R
import com.nuoyi.agent.callnotes.CallState
import com.nuoyi.agent.callnotes.CallStateFlow
import com.nuoyi.agent.callnotes.CallStateMonitor
import com.nuoyi.agent.settings.FeatureFlags
import com.nuoyi.agent.ui.MainActivity
import com.nuoyi.agent.ui.CallLiveActivity
import com.nuoyi.agent.ui.theme.MascotMood
import com.nuoyi.agent.ui.theme.NuoyiIcons
import com.nuoyi.agent.ui.theme.NuoyiMascot
import com.nuoyi.agent.ui.theme.NuoyiTheme
import com.nuoyi.agent.ui.theme.NuoyiType
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 类灵动岛胶囊:把 [NotificationCenter.active] 的通知、以及实时通话状态,显示在屏幕顶部居中的胶囊里。
 * 胶囊可拖动;多条通知会堆叠(角标显示条数),点一下展开成卡片逐条查看 / 清除 / 直达对应页面,长按划掉最新一条。
 * 展开期间通知不会被倒计时撤掉,尺寸变化后会重新把窗口 clamp 回屏幕内。
 *
 * **Android 的硬边界**:iOS 的灵动岛是系统 UI,第三方通过 Live Activity 把数据交给系统渲染;
 * Android 没有对等的公开 API,第三方 App 既不能往系统状态栏里塞自定义界面,也不能改写前摄挖孔区域的显示内容。
 * 这里能做到的是 `TYPE_APPLICATION_OVERLAY` 浮窗 + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` + 读 `DisplayCutout`
 * 把胶囊对齐到挖孔的位置与宽度,视觉上尽量像「岛」;状态栏里那个常驻的小图标才是 Android 允许第三方真正放进去的东西。
 * 部分厂商仍会裁掉浮窗落在状态栏/挖孔区的部分,所以设置页提供「贴合前摄挖孔」开关,关掉就退回普通顶部胶囊。
 *
 * 它本身不依赖系统通知栏权限;若没有「显示在其他应用上层」权限就不显示。
 * 用前台服务保活(常驻低优先通知只用来保活,胶囊显示不靠它);被系统清掉时 [onTaskRemoved] 会再把自己拉起来。
 */
class DynamicIslandService : Service() {
    private var wm: WindowManager? = null
    private var view: ComposeView? = null
    /** 胶囊窗口当前的布局参数;拖动时实时改 x/y 并写回,用于保存/恢复位置。 */
    private var params: WindowManager.LayoutParams? = null
    /** 是否避开状态栏/挖孔区;设置页改开关后由 IslandContent 订阅实时同步。 */
    private var avoidStatusBar = true
    /** 是否贴合前摄挖孔(类灵动岛);设备没有挖孔时自动为 false。用 State 以便在开关切换时重组胶囊宽度。 */
    private var hugCutout by mutableStateOf(false)
    /** 监听横竖屏/分屏等配置变化,Service 本身没有 onConfigurationChanged 回调。 */
    private var configCallback: ComponentCallbacks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        // Android 14+ 拒绝前台服务(后台启动限制 / 通知渠道 / specialUse 校验)时会在 startForeground 抛异常。
        // Service.onCreate 跑在主线程,异常冒出去会打崩整个 App 进程 —— 真机上表现为「一打开就闪退」。
        // 进不了前台就安静停掉:胶囊不显示,其余功能照常用,绝不牵连主进程。
        val foregroundOk = runCatching {
            startForeground(1, NotificationCenter.islandServiceNotification(this))
        }.onFailure { android.util.Log.w("NuoyiIsland", "cannot enter foreground, capsule disabled", it) }.isSuccess
        if (!foregroundOk) {
            stopSelf()
            return
        }
        // 顺带开始监听通话状态(需要 READ_PHONE_STATE,没有则静默跳过)
        runCatching { CallStateMonitor.start(this) }
            .onFailure { android.util.Log.w("NuoyiIsland", "call state monitor failed", it) }
        // 别叫 flags:下面 LayoutParams.apply 里也有个 flags 字段,同名会让编译器把赋值算到这个 val 上
        val featureFlags = FeatureFlags.get(this)
        avoidStatusBar = featureFlags.islandAvoidStatusBar()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val savedPos = featureFlags.islandPosForScreen(dm.widthPixels, dm.heightPixels)
        // 有挖孔 + 开关打开 + 用户没手动拖过,才贴合;否则走原来的顶部居中 / 自定义位置逻辑
        hugCutout = featureFlags.islandHugCutout() && cutoutRect() != null && savedPos == null
        val compose = ComposeView(ContextThemeWrapper(this, R.style.Theme_Nuoyi)).apply { setContent { NuoyiTheme { IslandContent() } } }
        val params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            y = statusBarTop()
            if (hugCutout) {
                // 不设 ALWAYS 的话系统会把浮窗整体下推到挖孔下方,永远贴不到前摄。
                // 即便设了,个别厂商仍可能裁掉浮窗在状态栏区域的部分 —— 这属于系统限制。
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                gravity = Gravity.TOP or Gravity.START
                y = cutoutRect()?.top ?: 0
            }
        }
        // 之前拖动过就按保存时的屏幕尺寸等比还原;贴合模式下位置由挖孔决定,忽略手动位置
        val saved = if (hugCutout) null else savedPos
        if (saved != null) {
            params.gravity = Gravity.TOP or Gravity.START
            params.x = saved.first
            params.y = saved.second
        }
        try {
            this.params = params
            wm!!.addView(compose, params)
            view = compose
            // 视图测量完成后再 clamp 一次,确保胶囊完整落在当前屏幕内(旋转/分屏后尤其重要)
            clampToScreen(params)
            wm!!.updateViewLayout(compose, params)
            // 真正测量完之后才有真实宽度,这时才能按挖孔中心把胶囊对齐过去
            compose.post {
                if (hugCutout) alignToCutout(expanded = false)
                else {
                    clampToScreen(params)
                    runCatching { wm?.updateViewLayout(compose, params) }
                }
            }
        } catch (e: SecurityException) {
            stopSelf()
        } catch (e: Throwable) {
            stopSelf()
        }
        // Service 没有 onConfigurationChanged,用 ComponentCallbacks 监听横竖屏/分屏变化
        val cb = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) = relayoutForScreen()
            override fun onLowMemory() = Unit
        }
        registerComponentCallbacks(cb)
        configCallback = cb
    }

    override fun onDestroy() {
        configCallback?.let { unregisterComponentCallbacks(it) }
        configCallback = null
        CallStateMonitor.stop()
        view?.let { wm?.removeView(it) }
        view = null
        super.onDestroy()
    }

    /**
     * 横竖屏 / 分屏 / 外接屏变化时重算位置:按保存时的比例还原,再 clamp 到新屏幕内。
     * 没拖动过的胶囊仍保持顶部居中,只更新离顶距离(横竖屏状态栏高度可能不同)。
     */
    private fun relayoutForScreen() {
        if (hugCutout) {
            alignToCutout(expanded = false)
            return
        }
        val p = params ?: return
        val v = view ?: return
        val dm = resources.displayMetrics
        val saved = FeatureFlags.get(this).islandPosForScreen(dm.widthPixels, dm.heightPixels)
        if (saved == null) {
            p.y = statusBarTop()
        } else {
            p.gravity = Gravity.TOP or Gravity.START
            p.x = saved.first
            p.y = saved.second
        }
        clampToScreen(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** 把窗口坐标限制在屏幕内;开启「避开状态栏」时 y 下限抬到状态栏下方。 */
    private fun clampToScreen(p: WindowManager.LayoutParams) {
        val w = view?.width ?: 0
        val h = view?.height ?: 0
        val dm = resources.displayMetrics
        val low = minIslandY()
        p.x = p.x.coerceIn(0, max(0, dm.widthPixels - w))
        p.y = p.y.coerceIn(low, max(low, dm.heightPixels - h))
    }

    /** 贴合挖孔时允许 y 落在状态栏/挖孔区域;否则按「避开状态栏」开关限制最低位置。 */
    private fun minIslandY(): Int = if (hugCutout) 0 else if (avoidStatusBar) statusBarTop() else 0

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 被系统清掉时立刻把自己拉起来,尽量保证系统级胶囊常驻(仍需用户授予自启动白名单才真正有效)
        try {
            startForegroundService(Intent(this, DynamicIslandService::class.java))
        } catch (_: Throwable) {
        }
        super.onTaskRemoved(rootIntent)
    }

    /** 状态栏高度 + 一点间距,作为胶囊离顶边的像素。 */
    private fun statusBarTop(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val h = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        return h + (10 * resources.displayMetrics.density).toInt()
    }

    /** 顶部前摄挖孔(屏幕坐标);没有挖孔、或系统不肯把 cutout 信息给浮窗时返回 null。 */
    private fun cutoutRect(): Rect? {
        val manager = wm ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val cutout = runCatching { manager.currentWindowMetrics.windowInsets.displayCutout }.getOrNull() ?: return null
        return cutout.boundingRects.filter { it.top <= 4 && it.width() > 8 }.minByOrNull { it.top }
    }

    /** 贴合挖孔时胶囊的宽度:至少包住挖孔、左右各延展一点,再夹到能放得下图标和标题的范围。 */
    private fun islandWidth(): Dp? {
        if (!hugCutout) return null
        val w = cutoutRect()?.width() ?: return null
        val density = resources.displayMetrics.density
        return ((w / density) + 56f).coerceIn(160f, 320f).dp
    }

    /** 按挖孔中心对齐;展开成卡片后以屏幕中心为准并落到挖孔下方,免得整张卡片压住状态栏。 */
    private fun alignToCutout(expanded: Boolean) {
        val p = params ?: return
        val v = view ?: return
        val c = cutoutRect() ?: return
        val dm = resources.displayMetrics
        val width = v.width
        p.gravity = Gravity.TOP or Gravity.START
        val centerX = if (expanded) dm.widthPixels / 2f else c.centerX().toFloat()
        p.x = (centerX - width / 2f).roundToInt().coerceIn(0, max(0, dm.widthPixels - width))
        p.y = if (expanded) c.bottom + (6 * dm.density).toInt() else c.top
        clampToScreen(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    /** 打开 App;[route] 不为空时(如 "callnotes")由 MainActivity 直接跳到对应页面。 */
    private fun openApp(route: String? = null) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.nuoyi.agent.action.OPEN"
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (route != null) putExtra(EXTRA_OPEN_ROUTE, route)
        }
        startActivity(intent)
    }

    /** 胶囊/卡片尺寸变化(展开、收起、通知增减)后重新把窗口限制在屏幕内;贴合模式下顺便重新对齐。 */
    private fun clampAfterResize(expanded: Boolean) {
        if (hugCutout) {
            alignToCutout(expanded)
            return
        }
        val p = params ?: return
        val v = view ?: return
        clampToScreen(p)
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun openCallLive() {
        val intent = Intent(this, CallLiveActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    /**
     * 给胶囊加「可拖动 + 点按 + 长按」手势:拖动改窗口位置并记住,点按=onTap,长按=onLongPress。
     * 用两个独立 [pointerInput]:点按/长按与拖动并行,移动超过 touchSlop 后 tap 自动取消、交给拖动,
     * 这样不用自己起协程判长按,也不用手写 await 循环。[key] 在内容变化时重建手势,避免闭包 capture 旧数据。
     */
    private fun Modifier.capsuleDrag(
        key: Any?,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
    ): Modifier = this
        .pointerInput(key) {
            detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() })
        }
        .pointerInput(key) {
            detectDragGestures(
                onDragStart = { ensureAbsoluteGravity() },
                onDrag = { change, dragAmount ->
                    params?.let { p ->
                        p.x = (p.x + dragAmount.x).roundToInt()
                        p.y = (p.y + dragAmount.y).roundToInt()
                        clampToScreen(p)
                        view?.let { v -> wm?.updateViewLayout(v, p) }
                    }
                    change.consume()
                },
                onDragEnd = { persistIslandPos() },
                onDragCancel = { persistIslandPos() },
            )
        }

    /** 首次拖动时把「居中」的 gravity 转成绝对左上坐标,方便之后用 x/y 自由定位。 */
    private fun ensureAbsoluteGravity() {
        val p = params ?: return
        if ((p.gravity and Gravity.CENTER_HORIZONTAL) != 0) {
            val w = view?.width ?: 0
            val dm = resources.displayMetrics
            p.x = ((dm.widthPixels - w) / 2f).roundToInt()
            p.gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun persistIslandPos() {
        val p = params ?: return
        val dm = resources.displayMetrics
        // 连屏幕尺寸一起存,下次横竖屏/分屏时才能按同一比例还原
        FeatureFlags.get(this).setIslandPos(p.x, p.y, dm.widthPixels, dm.heightPixels)
        // 手动拖过就以用户的位置为准(只在内存里退出贴合,不改设置开关):
        // 「复位胶囊位置」清掉位置后,服务重启会重新贴合挖孔。
        hugCutout = false
    }

    @Composable
    private fun IslandContent() {
        val active by NotificationCenter.active.collectAsState()
        val call by CallStateFlow.state.collectAsState()
        val flags = FeatureFlags.get(this@DynamicIslandService)
        val idlePill by flags.islandIdlePillChanges().collectAsState(initial = flags.islandIdlePill())
        val callMode by flags.islandCallModeChanges().collectAsState(initial = flags.islandCallMode())
        val avoidStatus by flags.islandAvoidStatusBarChanges().collectAsState(initial = flags.islandAvoidStatusBar())
        val expandOnTap by flags.islandExpandCardChanges().collectAsState(initial = flags.islandExpandCard())
        val hugPref by flags.islandHugCutoutChanges().collectAsState(initial = flags.islandHugCutout())
        var expanded by remember { mutableStateOf(false) }
        // 初始状态由 onCreate 处理,这里只响应「用户在设置页切换开关」,避免一启动就抹掉拖过的位置
        var hugInitialized by remember { mutableStateOf(false) }
        val pillWidth: Dp? = if (hugCutout) islandWidth() else null

        val inCall = callMode && (call.offhook || call.ringing)
        val hasContent = inCall || active.isNotEmpty()
        val duration = rememberCallDuration(if (inCall) call else null)

        LaunchedEffect(avoidStatus) {
            avoidStatusBar = avoidStatus
            // 开关一改立即把胶囊挪出/放回状态栏区域,不用等重启服务
            params?.let { p ->
                clampToScreen(p)
                view?.let { v -> runCatching { wm?.updateViewLayout(v, p) } }
            }
        }
        // 设置页切换「贴合挖孔」时立即生效:打开就清掉手动位置并对齐,关闭就回到原来的位置逻辑
        LaunchedEffect(hugPref) {
            if (!hugInitialized) {
                hugInitialized = true
                return@LaunchedEffect
            }
            // 重新打开贴合时清掉旧的拖动位置,让它真的贴回挖孔;关闭则保留用户拖过的位置
            if (hugPref) flags.setIslandPos(-1, -1)
            val canHug = hugPref && cutoutRect() != null
            hugCutout = canHug
            params?.let { p ->
                p.layoutInDisplayCutoutMode = if (canHug) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
                if (canHug) alignToCutout(expanded = false) else relayoutForScreen()
            }
        }
        // 展开卡片期间暂停自动收起;尺寸动画走完后再按新的宽高把窗口 clamp 回屏幕内
        LaunchedEffect(expanded, active.size, inCall) {
            NotificationCenter.expiryPaused = expanded && expandOnTap
            delay(280)
            clampAfterResize(expanded)
        }
        // 通知清空或通话结束后别留一张空卡片
        LaunchedEffect(hasContent) { if (!hasContent) expanded = false }

        when {
            !hasContent && !idlePill -> Spacer(Modifier.size(1.dp))
            !hasContent -> IdlePill()
            else -> Box(Modifier.animateContentSize(animationSpec = tween(220))) {
                if (expanded && expandOnTap) {
                    IslandCard(
                        call = if (inCall) call else null,
                        active = active,
                        duration = duration,
                        onCollapse = { expanded = false },
                    )
                } else {
                    IslandPillStack(
                        call = if (inCall) call else null,
                        active = active,
                        expandOnTap = expandOnTap,
                        duration = duration,
                        pillWidth = pillWidth,
                        onExpand = { expanded = true },
                    )
                }
            }
        }
    }

    /** 通话计时只起一个协程,胶囊和展开后的卡片共用同一份时长文本。 */
    @Composable
    private fun rememberCallDuration(call: CallState?): String {
        var now by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(call?.offhook, call?.startMs) {
            if (call?.offhook == true) {
                while (true) {
                    now = System.currentTimeMillis()
                    delay(1000)
                }
            }
        }
        val ms = if (call?.offhook == true) (now - call.startMs).coerceAtLeast(0) else 0L
        return formatDuration(ms)
    }

    /**
     * 收起态:主胶囊 + 背后两片"纸片"(多于一条时)+ 条数角标。
     * 通话优先占用胶囊正文,通知条数仍照常堆叠;点一下展开卡片,长按划掉最新一条。
     */
    @Composable
    private fun IslandPillStack(
        call: CallState?,
        active: List<NuoyiNotification>,
        expandOnTap: Boolean,
        duration: String,
        /** 贴合挖孔时给定宽度(跟着挖孔走),其余情况为 null 保持自适应。 */
        pillWidth: Dp?,
        onExpand: () -> Unit,
    ) {
        val c = NuoyiTheme.colors
        val shown = active.lastOrNull()
        val count = active.size
        val label: String
        val sub: String
        val tint: Color
        val mood: MascotMood
        if (call != null) {
            label = if (call.ringing) "来电" else "通话中"
            sub = if (call.ringing) (call.number ?: "未知号码")
            else if (call.number != null) "${call.number} · $duration" else duration
            tint = c.accent
            mood = if (call.offhook) MascotMood.Listening else MascotMood.Happy
        } else {
            label = shown?.title ?: "通知"
            sub = shown?.text ?: ""
            tint = toneTint(shown?.tone ?: Tone.Info)
            mood = toneMood(shown?.tone ?: Tone.Info)
        }

        // 进度条只在通知态且有默认停留时长时跑;通话不自动收起
        val dwell = NotificationCenter.defaultDwellMs
        val progress = remember { Animatable(1f) }
        LaunchedEffect(shown?.id, dwell, call) {
            progress.snapTo(1f)
            if (call == null && dwell > 0) progress.animateTo(0f, tween(dwell.toInt(), easing = LinearEasing))
        }

        Box(
            Modifier.widthIn(max = 340.dp)
                .then(if (pillWidth != null) Modifier.width(pillWidth) else Modifier)
                .padding(bottom = 10.dp),
        ) {
            // 背后露出的两片圆角,视觉上像叠了一摞通知
            if (count > 2) {
                Box(
                    Modifier.matchParentSize().offset(y = 10.dp).padding(horizontal = 15.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(c.surface.copy(alpha = 0.45f))
                        .border(1.dp, c.edge, RoundedCornerShape(22.dp)),
                )
            }
            if (count > 1) {
                Box(
                    Modifier.matchParentSize().offset(y = 5.dp).padding(horizontal = 7.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(c.surface.copy(alpha = 0.72f))
                        .border(1.dp, c.edge, RoundedCornerShape(22.dp)),
                )
            }
            Box(
                Modifier
                    .then(if (pillWidth != null) Modifier.fillMaxWidth() else Modifier.widthIn(max = 340.dp))
                    .shadow(14.dp, RoundedCornerShape(24.dp), clip = false)
                    .clip(RoundedCornerShape(24.dp))
                    .border(1.5.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
                    .background(c.surface.copy(alpha = 0.98f))
                    .capsuleDrag(
                        if (call != null) call.startMs to count else (shown?.id ?: "none") to count,
                        onTap = {
                            if (expandOnTap) onExpand()
                            else if (call != null) openCallLive()
                            else openApp(shown?.openRoute)
                        },
                        onLongPress = {
                            if (call != null) {
                                NotificationCenter.post(
                                    this@DynamicIslandService,
                                    NuoyiNotification("call-hint", "通话中", "点胶囊可记要点 / 让 AI 助理记下待办", tone = Tone.Info),
                                )
                            } else if (shown != null) {
                                NotificationCenter.dismiss(shown.id)
                            }
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (call != null) {
                        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                            Icon(NuoyiIcons.Phone, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                    NuoyiMascot(mood, size = 40.dp)
                    Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                        Text(label, style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold),
                            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = NuoyiType.Hint, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (count > 1) {
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp))
                                .background(tint.copy(alpha = 0.14f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("$count 条", style = NuoyiType.Hint.copy(fontWeight = FontWeight.SemiBold),
                                color = tint, maxLines = 1)
                        }
                    }
                }
                if (call == null) {
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth(progress.value)
                            .height(3.dp).background(tint.copy(alpha = 0.9f)),
                    )
                }
            }
        }
    }

    /** 展开态:顶部把手(可拖动 / 点按收起)+ 通话信息 + 可滚动的通知堆叠 + 底部操作。 */
    @Composable
    private fun IslandCard(
        call: CallState?,
        active: List<NuoyiNotification>,
        duration: String,
        onCollapse: () -> Unit,
    ) {
        val c = NuoyiTheme.colors
        val cardW = min(340, LocalConfiguration.current.screenWidthDp - 24).dp
        Column(
            Modifier
                .width(cardW)
                .shadow(18.dp, RoundedCornerShape(24.dp), clip = false)
                .clip(RoundedCornerShape(24.dp))
                .border(1.5.dp, c.edge, RoundedCornerShape(24.dp))
                .background(c.surface.copy(alpha = 0.99f)),
        ) {
            // 把手:整张卡片靠它拖动,点一下收起;下面各按钮单独 clickable,不受拖动手势影响
            Row(
                Modifier.fillMaxWidth()
                    .capsuleDrag("card-handle", onTap = onCollapse, onLongPress = onCollapse)
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Icon(if (call != null) NuoyiIcons.Phone else NuoyiIcons.Bell, null,
                    tint = c.accent, modifier = Modifier.size(18.dp))
                Text(
                    if (call != null) "通话中 · ${active.size} 条通知" else "通知 · ${active.size} 条",
                    style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text("收起", style = NuoyiType.Hint, color = c.accent,
                    modifier = Modifier.clickable(onClick = onCollapse).padding(horizontal = 6.dp, vertical = 3.dp))
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            if (call != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NuoyiMascot(MascotMood.Listening, size = 40.dp)
                    Column(Modifier.weight(1f, fill = false)) {
                        Text(if (call.ringing) "来电" else "通话中 · $duration",
                            style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1)
                        Text(call.number ?: "未知号码", style = NuoyiType.Hint, color = c.ink2, maxLines = 1)
                    }
                    Text("记要点", style = NuoyiType.Hint.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent)
                            .clickable { openCallLive(); onCollapse() }
                            .padding(horizontal = 12.dp, vertical = 7.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 224.dp).verticalScroll(rememberScrollState()),
            ) {
                if (active.isEmpty()) {
                    Text("暂无通知", style = NuoyiType.Hint, color = c.ink3,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                } else {
                    val items = active.asReversed() // 最新的排最上面
                    items.forEachIndexed { i, n ->
                        NoticeRow(
                            n,
                            onOpen = {
                                openApp(n.openRoute)
                                NotificationCenter.dismiss(n.id)
                                onCollapse()
                            },
                            onDismiss = { NotificationCenter.dismiss(n.id) },
                        )
                        if (i < items.size - 1) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.hair))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("全部清除", style = NuoyiType.Hint, color = if (active.isEmpty()) c.ink3 else c.ink2,
                    modifier = Modifier.clickable(enabled = active.isNotEmpty()) {
                        NotificationCenter.clearAll()
                        onCollapse()
                    }.padding(6.dp))
                Spacer(Modifier.weight(1f))
                Text("打开诺伊", style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold), color = c.onAccent,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent)
                        .clickable { openApp(); onCollapse() }
                        .padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }
    }

    /** 卡片里的单条通知:点整行直达 openRoute 并撤掉该条,点右侧 × 只清除。 */
    @Composable
    private fun NoticeRow(n: NuoyiNotification, onOpen: () -> Unit, onDismiss: () -> Unit) {
        val c = NuoyiTheme.colors
        val tint = toneTint(n.tone)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(RoundedCornerShape(4.dp)).background(tint))
            Column(Modifier.weight(1f)) {
                Text(n.title, style = NuoyiType.Caption.copy(fontWeight = FontWeight.SemiBold),
                    color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(n.text, style = NuoyiType.Hint, color = c.ink2,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(timeLabel(n.atMillis), style = NuoyiType.Hint, color = c.ink3)
            }
            Icon(NuoyiIcons.Close, null, tint = c.ink3,
                modifier = Modifier.size(18.dp).clickable(onClick = onDismiss))
        }
    }

    @Composable
    private fun toneTint(tone: Tone): Color {
        val c = NuoyiTheme.colors
        return when (tone) {
            Tone.Success -> c.ok
            Tone.Warn -> Color(0xFFFF9F0A)
            Tone.Alert -> c.bad
            else -> c.accent
        }
    }

    private fun toneMood(tone: Tone): MascotMood = when (tone) {
        Tone.Success -> MascotMood.Happy
        Tone.Warn, Tone.Alert -> MascotMood.Listening
        else -> MascotMood.Idle
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private fun timeLabel(ms: Long): String = timeFmt.format(Date(ms))

    @Composable
    private fun IdlePill() {
        val c = NuoyiTheme.colors
        Box(
            Modifier
                .shadow(10.dp, RoundedCornerShape(20.dp), clip = false)
                .clip(RoundedCornerShape(20.dp))
                .background(c.surface.copy(alpha = 0.95f))
                .capsuleDrag(Unit, onTap = { openApp() }, onLongPress = {})
                .padding(8.dp),
        ) {
            NuoyiMascot(MascotMood.Idle, size = 30.dp)
        }
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        val m = total / 60
        val s = total % 60
        return "%02d:%02d".format(m, s)
    }
}
