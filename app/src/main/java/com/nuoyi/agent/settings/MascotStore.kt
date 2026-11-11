package com.nuoyi.agent.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 吉祥物图片:默认用随包内置的 Q 版小女孩插画(R.drawable.nuoyi_girl,透明背景);
 * 用户可以在「设置 › 吉祥物」里上传一张自己生成的图片
 * (去豆包 / 即梦 / 通义万相等平台生成,见 docs/mascot-prompts.md),App 把它复制到私有目录,
 * 所有用到吉祥物的地方都改画这张图。上传的图建议是透明背景、正方形、1024×1024 的 PNG;
 * 不传或点「恢复默认」就退回内置的小女孩形象。
 */
class MascotStore(private val prefs: SharedPreferences) {
    private val _path = MutableStateFlow(prefs.getString(KEY_PATH, null))
    val path: StateFlow<String?> = _path.asStateFlow()

    /** 是否使用自定义图片:有图且开关开着才算。 */
    val useCustom: Boolean
        get() = prefs.getBoolean(KEY_USE_CUSTOM, false) && _path.value != null

    /** 把用户从相册 / 文件里选的图复制到 App 私有目录,记为当前吉祥物。 */
    fun setCustomPath(context: Context, uri: Uri) {
        val dir = File(context.filesDir, "mascot").also { it.mkdirs() }
        val file = File(dir, "custom.png")
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { out -> input.copyTo(out) }
            }
        }.onFailure { it.printStackTrace() }
        prefs.edit().putString(KEY_PATH, file.absolutePath).putBoolean(KEY_USE_CUSTOM, true).apply()
        _path.value = file.absolutePath
    }

    /** 恢复内置形象。 */
    fun clear() {
        prefs.edit().remove(KEY_PATH).putBoolean(KEY_USE_CUSTOM, false).apply()
        _path.value = null
        synchronized(this) { cached = null; cachedPath = null; cachedStamp = -1L }
    }

    @Volatile private var cachedPath: String? = null
    @Volatile private var cachedStamp: Long = -1L
    @Volatile private var cached: Bitmap? = null

    /**
     * 读出当前吉祥物位图;没有或读不出返回 null(调用方退回内置形象)。
     *
     * 之前是裸 `decodeFile`:用户从相册选一张 4000×3000 的照片就是约 48MB 的位图,
     * 而欢迎页 / Logo / 聊天页 / 关于页 / 设置页各自 `remember` 一次,同一张图会被解码好几份,
     * 在荣耀这类内存吃紧的机器上很容易 OOM 直接闪退。这里按最长边 1024 采样,
     * 全进程只缓存一份,并兜住 OutOfMemoryError —— 读不出来就退回内置小女孩,绝不崩。
     */
    fun loadBitmap(): Bitmap? {
        val p = _path.value ?: return null
        val stamp = runCatching { File(p).lastModified() }.getOrDefault(0L)
        synchronized(this) {
            cached?.takeIf { cachedPath == p && cachedStamp == stamp && !it.isRecycled }?.let { return it }
        }
        val decoded = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(p, bounds)
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            while (longest / sample > MAX_SIDE) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample.coerceAtLeast(1)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(p, opts)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Throwable) {
            null
        } ?: return null
        synchronized(this) {
            cachedPath = p
            cachedStamp = stamp
            cached = decoded
        }
        return decoded
    }

    companion object {
        private const val PREFS = "mascot"
        private const val MAX_SIDE = 1024
        private const val KEY_PATH = "custom_path"
        private const val KEY_USE_CUSTOM = "use_custom"
        @Volatile private var instance: MascotStore? = null

        fun get(context: Context): MascotStore = instance ?: synchronized(this) {
            instance ?: MascotStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
