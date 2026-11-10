package com.nuoyi.agent.memory

import com.nuoyi.agent.core.memory.ProfileEntry
import com.nuoyi.agent.core.memory.ProfileStatus
import com.nuoyi.agent.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileProfileBackendTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun encryptedRoundTripKeepsSupersededHistory() {
        val cipher = object : KeyCipher {
            override fun encrypt(plain: ByteArray) = plain.reversedArray()
            override fun decrypt(data: ByteArray) = data.reversedArray()
        }
        val file = File(tmp.root, "memory/profile.bin")
        val store = FileProfileBackend(file, cipher)
        val rows = listOf(ProfileEntry("1", "用户喜欢安静的酒店。", 1, ProfileStatus.superseded, 2),
            ProfileEntry("2", "用户喜欢交通便利的酒店。", 2, ProfileStatus.active))
        store.write(rows)
        assertFalse(file.readText().contains("用户喜欢"))
        assertEquals(rows, FileProfileBackend(file, cipher).read())
    }
}
