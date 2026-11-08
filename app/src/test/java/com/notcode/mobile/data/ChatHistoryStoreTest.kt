package com.notcode.mobile.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatHistoryStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun roundTripPreservesConversations() {
        val store = ChatHistoryStore(temp.root)
        val conversation = StoredConversation(
            "id-1", "Привет", 1000L,
            listOf(StoredChatItem("Вы", "Привет", "m1"), StoredChatItem("Агент", "Здравствуйте", "m2", filePath = "a.kt", successful = true)),
            listOf(StoredTurn("user", "Привет"), StoredTurn("assistant", "Здравствуйте")),
        )
        store.save(listOf(conversation))
        val loaded = ChatHistoryStore(temp.root).load()
        assertEquals(listOf(conversation), loaded)
    }

    @Test fun corruptOrOversizeFileLoadsEmpty() {
        File(temp.root, "chat-history.json").writeText("{not json")
        assertTrue(ChatHistoryStore(temp.root).load().isEmpty())
        assertTrue(ChatHistoryStore(temp.newFolder("empty")).load().isEmpty())
    }

    @Test fun saveBoundsConversationsAndMessages() {
        val store = ChatHistoryStore(temp.root)
        val many = (1..60).map { index ->
            StoredConversation("id-$index", "Чат $index", index.toLong(),
                items = (1..500).map { StoredChatItem("Агент", "текст $it", "m$it") },
                turns = (1..200).map { StoredTurn("user", "вопрос $it") })
        }
        store.save(many)
        val loaded = ChatHistoryStore(temp.root).load()
        assertEquals(50, loaded.size)
        assertEquals(60L, loaded.first().updatedAt)
        assertEquals(400, loaded.first().items.size)
        assertEquals(100, loaded.first().turns.size)
    }

    @Test fun saveIsAtomicAndOverwrites() {
        val store = ChatHistoryStore(temp.root)
        store.save(listOf(StoredConversation("a", "Первый", 1L)))
        store.save(listOf(StoredConversation("b", "Второй", 2L)))
        val loaded = ChatHistoryStore(temp.root).load()
        assertEquals(1, loaded.size)
        assertEquals("b", loaded.single().id)
        assertFalse(File(temp.root, "chat-history.tmp").exists())
    }
}