package com.nuoyi.agent.diag

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import com.nuoyi.agent.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃记录 + 安全启动。
 *
 * 之前整个 App 没有默认的未捕获异常处理器:任何一处异常(尤其是 Service.onCreate / onStartCommand
 * 在主线程抛出的前台服务异常)都会直接打崩进程,用户只看到「闪退」,我们拿不到任何堆栈。
 * 这里做三件事:
 *  1. 崩溃时把完整堆栈同步写到 `filesDir/crashes/latest.txt`(用 commit,不用 apply,进程马上要死);
 *  2. 连续崩溃两次就给当前版本打上「安全启动」标记:下次启动跳过浮窗服务、运行环境准备等后台重活,
 *     保证至少能进主界面,把诊断信息发出来;
 *  3. 诊断信息里自动附上最近一次崩溃堆栈,不需要 adb / logcat。
 *
 * 安全标记按版本存:升级到新版本后自动解除,不需要用户手动清。
 */
object CrashGuard {
    private const val TAG = "NuoyiCrash"
    private const val PREFS = "crash_guard"
    private const val KEY_STREAK = "streak"
    private const val KEY_LAST_MS = "last_ms"
    private const val KEY_SAFE = "safe_mode"
    private const val KEY_SAFE_VERSION = "safe_mode_version"
    private const val KEY_REPORT = "latest_report"
    private const val WINDOW_MS = 120_000L
    private const val SAFE_AFTER = 2
    private const val MAX_REPORT = 24_000

    private fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun stamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))

    /** 在 Application.onCreate 的第一行调用。重复调用不会叠加处理器。 */
    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Handler) return
        Thread.setDefaultUncaughtExceptionHandler(Handler(app.applicationContext, previous))
    }

    private class Handler(
        private val app: Context,
        private val previous: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            runCatching { record(app, thread, error) }
            // 仍交给系统默认处理器:该显示的崩溃对话框、该杀的进程照旧,但堆栈已经落盘。
            if (previous != null) previous.uncaughtException(thread, error)
            else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    private fun record(app: Context, thread: Thread, error: Throwable) {
        val p = prefs(app)
        val now = System.currentTimeMillis()
        val last = p.getLong(KEY_LAST_MS, 0L)
        val streak = if (now - last in 0..WINDOW_MS) p.getInt(KEY_STREAK, 0) + 1 else 1
        val safe = streak >= SAFE_AFTER
        val report = buildString {
            appendLine("time: ${stamp(now)}")
            appendLine("app: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.GIT_HASH})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("thread: ${thread.name}")
            appendLine("exception: ${error.javaClass.name}: ${error.message ?: "(no message)"}")
            appendLine("consecutive crashes: $streak, safe start: ${if (safe) "on" else "off"}")
            appendLine("stack:")
            append(Log.getStackTraceString(error))
        }.take(MAX_REPORT)

        // 同步提交:进程可能立刻被杀,apply() 的异步写盘会丢。
        p.edit()
            .putInt(KEY_STREAK, streak)
            .putLong(KEY_LAST_MS, now)
            .putString(KEY_REPORT, report)
            .putBoolean(KEY_SAFE, safe || (p.getBoolean(KEY_SAFE, false) && p.getString(KEY_SAFE_VERSION, "") == BuildConfig.VERSION_NAME))
            .putString(KEY_SAFE_VERSION, BuildConfig.VERSION_NAME)
            .commit()

        runCatching {
            val dir = File(app.filesDir, "crashes").also { it.mkdirs() }
            File(dir, "latest.txt").writeText(report)
            File(dir, "crash-$now.txt").writeText(report)
            dir.listFiles()?.filter { it.name.startsWith("crash-") }
                ?.sortedByDescending { it.name }
                ?.drop(5)
                ?.forEach { it.delete() }
        }
        Log.e(TAG, "crash recorded (streak=$streak, safe=$safe)", error)
    }

    /** 当前版本是否处于安全启动:连续崩溃过,且还没升级到新版本。 */
    fun safeMode(app: Context): Boolean {
        val p = prefs(app)
        return p.getBoolean(KEY_SAFE, false) && p.getString(KEY_SAFE_VERSION, "") == BuildConfig.VERSION_NAME
    }

    /** 最近一次崩溃报告;没有崩溃过返回 null。给诊断信息用,内容已截断、再经 Diagnostics.sanitize 脱敏。 */
    fun latestReport(app: Context): String? = prefs(app).getString(KEY_REPORT, null)

    /** 主界面稳定显示后调用:清零连续崩溃计数(安全标记保留到升级,避免启动崩 → 恢复 → 再崩来回震荡)。 */
    fun noteHealthyStart(app: Context) {
        prefs(app).edit().putInt(KEY_STREAK, 0).commit()
    }

    /** 主动清掉安全启动与全部崩溃记录(升级或用户确认修好后)。 */
    fun clear(app: Context) {
        prefs(app).edit().clear().commit()
        runCatching { File(app.filesDir, "crashes").deleteRecursively() }
    }
}
