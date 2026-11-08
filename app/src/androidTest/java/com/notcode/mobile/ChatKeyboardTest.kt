package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChatKeyboardTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun editorAndTerminalRiseWithoutMovingAppBars() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        compose.runOnIdle {
            val doc = EditorDocument(com.notcode.mobile.filesystem.ProjectPath.parse("keyboard.kt"), "val value = 1", null)
            vm.documents.add(doc); vm.selectedDocument = doc; vm.tab = 0
        }
        val header = compose.onNodeWithContentDescription("NotCode").fetchSemanticsNode().boundsInRoot.top
        val keysBefore = compose.onNodeWithTag("editor-extra-keys").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithTag("editor-input").performClick()
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("editor-extra-keys").fetchSemanticsNode().boundsInRoot.bottom < keysBefore - 100f)
        assertEquals(header, compose.onNodeWithContentDescription("NotCode").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false }
        compose.runOnIdle { vm.tab = 2 }
        val before = compose.onNodeWithTag("terminal-input").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithTag("terminal-input").performClick()
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("terminal-input").fetchSemanticsNode().boundsInRoot.bottom < before - 100f)
        assertEquals(header, compose.onNodeWithContentDescription("NotCode").fetchSemanticsNode().boundsInRoot.top, 2f)
        compose.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    }

    @Test fun keyboardShrinksChatWithoutMovingAppBars() {
        compose.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
        compose.waitUntil(10000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
        compose.waitForIdle()
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        compose.runOnIdle {
            vm.tab = 1
            repeat(12) { vm.chat.add(ChatItem("Агент", "Сообщение $it")) }
        }
        val headerBefore = compose.onNodeWithContentDescription("NotCode").fetchSemanticsNode().boundsInRoot
        val navBefore = compose.onNodeWithText("Код", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val chatBefore = compose.onNodeWithTag("agent-chat").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitUntil(10000) {
            ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        compose.waitForIdle()
        val headerAfter = compose.onNodeWithContentDescription("NotCode").fetchSemanticsNode().boundsInRoot
        val navAfter = compose.onNodeWithText("Код", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(headerBefore.top, headerAfter.top, 2f)
        assertEquals(navBefore.top, navAfter.top, 2f)
        val input = compose.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Input should rise above its closed-keyboard position", input.bottom < chatBefore.bottom - 100f)
        compose.onNodeWithText("Сообщение 11").assertIsDisplayed()
        compose.runOnIdle { androidx.core.view.WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView).hide(WindowInsetsCompat.Type.ime()) }
    }
}
