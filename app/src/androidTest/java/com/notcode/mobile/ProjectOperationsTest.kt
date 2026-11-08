package com.notcode.mobile

import android.app.Application
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.notcode.mobile.filesystem.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ProjectOperationsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val vm get() = ViewModelProvider(compose.activity)[IdeViewModel::class.java]

    @Test fun fileOpenCommandAndGitUseRealWorkspace() {
        val root = File(compose.activity.application.cacheDir.canonicalFile, "operations-${System.nanoTime()}")
        val fs = WorkspaceProjectFileSystem(root)
        runBlocking {
            fs.writeText(ProjectPath.parse("first.kt"), "val first = 1")
            fs.writeText(ProjectPath.parse("second.kt"), "val second = 2")
        }
        try {
            compose.runOnIdle {
                IdeViewModel::class.java.getDeclaredField("fileSystem\$delegate").let { field ->
                    field.isAccessible = true
                    val state = field.get(vm) as androidx.compose.runtime.MutableState<WorkspaceProjectFileSystem?>
                    state.value = fs
                }
            }
            runBlocking { withContext(Dispatchers.Main) { vm.openFile(ProjectPath.parse("first.kt")) }.join() }
            compose.runOnIdle { assertEquals("first.kt", vm.selectedDocument?.path?.value) }
            runBlocking { withContext(Dispatchers.Main) { vm.openFile(ProjectPath.parse("second.kt")) }.join() }
            compose.runOnIdle { assertEquals(2, vm.documents.size); assertEquals("second.kt", vm.selectedDocument?.path?.value) }
            compose.runOnIdle { vm.command("printf notcode-command-ok") }
            compose.waitUntil { vm.approval != null }
            compose.runOnIdle { vm.decide(false) }
            compose.waitUntil { vm.message?.contains("отклонена") == true }
            compose.runOnIdle { vm.message = null; vm.command("printf notcode-command-ok") }
            compose.waitUntil { vm.approval != null }
            compose.runOnIdle { vm.decide(true) }
            compose.waitUntil(10000) { vm.terminal.contains("notcode-command-ok\n[exit 0]") }
            var init: Job? = null
            compose.runOnIdle { init = vm.git("init") }
            compose.waitUntil { vm.approval != null }
            compose.runOnIdle { vm.decide(true) }
            runBlocking { init!!.join() }
            compose.runOnIdle { assertTrue(vm.gitOutput.contains("создан")) }
            var commit: Job? = null
            compose.runOnIdle { commit = vm.git("commit", "first commit") }
            compose.waitUntil { vm.approval != null }
            compose.runOnIdle { vm.decide(true) }
            runBlocking { commit!!.join() }
            compose.runOnIdle { assertTrue(vm.gitOutput.contains("first commit")) }
        } finally { root.deleteRecursively() }
    }
}
