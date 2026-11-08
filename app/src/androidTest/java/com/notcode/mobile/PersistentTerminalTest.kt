package com.notcode.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.notcode.mobile.runtime.LinuxRuntimeManager
import com.notcode.mobile.runtime.pty.PtySession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PersistentTerminalTest {
    @Test fun ttyPreservesStateAcceptsInputAndInterruptsForegroundJob() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val manager = LinuxRuntimeManager(context)
        assumeTrue(manager.state.value.ubuntuReady)
        val work = File(context.cacheDir.canonicalFile, "pty-test-${System.nanoTime()}").apply { mkdirs() }
        var session: PtySession? = null
        val exited = CountDownLatch(1)
        instrumentation.runOnMainSync {
            session = PtySession.start(manager.interactiveBuilder("ubuntu", work), 80, 24)
            session!!.addListener(object : PtySession.Listener {
                override fun onExit(session: PtySession, exitStatus: Int) { exited.countDown() }
            })
            session!!.write("export NOTCODE_PTY_TEST=retained\r")
            session!!.write("test -t 0 && printf \"%s\" \"\$NOTCODE_PTY_TEST\" > /workspace/state.txt\r")
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!File(work, "state.txt").exists() && System.nanoTime() < deadline) kotlinx.coroutines.delay(50)
        assertEquals("retained", File(work, "state.txt").readText())
        instrumentation.runOnMainSync { session!!.write("printf ready > /workspace/ready.txt; read -r answer; printf \"%s\" \"\$answer\" > /workspace/answer.txt\r") }
        val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!File(work, "ready.txt").exists() && System.nanoTime() < readyDeadline) kotlinx.coroutines.delay(50)
        instrumentation.runOnMainSync { session!!.write("hello interactive\r") }
        val inputDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!File(work, "answer.txt").exists() && System.nanoTime() < inputDeadline) kotlinx.coroutines.delay(50)
        assertEquals("hello interactive", File(work, "answer.txt").readText())
        instrumentation.runOnMainSync { session!!.write("sleep 60\r") }
        kotlinx.coroutines.delay(300)
        instrumentation.runOnMainSync { session!!.sendCtrlC(); session!!.write("printf interrupted > /workspace/interrupt.txt\r") }
        val stopDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!File(work, "interrupt.txt").exists() && System.nanoTime() < stopDeadline) kotlinx.coroutines.delay(50)
        assertEquals("interrupted", File(work, "interrupt.txt").readText())
        instrumentation.runOnMainSync { session!!.finish() }
        assertTrue("Session must exit after close", exited.await(10, TimeUnit.SECONDS))
    }
}
