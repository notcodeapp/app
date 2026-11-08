package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.notcode.mobile.runtime.RuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

class TerminalInstallStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun eachShellShowsOnlyItsOwnInstallationState() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        val state = vm.runtimeState as MutableStateFlow<RuntimeState>
        val original = state.value
        try {
            compose.runOnIdle { state.value = RuntimeState(); vm.tab = 2; vm.selectShell("ubuntu") }
            compose.onNodeWithText("Ubuntu — не установлено").assertIsDisplayed()
            compose.onNodeWithText("Скачать Ubuntu").assertIsDisplayed()
            compose.onNodeWithTag("terminal-input").assertDoesNotExist()
            compose.onNodeWithTag("shell:powershell").performClick()
            compose.onNodeWithText("PowerShell — не установлено").assertIsDisplayed()
            compose.onNodeWithTag("terminal-input").assertDoesNotExist()
            compose.runOnIdle { state.value = RuntimeState(ubuntuReady = true) }
            compose.onNodeWithText("Скачать PowerShell").assertIsDisplayed()
            compose.onNodeWithTag("shell:ubuntu").performClick()
            compose.onNodeWithTag("terminal-open").assertIsDisplayed()
            compose.onNodeWithTag("shell-not-installed").assertDoesNotExist()
            compose.onNodeWithTag("shell:powershell").performClick()
            compose.onNodeWithText("Скачать PowerShell").assertIsDisplayed()
            compose.runOnIdle { state.value = RuntimeState(ubuntuReady = true, powershellReady = true) }
            compose.onNodeWithTag("terminal-open").assertIsDisplayed()
        } finally { compose.runOnIdle { state.value = original } }
    }
}
