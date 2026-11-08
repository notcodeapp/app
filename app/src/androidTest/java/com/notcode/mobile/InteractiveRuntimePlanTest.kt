package com.notcode.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.notcode.mobile.runtime.LinuxRuntimeManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class InteractiveRuntimePlanTest {
    @Test fun interactivePlanUsesLoginSessionWithoutOneShotCommand() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = LinuxRuntimeManager(context)
        assumeTrue(manager.state.value.ubuntuReady)
        val builder = manager.interactiveBuilder("ubuntu", context.cacheDir.canonicalFile)
        val argv = builder.command()
        assertFalse(argv.contains("/system/bin/setsid"))
        assertFalse(argv.contains("/system/bin/sh"))
        assertTrue(argv.contains("-i"))
        assertTrue(argv.contains("TERM=xterm-256color"))
        assertFalse(argv.contains("-c"))
        assertEquals("xterm-256color", builder.environment()["TERM"])
    }
}
