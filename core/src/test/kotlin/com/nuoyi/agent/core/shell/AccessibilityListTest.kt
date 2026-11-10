package com.nuoyi.agent.core.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityListTest {
    private val nuoyi = PermissionCommands.accessibilityService("com.nuoyi.agent")
    private val nuoyiLong = "com.nuoyi.agent/com.nuoyi.agent.a11y.NuoyiAccessibilityService"
    private val other = "com.example.reader/.ReaderService"
    private val other2 = "org.sample.keys/org.sample.keys.KeyService"

    @Test
    fun emptyOrUnsetValues() {
        listOf(null, "", "   ", "null", "null\n", ":", " : ").forEach { raw ->
            assertEquals("raw=$raw", emptyList<String>(), AccessibilityList.entries(raw))
            assertFalse(AccessibilityList.contains(raw, nuoyi))
            assertEquals(nuoyi, AccessibilityList.with(raw, nuoyi))
            assertEquals("", AccessibilityList.without(raw, nuoyi))
        }
    }

    @Test
    fun appendKeepsOtherServicesInOrder() {
        assertEquals("$other:$nuoyi", AccessibilityList.with(other, nuoyi))
        assertEquals("$other:$other2:$nuoyi", AccessibilityList.with("$other:$other2\n", nuoyi))
    }

    @Test
    fun alreadyPresentIsNotDuplicated() {
        assertEquals("$other:$nuoyi", AccessibilityList.with("$other:$nuoyi", nuoyi))
        // 系统里存的是完整类名写法,也算已经有了
        assertEquals("$nuoyiLong:$other", AccessibilityList.with("$nuoyiLong:$other", nuoyi))
        assertTrue(AccessibilityList.contains(nuoyiLong, nuoyi))
    }

    @Test
    fun removeKeepsOthersAndHandlesBothSpellings() {
        assertEquals("$other:$other2", AccessibilityList.without("$other:$nuoyi:$other2", nuoyi))
        assertEquals(other, AccessibilityList.without("$nuoyiLong:$other", nuoyi))
        assertEquals(other, AccessibilityList.without(other, nuoyi))
        assertEquals("", AccessibilityList.without(nuoyi, nuoyi))
    }

    @Test
    fun strayColonsAndSpacesAreCleanedUp() {
        assertEquals("$other:$other2:$nuoyi", AccessibilityList.with(":$other:: $other2 :", nuoyi))
        assertEquals("$other:$other2", AccessibilityList.without("$other::$nuoyi:$other2:", nuoyi))
    }

    @Test
    fun differentServiceOfSamePackageIsNotConfused() {
        val sibling = "com.nuoyi.agent/.a11y.OtherService"
        assertFalse(AccessibilityList.contains(sibling, nuoyi))
        assertEquals(sibling, AccessibilityList.without("$sibling:$nuoyi", nuoyi))
    }
}
