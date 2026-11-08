package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test

class ChatInputRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun draftCanBeTypedWhileAgentRunsAndActionsStartCollapsed() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        compose.runOnIdle {
            vm.tab = 1
            vm.chat.clear()
            vm.chat.add(ChatItem("Агент", "Проверяю проект"))
            repeat(23) { vm.chat.add(ChatItem("Инструмент", "Действие номер $it", successful = true)) }
            vm.chat.add(ChatItem("Агент", "Готово"))
            vm.agentRunning = true
        }
        compose.onNodeWithTag("chat-input").performTextInput("Следующий вопрос")
        compose.onNodeWithTag("chat-input").assertTextContains("Следующий вопрос")
        compose.onNodeWithText("Действие номер 0").assertDoesNotExist()
        compose.onNodeWithText("23 действия").performScrollTo().performClick()
        compose.onNodeWithText("Действие номер 0").assertExists()
        compose.onNodeWithText("23 действия").performScrollTo().performClick()
        compose.onNodeWithText("Действие номер 0").assertDoesNotExist()
        compose.runOnIdle { vm.agentRunning = false }
    }
}
