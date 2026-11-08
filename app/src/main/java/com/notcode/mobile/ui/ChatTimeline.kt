package com.notcode.mobile.ui

import com.notcode.mobile.ChatItem

sealed interface ChatTimelineEntry {
    val id: String

    data class Message(val item: ChatItem) : ChatTimelineEntry {
        override val id: String get() = item.id
    }

    data class Actions(override val id: String, val items: List<ChatItem>) : ChatTimelineEntry
}

/** Groups only adjacent actions; messages remain in their original chronological position. */
fun buildChatTimeline(items: List<ChatItem>): List<ChatTimelineEntry> = buildList {
    val actions = mutableListOf<ChatItem>()
    fun flushActions() {
        if (actions.isNotEmpty()) {
            add(ChatTimelineEntry.Actions(actions.first().id, actions.toList()))
            actions.clear()
        }
    }
    items.forEach { item ->
        if (item.role == "Инструмент" || item.role == "Подагент") {
            actions.add(item)
        } else {
            flushActions()
            add(ChatTimelineEntry.Message(item))
        }
    }
    flushActions()
}

fun actionCountLabel(count: Int): String {
    val noun = when {
        count % 100 in 11..14 -> "действий"
        count % 10 == 1 -> "действие"
        count % 10 in 2..4 -> "действия"
        else -> "действий"
    }
    return "$count $noun"
}
