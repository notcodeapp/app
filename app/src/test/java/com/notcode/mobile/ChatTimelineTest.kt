package com.notcode.mobile

import com.notcode.mobile.ui.ChatTimelineEntry
import com.notcode.mobile.ui.actionCountLabel
import com.notcode.mobile.ui.buildChatTimeline
import org.junit.Assert.*
import org.junit.Test

class ChatTimelineTest {
    private fun item(id: String, role: String) = ChatItem(role, "Текст $id", id)

    @Test fun emptyChatHasNoEntries() {
        assertTrue(buildChatTimeline(emptyList()).isEmpty())
    }

    @Test fun messagesKeepTheirOrderAndContents() {
        val messages = listOf(item("user", "Вы"), item("assistant", "Агент"), item("system", "Система"))
        assertEquals(messages.map { ChatTimelineEntry.Message(it) }, buildChatTimeline(messages))
    }

    @Test fun consecutiveToolsAndSubagentsBecomeOneActionBlock() {
        val actions = listOf(item("tool-1", "Инструмент"), item("subagent", "Подагент"), item("tool-2", "Инструмент"))
        assertEquals(listOf(ChatTimelineEntry.Actions("tool-1", actions)), buildChatTimeline(actions))
        assertEquals("3 действия", actionCountLabel((buildChatTimeline(actions).single() as ChatTimelineEntry.Actions).items.size))
    }

    @Test fun messagesSplitActionBlocksWithoutChangingChronology() {
        val first = item("first", "Инструмент")
        val assistant = item("assistant", "Агент")
        val second = item("second", "Подагент")
        val user = item("user", "Вы")
        val third = item("third", "Инструмент")
        val system = item("system", "Система")
        val fourth = item("fourth", "Инструмент")
        assertEquals(
            listOf(
                ChatTimelineEntry.Actions(first.id, listOf(first)),
                ChatTimelineEntry.Message(assistant),
                ChatTimelineEntry.Actions(second.id, listOf(second)),
                ChatTimelineEntry.Message(user),
                ChatTimelineEntry.Actions(third.id, listOf(third)),
                ChatTimelineEntry.Message(system),
                ChatTimelineEntry.Actions(fourth.id, listOf(fourth))
            ),
            buildChatTimeline(listOf(first, assistant, second, user, third, system, fourth))
        )
    }

    @Test fun updatingAnActionDoesNotDoubleCountOrChangeGroupId() {
        val started = item("tool", "Инструмент")
        val finished = started.copy(text = "Готово", filePath = "src/main.kt", successful = true)
        val before = buildChatTimeline(listOf(started)).single() as ChatTimelineEntry.Actions
        val after = buildChatTimeline(listOf(finished)).single() as ChatTimelineEntry.Actions
        assertEquals(before.id, after.id)
        assertEquals(1, after.items.size)
        assertEquals(finished, after.items.single())
    }

    @Test fun appendingActionsPreservesFirstActionIdAndEarlierSnapshot() {
        val first = item("first", "Подагент")
        val second = item("second", "Инструмент")
        val before = buildChatTimeline(listOf(first)).single() as ChatTimelineEntry.Actions
        val after = buildChatTimeline(listOf(first, second)).single() as ChatTimelineEntry.Actions
        assertEquals(first.id, after.id)
        assertEquals(listOf(first, second), after.items)
        assertEquals(listOf(first), before.items)
    }

    @Test fun twentyThreeActionsHaveOneEntryAndCorrectLabel() {
        val actions = (1..23).map { item("tool-$it", "Инструмент") }
        val group = buildChatTimeline(actions).single() as ChatTimelineEntry.Actions
        assertEquals("tool-1", group.id)
        assertEquals(23, group.items.size)
        assertEquals("23 действия", actionCountLabel(group.items.size))
    }

    @Test fun russianActionCountDeclension() {
        val expected = mapOf(
            0 to "0 действий", 1 to "1 действие", 2 to "2 действия", 4 to "4 действия",
            5 to "5 действий", 10 to "10 действий", 11 to "11 действий", 12 to "12 действий",
            13 to "13 действий", 14 to "14 действий", 20 to "20 действий", 21 to "21 действие",
            22 to "22 действия", 25 to "25 действий", 101 to "101 действие", 111 to "111 действий",
            112 to "112 действий", 113 to "113 действий", 114 to "114 действий", 121 to "121 действие"
        )
        expected.forEach { (count, label) -> assertEquals(label, actionCountLabel(count)) }
    }
}
