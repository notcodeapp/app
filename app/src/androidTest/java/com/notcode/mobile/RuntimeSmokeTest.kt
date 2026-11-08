package com.notcode.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.notcode.mobile.runtime.LinuxRuntimeManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class RuntimeSmokeTest {
    @Test fun installUbuntuThenPowerShellAndExecute() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = LinuxRuntimeManager(context)
        assertNull(manager.state.value.unsupportedReason)
        manager.installUbuntu()
        assertTrue(manager.state.value.ubuntuReady)
        val workspace = File(context.cacheDir.canonicalFile, "runtime-smoke").apply { mkdirs() }
        manager.commandBuilder("ubuntu", "printf ubuntu-ok > test.txt; cat test.txt; . /etc/os-release; printf \"%s\" \"\$ID\"", workspace).redirectErrorStream(true).start().let { child ->
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.waitFor())
            assertTrue(output, output.contains("ubuntu-okubuntu"))
        }
        assertEquals("ubuntu-ok", File(workspace, "test.txt").readText())
        manager.installPowerShell()
        assertTrue(manager.state.value.powershellReady)
        manager.commandBuilder("powershell", "Write-Output ('pwsh-' + (2+3))", workspace).redirectErrorStream(true).start().let { child ->
            val output = child.inputStream.bufferedReader().readText()
            assertEquals(output, 0, child.waitFor())
            assertTrue(output, output.contains("pwsh-5"))
        }
    }
}
