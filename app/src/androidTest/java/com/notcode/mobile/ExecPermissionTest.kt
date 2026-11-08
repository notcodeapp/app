package com.notcode.mobile

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/**
 * PRoot запускает бинарники из data-каталога приложения, и SELinux разрешает это только
 * legacy-доменам (targetSdk <= 28). Тест падает сразу, как только targetSdk поднимут выше.
 */
class ExecPermissionTest {
    @Test fun executesOwnBinaryFromDataDir() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // toybox выбирает апплет по имени файла, поэтому копия остаётся называться toybox.
        val probe = File(context.filesDir, "toybox")
        File("/system/bin/toybox").copyTo(probe, overwrite = true)
        assertTrue("не удалось выставить права", probe.setExecutable(true, true))
        val child = ProcessBuilder(probe.absolutePath, "echo", "exec-ok").redirectErrorStream(true).start()
        val output = child.inputStream.bufferedReader().readText()
        assertTrue("процесс не завершился", child.waitFor(10, TimeUnit.SECONDS))
        assertEquals(output, 0, child.exitValue())
        assertTrue(output, output.contains("exec-ok"))
        probe.delete()
    }
}
