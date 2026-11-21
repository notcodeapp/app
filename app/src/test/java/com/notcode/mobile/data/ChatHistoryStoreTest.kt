package com.notcode.mobile.data

import com.notcode.mobile.desktop.DesktopChatSource
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
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
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.startsWith("chat-history-") && it.name.endsWith(".tmp") })
    }

    @Test fun utf8ByteLimitRejectsWithoutDestroyingOriginal() {
        val store = ChatHistoryStore(temp.root)
        store.save(listOf(StoredConversation("kept", "Сохранён", 1L)))
        val oversizedUtf8 = StoredConversation("large", "Большой", 2L,
            items = listOf(StoredChatItem("Вы", "я".repeat(2_100_000), "m")))

        val failure = runCatching { store.save(listOf(oversizedUtf8)) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("kept", store.load().single().id)
    }

    @Test fun exactSaveRejectsResultsThatWouldBeTruncated() {
        val store = ChatHistoryStore(temp.root)
        store.save(listOf(StoredConversation("kept", "Сохранён", 1L)))
        val tooMany = (1..51).map { StoredConversation("id-$it", "Чат", it.toLong()) }

        assertTrue(runCatching { store.save(tooMany, requireExact = true) }.isFailure)
        assertEquals("kept", store.load().single().id)
    }

    @Test fun revisionsCoalesceStaleSaveAndKeepLocalContinuation() = runBlocking {
        val store = ChatHistoryStore(temp.root)
        val writer = ChatHistoryWriter()
        val source = DesktopChatSource("https://pc", "fingerprint", "desktop-project", "Desktop",
            "session", "provider", "model")
        val imported = StoredConversation("chat", "Imported", 1L,
            items = listOf(StoredChatItem("ИИ", "remote", "remote-1")), desktopSource = source)
        val importSnapshot = writer.snapshot(store, listOf(imported), requireExact = true)
        val continuation = imported.copy(updatedAt = 2L,
            items = imported.items + StoredChatItem("Вы", "local continuation", "local-1"))
        writer.snapshot(store, listOf(continuation))

        writer.save(importSnapshot)

        val loaded = store.load().single()
        assertEquals(listOf("remote-1", "local-1"), loaded.items.map { it.id })
        assertEquals(source, loaded.desktopSource)
    }

    @Test fun normalSavePreservesPersistedDesktopProvenance() {
        val store = ChatHistoryStore(temp.root)
        val source = DesktopChatSource("https://pc", "fingerprint", "desktop-project", "Desktop",
            "session", "provider", "model")
        store.save(listOf(StoredConversation("chat", "Imported", 1L, desktopSource = source)))

        store.save(listOf(StoredConversation("chat", "Continued", 2L,
            items = listOf(StoredChatItem("Вы", "local", "local-1")))))

        assertEquals(source, store.load().single().desktopSource)
    }

    @Test fun destinationRejectsProjectSwitchAndSsh() {
        val firstStore = ChatHistoryStore(temp.newFolder("first"))
        val secondStore = ChatHistoryStore(temp.newFolder("second"))
        val firstWorkspace = Any()
        val destination = ChatImportDestination(firstStore, firstWorkspace)

        destination.requireCurrent(firstStore, firstWorkspace, sshActive = false)
        assertTrue(runCatching { destination.requireCurrent(secondStore, firstWorkspace, sshActive = false) }.isFailure)
        assertTrue(runCatching { destination.requireCurrent(firstStore, Any(), sshActive = false) }.isFailure)
        assertTrue(runCatching { destination.requireCurrent(firstStore, firstWorkspace, sshActive = true) }.isFailure)
    }
}