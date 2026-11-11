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
    }

    /** 读出当前吉祥物位图;没有或读不出返回 null(调用方退回内置形象)。 */
    fun loadBitmap(): Bitmap? {
        val p = _path.value ?: return null
        return BitmapFactory.decodeFile(p)
    }

    companion object {
        private const val PREFS = "mascot"
        private const val KEY_PATH = "custom_path"
        private const val KEY_USE_CUSTOM = "use_custom"
        @Volatile private var instance: MascotStore? = null

        fun get(context: Context): MascotStore = instance ?: synchronized(this) {
            instance ?: MascotStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)).also { instance = it }
        }
    }
}
