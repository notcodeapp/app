package com.notcode.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.notcode.mobile.filesystem.ProjectPath
import org.junit.Rule
import org.junit.Test
import java.io.File

class BrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun previewMenuAndTelegramEntryAreAvailable() {
        val vm = ViewModelProvider(compose.activity)[IdeViewModel::class.java]
        val project = File(compose.activity.filesDir.canonicalFile, "projects/browser-ui-${System.nanoTime()}")
        val root = File(project, "work").apply { mkdirs() }
        File(root, "index.html").writeText("<html><body>Preview test</body></html>")
        try {
            compose.runOnIdle { vm.preview.openProjectFile(root, ProjectPath.parse("index.html")) }
            compose.onNodeWithText("⋮").performClick()
            compose.onNodeWithText("Обновить страницу").assertIsDisplayed()
            compose.onNodeWithText("Сохранить страницу в проект").assertIsDisplayed()
            compose.onNodeWithText("Открыть с помощью…").assertIsDisplayed()
            compose.onNodeWithText("Обновить страницу").performClick()
            compose.onNodeWithText("Назад").performClick()
            compose.runOnIdle { vm.tab = 3 }
            compose.onNodeWithTag("telegram-channel").assertIsDisplayed()
            compose.onNodeWithText("Добавить модель").performScrollTo().performClick()
            compose.onNodeWithText("Подписаться").assertExists()
            compose.onNodeWithText("Отмена").performClick()
        } finally { compose.runOnIdle { vm.preview.close() }; project.deleteRecursively() }
    }
}
