package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.notcode.mobile.filesystem.ProjectPath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val vm: IdeViewModel
        get() = ViewModelProvider(compose.activity)[IdeViewModel::class.java]

    @Test fun welcomeAndNavigation() {
        compose.onNodeWithText("Открыть проект").assertIsDisplayed()
        compose.onNodeWithText("Агент", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Ваш помощник").assertIsDisplayed()
        compose.onNodeWithText("Объясни структуру проекта").performClick()
        compose.onAllNodesWithText("Объясни структуру проекта").assertCountEquals(2)
        compose.onNodeWithText("Терминал", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("shell:android").assertDoesNotExist()
        compose.onNodeWithTag("shell:ubuntu").assertIsDisplayed()
        compose.onNodeWithText("Git", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Откройте проект для работы с Git.").assertIsDisplayed()
        compose.onNodeWithText("Настройки", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Ваш ИИ. Ваши правила.").assertIsDisplayed()
        compose.onNodeWithText("Добавить модель").performScrollTo().performClick()
        compose.onNodeWithText("Новая модель").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Код", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Открыть проект").assertIsDisplayed()
    }

    @Test fun providerFormRejectsMissingFieldsAndOffersPresets() {
        compose.onNodeWithText("Настройки", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Добавить модель").performScrollTo().performClick()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithTag("provider-preset-picker").performClick()
        listOf("OpenAI", "DeepSeek", "Claude", "Qwen", "Gemini", "Groq", "OpenRouter").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("OpenAI", substring = false).performClick()
        compose.onNodeWithText("Название (необязательно)").assertExists()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithContentDescription("Файлы проекта").performClick()
        compose.onNodeWithText("ПРОВОДНИК").assertIsDisplayed()
    }

    @Test fun themeModesCanBeSelectedAndSurviveRecreation() {
        compose.onNodeWithText("Настройки", useUnmergedTree = true).performClick()
        listOf("dark", "light", "system").forEach { mode ->
            compose.onNodeWithTag("theme:$mode").performClick()
            compose.waitUntil { vm.themeMode.value == mode }
            compose.onNodeWithTag("theme:$mode").assertIsSelected()
        }
        compose.onNodeWithTag("theme:dark").performClick()
        compose.waitUntil { vm.themeMode.value == "dark" }
        compose.activityRule.scenario.recreate()
        compose.waitUntil { vm.themeMode.value == "dark" }
        compose.runOnIdle { vm.tab = 3 }
        compose.onNodeWithTag("theme:dark").assertIsSelected()
        compose.onNodeWithTag("theme:system").performClick()
        compose.waitUntil { vm.themeMode.value == "system" }
    }

    @Test fun assistantDropdownSelectsIndependentModelsFromSameProvider() {
        val first = "navigation-test-first"
        val second = "navigation-test-second"
        val original = vm.activeProfileId.value
        try {
            runBlocking {
                vm.saveProfile("", "https://example.com/v1", first, "", true, true, true, providerName = "OpenAI").join()
                vm.saveProfile("Вторая модель", "https://example.com/v1", second, "", true, true, true, providerName = "OpenAI").join()
            }
            compose.runOnIdle { vm.tab = 1 }
            compose.waitUntil { vm.profiles.value.any { it.modelId == second } }
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText(first).assertIsDisplayed().performClick()
            compose.waitUntil { vm.profiles.value.firstOrNull { it.id == vm.activeProfileId.value }?.modelId == first }
            compose.onNodeWithTag("assistant-model-picker").assertTextContains(first)
            compose.onNodeWithTag("assistant-model-picker").performClick()
            compose.onNodeWithText(second).performClick()
            compose.waitUntil { vm.profiles.value.firstOrNull { it.id == vm.activeProfileId.value }?.modelId == second }
            compose.onNodeWithTag("assistant-model-picker").assertTextContains("Вторая модель")
        } finally {
            runBlocking {
                vm.profiles.value.filter { it.modelId == first || it.modelId == second }.forEach { vm.deleteProfile(it).join() }
                original?.let { vm.selectProfile(it).join() }
            }
        }
    }

    @Test fun openingSwitchingAndClosingSecondFileDoesNotCrash() {
        val first = EditorDocument(ProjectPath.parse("first.kt"), "val first = 1", null)
        val second = EditorDocument(ProjectPath.parse("second.kt"), "val second = 2", null)
        compose.runOnIdle {
            vm.documents.clear()
            vm.documents.add(first)
            vm.selectedDocument = first
            vm.tab = 0
        }
        compose.onNodeWithTag("selected-file").assertTextEquals("first.kt")
        // Separate frames reproduce the transition from one open file to two.
        compose.runOnIdle { vm.documents.add(second) }
        compose.runOnIdle { vm.selectedDocument = second }
        compose.onNodeWithTag("selected-file").assertTextEquals("second.kt")
        compose.onNodeWithText("val second = 2").assertIsDisplayed()
        compose.onNodeWithTag("editor-tabs").performScrollToNode(hasTestTag("file-tab:first.kt"))
        compose.onNodeWithTag("file-tab:first.kt").performClick()
        compose.onNodeWithTag("selected-file").assertTextEquals("first.kt")
        compose.onNodeWithTag("editor-tabs").performScrollToNode(hasTestTag("file-tab:second.kt"))
        compose.onNodeWithTag("file-tab:second.kt").performClick()
        compose.onNodeWithContentDescription("Открыть список файлов").assertIsDisplayed()
        compose.onNodeWithContentDescription("Закрыть second.kt").performClick()
        compose.onNodeWithTag("selected-file").assertTextEquals("first.kt")
        compose.runOnIdle { assertEquals(listOf(first), vm.documents.toList()) }
        compose.onNodeWithContentDescription("Закрыть first.kt").performClick()
        compose.onNodeWithTag("editor-tabs").assertDoesNotExist()
    }
}
