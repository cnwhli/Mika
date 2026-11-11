package com.nuoyi.agent.callnotes

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.nuoyi.agent.settings.FeatureFlags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

private val MINUTES_EXTS = setOf(".txt", ".md", ".markdown", ".json", ".text")

/** 读一个用户直接选中的文本文件(.txt/.md/.json),拼成字符串交给模型。 */
suspend fun readMinutesFromUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openInputStream(uri)?.use { s -> s.bufferedReader().readText() }.orEmpty()
    }.getOrDefault("")
}

/** 把 SAF 树里的文本文件(.txt/.md/.json)读出来拼成一个大字符串交给模型。 */
suspend fun readMinutesFromTree(context: Context, treeUri: Uri): String = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val exts = setOf(".txt", ".md", ".markdown", ".json", ".text")
    val out = StringBuilder()
    val treeId = DocumentsContract.getTreeDocumentId(treeUri)
    val queue: ArrayDeque<String> = ArrayDeque(listOf(treeId))
    var files = 0
    while (queue.isNotEmpty() && files < 60) {
        val parent = queue.removeFirst()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
        val cursor = resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: continue
        cursor.use { c ->
            val idI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeI = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext() && files < 60) {
                val id = c.getString(idI)
                val name = c.getString(nameI)
                val mime = c.getString(mimeI) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (queue.size < 10) queue.add(id)
                } else if (exts.any { name.endsWith(it, ignoreCase = true) }) {
                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    runCatching {
                        resolver.openInputStream(docUri)?.use { s ->
                            val text = s.bufferedReader().readText()
                            if (text.isNotBlank()) { out.append("\n\n# ").append(name).append("\n").append(text); files++ }
                        }
                    }
                }
            }
        }
    }
    out.toString()
}

/** 一个可以直接 openInputStream 读取的纪要文件;自动发现列表用。 */
data class MinutesFile(
    val uri: Uri,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val source: String,
)

/** Cursor 里有些列在部分 DocumentsProvider / 机型上不存在,缺了就当 0 / 空串。 */
private fun Cursor.textOf(col: String): String {
    val i = getColumnIndex(col)
    return if (i >= 0 && !isNull(i)) getString(i).orEmpty() else ""
}

private fun Cursor.longOf(col: String): Long {
    val i = getColumnIndex(col)
    return if (i >= 0 && !isNull(i)) getLong(i) else 0L
}

/**
 * 列出上次授权过的 SAF 文件夹里的文本纪要(按最近修改倒序)。
 * 权限被系统收回或目录不可读时返回空列表,由界面引导用户重新选一次。
 */
suspend fun listMinutesInTree(context: Context, treeUri: Uri, limit: Int = 40): List<MinutesFile> =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val out = ArrayList<MinutesFile>()
            val queue = ArrayDeque<String>()
            queue.add(DocumentsContract.getTreeDocumentId(treeUri))
            var visited = 0
            while (queue.isNotEmpty() && visited < 240) {
                val parent = queue.removeFirst()
                val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
                val cursor = resolver.query(
                    children,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null, null, null,
                ) ?: continue
                cursor.use { c ->
                    while (c.moveToNext() && visited < 240) {
                        visited++
                        val id = c.textOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val name = c.textOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        if (id.isEmpty() || name.isEmpty()) continue
                        if (c.textOf(DocumentsContract.Document.COLUMN_MIME_TYPE) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (queue.size < 12) queue.add(id)
                            continue
                        }
                        if (MINUTES_EXTS.none { name.endsWith(it, ignoreCase = true) }) continue
                        out += MinutesFile(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                            name,
                            c.longOf(DocumentsContract.Document.COLUMN_SIZE),
                            c.longOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                            "已授权的纪要文件夹",
                        )
                    }
                }
            }
            out.sortedByDescending { it.lastModified }.take(limit)
        }.getOrDefault(emptyList())
    }

private val MINUTES_PATH_HINTS = listOf(
    "call", "record", "recorder", "sound_recorder", "录音", "纪要", "transcript", "minutes", "asr", "语音",
)

private fun looksLikeMinutesPath(path: String, name: String): Boolean =
    MINUTES_PATH_HINTS.any { path.contains(it) || name.contains(it) }

/**
 * 尽力从 MediaStore 里找系统录音 / 通话纪要目录下的文本文件。
 * Android 11+ 对第三方读取非媒体文件有限制,很多机型查不到属于正常情况,返回空即可。
 */
private fun mediaStoreCandidates(context: Context): List<MinutesFile> = runCatching {
    val resolver = context.contentResolver
    val base = MediaStore.Files.getContentUri("external")
    val selection = MINUTES_EXTS.joinToString(" OR ") {
        "lower(${MediaStore.MediaColumns.DISPLAY_NAME}) LIKE '%$it'"
    }
    val cursor = resolver.query(
        base,
        arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
        ),
        selection, null,
        "${MediaStore.MediaColumns.DATE_MODIFIED} DESC LIMIT 250",
    ) ?: return@runCatching emptyList()
    val out = ArrayList<MinutesFile>()
    cursor.use { c ->
        while (c.moveToNext()) {
            val id = c.longOf(MediaStore.MediaColumns._ID)
            val name = c.textOf(MediaStore.MediaColumns.DISPLAY_NAME)
            val path = c.textOf(MediaStore.MediaColumns.RELATIVE_PATH).lowercase()
            if (id <= 0 || name.isEmpty() || !looksLikeMinutesPath(path, name.lowercase())) continue
            out += MinutesFile(
                ContentUris.withAppendedId(base, id),
                name,
                c.longOf(MediaStore.MediaColumns.SIZE),
                c.longOf(MediaStore.MediaColumns.DATE_MODIFIED) * 1000L,
                "设备文件",
            )
        }
    }
    out
}.getOrDefault(emptyList())

/**
 * 自动发现可用的通话纪要:优先列出上次授权过的 SAF 文件夹,再用 MediaStore 尽力补一批。
 * [sinceMs] 之后修改过的才返回(留 1 分钟容差,兼容系统时间戳偏差);传 0 表示不限时间。
 * 找不到时由界面引导用户手动选文件夹,不影响通话速记主流程。
 */
suspend fun discoverMinutes(context: Context, sinceMs: Long = 0L, limit: Int = 20): List<MinutesFile> =
    withContext(Dispatchers.IO) {
        val tree = FeatureFlags.get(context).lastMinutesTree()
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
        val fromTree = tree?.let { listMinutesInTree(context, it) } ?: emptyList()
        (fromTree + mediaStoreCandidates(context))
            .distinctBy { "${it.name}|${it.size}" }
            .filter { it.lastModified <= 0L || sinceMs <= 0L || it.lastModified >= sinceMs - 60_000L }
            .sortedByDescending { it.lastModified }
            .take(limit)
    }
