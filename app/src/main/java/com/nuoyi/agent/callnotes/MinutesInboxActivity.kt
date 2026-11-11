package com.nuoyi.agent.callnotes

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * 系统分享的接收端:在厂商「通话纪要」App 里点分享 → 选「诺伊 · 收进纪要库」,
 * 这段纪要文字就进 [MinutesInbox]。不打开界面、不自动执行任何操作,收完立刻退出。
 *
 * 这是绕开「厂商 App 没有文件夹、不能批量导出」的主通道:一次收一段,攒够之后
 * 在「通话纪要」页可以一键整理全部、或把全部导出成一个 TXT / JSON 文件。
 */
class MinutesInboxActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val i = intent
        val texts = ArrayList<String>()
        when (i?.action) {
            Intent.ACTION_SEND -> {
                i.getStringExtra(Intent.EXTRA_TEXT)?.let { texts.add(it) }
                uriExtra(i, Intent.EXTRA_STREAM)?.let { u -> readText(u)?.let(texts::add) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                i.getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }?.let(texts::addAll)
                i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { u ->
                    readText(u)?.let(texts::add)
                }
            }
        }
        // 分享文字常带标题/链接,单独一行收进来;多段用空行隔开,方便人读和模型分析
        val body = texts.joinToString("\n\n") { it.trim() }.trim()
        val from = referrer()
        val inbox = MinutesInbox.of(this)
        val clip = if (body.isBlank()) null else inbox.add(body, "share", from)
        val total = inbox.list().size
        Toast.makeText(
            this,
            when {
                clip == null && body.isBlank() -> "没收到文字：请在纪要 App 里用「分享文字 / 复制」"
                clip == null -> "这一段已经收过了，未重复收录（共 $total 段）"
                else -> "已收进纪要库（共 $total 段）"
            },
            Toast.LENGTH_LONG,
        ).show()
        if (clip != null) MinutesCapture.postNotice(this, "分享收录成功：已攒了 $total 段纪要")
        finish()
    }

    private fun readText(uri: Uri): String? = runCatching {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText().take(20_000) }
    }.getOrNull()

    private fun uriExtra(i: Intent, key: String): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) i.getParcelableExtra(key, Uri::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(key) as? Uri

    private fun referrer(): String = runCatching {
        referrer?.host?.takeIf { it.isNotBlank() } ?: ""
    }.getOrDefault("")
}
