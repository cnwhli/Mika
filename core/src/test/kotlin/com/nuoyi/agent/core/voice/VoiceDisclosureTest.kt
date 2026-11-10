package com.nuoyi.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDisclosureTest {
    @Test fun defaultConsentBlocksVoice() {
        assertFalse(VoiceDisclosure.canUseVoice(VoiceConsent()))
        assertTrue(VoiceDisclosure.canUseVoice(VoiceConsent.granted()))
    }

    @Test fun everyRequiredItemMustBeAcknowledged() {
        val all = VoiceConsent.granted()
        assertFalse(VoiceDisclosure.canUseVoice(all.copy(acknowledged = false)))
        assertFalse(VoiceDisclosure.canUseVoice(all.copy(microphone = false)))
        assertFalse(VoiceDisclosure.canUseVoice(all.copy(screenCapture = false)))
        assertFalse(VoiceDisclosure.canUseVoice(all.copy(freeTierTrainingUnderstood = false)))
        assertFalse(VoiceDisclosure.canUseVoice(all.copy(ownCredentialsConfirmed = false)))
    }

    @Test fun checklistCoversAllFourDisclosures() {
        val items = VoiceDisclosure.checklist()
        assertEquals(setOf("mic", "screen", "training", "quota"), items.map { it.key }.toSet())
        assertTrue(items.all { it.required })
        assertTrue(items.all { it.detail.isNotBlank() })
    }

    @Test fun quotaPolicyStatesTheBoundary() {
        val text = VoiceDisclosure.QUOTA_POLICY
        assertTrue(text.contains("官方公开"))
        assertTrue(text.contains("不借用"))
        assertTrue(text.contains("共享 Key"))
    }

    @Test fun trainingFreeTierNeedsUnderstanding() {
        assertFalse(VoiceDisclosure.allowsTrainingFreeTier(VoiceConsent(), freeTierTrainsByDefault = true))
        assertTrue(VoiceDisclosure.allowsTrainingFreeTier(VoiceConsent.granted(), freeTierTrainsByDefault = true))
        // 不训练的档位与这个开关无关
        assertTrue(VoiceDisclosure.allowsTrainingFreeTier(VoiceConsent(), freeTierTrainsByDefault = false))
    }
}
