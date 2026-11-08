package com.notcode.mobile

import androidx.compose.ui.text.AnnotatedString
import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.ui.messageFileLinks
import org.junit.Assert.*
import org.junit.Test

class ChatFileLinksTest {
    @Test fun linksOnlyKnownExactOrUniqueFileNames() {
        val paths = listOf("src/main.kt", "src/one.kt", "test/main.kt").map(ProjectPath::parse)
        val text = "Изменён src/main.kt, one.kt. main.kt неизвестен; missing.kt нет. https://example.com/src/main.kt"
        val result = messageFileLinks(AnnotatedString(text), paths)
        assertEquals(listOf("src/main.kt", "src/one.kt"), result.getStringAnnotations("project-file", 0, result.length).map { it.item })
    }
}
