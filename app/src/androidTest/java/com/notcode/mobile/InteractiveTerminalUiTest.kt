package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class InteractiveTerminalUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openTerminalRenderAndCloseSession() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        assumeTrue(vm.runtimeState.value.ubuntuReady)
        compose.onNodeWithText("Терминал", useUnmergedTree = true).performClick()
        compose.runOnIdle { vm.selectShell("ubuntu") }
        compose.waitForIdle()
        compose.onNodeWithTag("terminal-open").assertIsDisplayed()
        android.util.Log.i("PtyUiTest", "before open")
        compose.onNodeWithTag("terminal-open").performClick()
        android.util.Log.i("PtyUiTest", "after open")
        compose.waitUntil(10000) { vm.approval != null }
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.decide(true) }
        try { compose.waitUntil(10000) { vm.interactiveRunning } } catch (error: Throwable) {
            android.util.Log.e("PtyUiTest", "Start failed: ${vm.message}, approval=${vm.approval?.title}, running=${vm.interactiveRunning}", error)
            throw error
        }
        compose.onNodeWithTag("interactive-terminal").assertIsDisplayed()
        compose.onNodeWithText("Ctrl+C").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Закрыть сессию терминала").performClick()
        compose.waitUntil(10000) { !vm.interactiveRunning }
        compose.onNodeWithTag("terminal-open").assertIsDisplayed()
    }
}
