package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Rule
import org.junit.Test

class ChatActionsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun messageBubblesAreNarrowAndAlignedBySender() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        val user = ChatItem("Вы", "Короткий вопрос")
        val agent = ChatItem("Агент", "Ответ помощника")
        compose.runOnIdle { vm.tab = 1; vm.chat.clear(); vm.chat.add(user); vm.chat.add(agent) }
        val chat = compose.onNodeWithTag("agent-chat").fetchSemanticsNode().boundsInRoot
        val userBounds = compose.onNodeWithTag("message-bubble:${user.id}").fetchSemanticsNode().boundsInRoot
        val agentBounds = compose.onNodeWithTag("message-bubble:${agent.id}").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(userBounds.width < chat.width * 0.9f)
        org.junit.Assert.assertTrue(agentBounds.width < chat.width * 0.9f)
        org.junit.Assert.assertTrue(userBounds.right > agentBounds.right)
        org.junit.Assert.assertTrue(userBounds.left > agentBounds.left)
    }
    @Test fun messagesKeepChronologyAndOfferCopy() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        compose.runOnIdle {
            vm.tab = 1
            vm.chat.clear()
            vm.chat.add(ChatItem("Агент", "Сначала проверю файл"))
            vm.chat.add(ChatItem("Инструмент", "Прочитан файл: 2 строки", successful = true))
            vm.chat.add(ChatItem("Агент", "Проверка завершена"))
        }
        compose.onNodeWithText("Проверка завершена").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Копировать сообщение").onLast().performClick()
        compose.onNodeWithText("Проверка завершена").assertIsDisplayed()
    }
}
