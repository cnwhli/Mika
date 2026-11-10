package com.nuoyi.agent.task

import com.nuoyi.agent.core.run.ChannelPeer
import com.nuoyi.agent.core.run.Delivery
import com.nuoyi.agent.core.run.DeliveryRecord
import com.nuoyi.agent.core.run.DeliveryState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class FileOutboxStoreTest {
    @Test fun attemptingDeliverySurvivesRestartWithoutLosingItsIdentity() {
        val dir = Files.createTempDirectory("mika-outbox").toFile()
        try {
            val file = dir.resolve("outbox.json")
            val peer = ChannelPeer("local", "owner", "thread")
            val delivery = Delivery.Result("result:1", "thread", "回答", title = "任务完成")
            FileOutboxStore(file).save(DeliveryRecord(peer, delivery, DeliveryState.Attempting))
            val restored = FileOutboxStore(file).get("result:1")
            assertEquals(DeliveryRecord(peer, delivery, DeliveryState.Attempting), restored)
        } finally { dir.deleteRecursively() }
    }
}
