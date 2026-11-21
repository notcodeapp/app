package com.notcode.mobile.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushActionTargetTest {
    @Test
    fun `updates and settings open the settings tab`() {
        assertEquals(PushActionTarget.TAB_SETTINGS, PushActionTarget.tabFor(PushActionTarget.OPEN_UPDATES))
        assertEquals(PushActionTarget.TAB_SETTINGS, PushActionTarget.tabFor(PushActionTarget.OPEN_SETTINGS))
    }

    @Test
    fun `projects open the editor tab and the projects drawer`() {
        assertEquals(PushActionTarget.TAB_EDITOR, PushActionTarget.tabFor(PushActionTarget.OPEN_PROJECTS))
        assertTrue(PushActionTarget.opensProjectsDrawer(PushActionTarget.OPEN_PROJECTS))
        assertFalse(PushActionTarget.opensProjectsDrawer(PushActionTarget.OPEN_UPDATES))
    }

    @Test
    fun `updates request the settings scroll`() {
        assertTrue(PushActionTarget.scrollsToUpdates(PushActionTarget.OPEN_UPDATES))
        assertFalse(PushActionTarget.scrollsToUpdates(PushActionTarget.OPEN_SETTINGS))
    }

    @Test
    fun `app and external url keep the current screen`() {
        assertNull(PushActionTarget.tabFor(PushActionTarget.OPEN_APP))
        assertNull(PushActionTarget.tabFor(PushActionTarget.OPEN_URL))
        assertTrue(PushActionTarget.isPassthrough(PushActionTarget.OPEN_APP))
        assertTrue(PushActionTarget.isPassthrough(PushActionTarget.OPEN_URL))
        assertTrue(PushActionTarget.isPassthrough(""))
        assertFalse(PushActionTarget.isPassthrough(PushActionTarget.OPEN_PROJECTS))
    }
}
