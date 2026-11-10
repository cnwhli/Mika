package com.nuoyi.agent.callnotes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

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
