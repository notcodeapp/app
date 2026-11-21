package com.notcode.mobile.desktop

import com.notcode.mobile.data.ChatHistoryStore
import com.notcode.mobile.data.StoredChatItem
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class DesktopTransferTest {
    @Test fun threeWaySyncNeverOverwritesBothChangedOrUntrackedCollisions() {
        assertEquals(SyncDecision.SAME, syncDecision("base", "base", "base"))
        assertEquals(SyncDecision.SAME, syncDecision("base", "same-edit", "same-edit"))
        assertEquals(SyncDecision.PUSH, syncDecision("base", "local-edit", "base"))
        assertEquals(SyncDecision.PULL, syncDecision("base", "base", "remote-edit"))
        assertEquals(SyncDecision.CONFLICT, syncDecision("base", "local-edit", "remote-edit"))
        assertEquals(SyncDecision.CONFLICT, syncDecision(null, "untracked", "different-remote"))
        assertEquals(SyncDecision.PUSH, syncDecision(null, "new-local", null))
        assertEquals(SyncDecision.PULL, syncDecision(null, null, "new-remote"))
        assertEquals(SyncDecision.DELETION_UNSUPPORTED, syncDecision("base", null, "base"))
        assertEquals(SyncDecision.DELETION_UNSUPPORTED, syncDecision("base", "base", null))
        assertEquals(SyncDecision.DELETION_UNSUPPORTED, syncDecision("base", "changed", null))
        // Existing local files are never overwritten even when the three-way result is PULL.
        assertEquals(SyncDecision.CONFLICT, desktopTransferDecision("base", "base", "remote-edit"))
        assertEquals(SyncDecision.PULL, desktopTransferDecision(null, null, "new-remote"))
    }
    @Test fun workspaceBomIsPreservedForHashCasInsteadOfBecomingAFakeLocalEdit() {
        val text = "\uFEFFhello"
        val document = com.notcode.mobile.filesystem.TextDocument(com.notcode.mobile.filesystem.ProjectPath.parse("a.txt"), "hello",
            com.notcode.mobile.filesystem.FileRevision("file:a.txt#sha256=${textHash(text)}", text.toByteArray().size.toLong(), 0))
        assertEquals(text, preserveDesktopWorkspaceBom(document).text)
        assertEquals(textHash(text), textHash(preserveDesktopWorkspaceBom(document).text))
        val plain = document.copy(revision = document.revision.copy(documentUri = "file:a.txt#sha256=${textHash("hello")}"))
        assertEquals("hello", preserveDesktopWorkspaceBom(plain).text)
    }
    private fun bundle() = DesktopChatBundle(
        DesktopChatSource("https://10.0.0.1:4000", "ab".repeat(32), "project", "Name", "session", "claude", "original-model", "external"),
        DesktopChat(DesktopSession("session", "Title", "claude", "original-model"), listOf(
            DesktopMessage("u", "session", "user", "Question"),
            DesktopMessage("a", "session", "assistant", "Answer"),
            DesktopMessage("tool", "session", "tool", "must not become assistant"),
            DesktopMessage("status", "session", "status", "must not become assistant"),
        ), false),
    )
    @Test fun repeatedChatImportDeduplicatesAndPreservesOriginalSourceAndLocalContinuation() {
        val input = bundle()
        val first = importedDesktopChat(input)
        val again = importedDesktopChat(input)
        assertEquals(first.id, again.id)
        assertEquals(first.items.map { it.id }, again.items.map { it.id })
        assertEquals(2, first.items.size)
        assertEquals(listOf("user", "assistant"), first.turns.map { it.role })
        val continued = first.copy(items = first.items + StoredChatItem("Вы", "Local continuation", "local-message"))
        val repeated = mergeDesktopImport(continued, again)
        assertEquals(3, repeated.items.size)
        assertEquals("Local continuation", repeated.items.last().text)
        val newReply = importedDesktopChat(input.copy(chat = input.chat.copy(messages = input.chat.messages + DesktopMessage("a2", "session", "assistant", "New reply"))))
        val updated = mergeDesktopImport(repeated, newReply)
        assertEquals(4, updated.items.size)
        assertEquals("original-model", updated.desktopSource?.model)
        assertEquals("external", updated.desktopSource?.externalId)
        assertEquals(4, mergeDesktopImport(updated, newReply).items.size)
        assertNotEquals(first.id, importedDesktopChat(input.copy(source = input.source.copy(projectId = "another"))).id)
    }
    @Test fun historyRetainsProvenanceWhenExistingIdeSavesALocalContinuation() {
        val directory = Files.createTempDirectory("desktop-chat-test").toFile()
        try {
            val store = ChatHistoryStore(directory)
            val first = importedDesktopChat(bundle())
            store.save(listOf(first))
            store.save(listOf(first.copy(desktopSource = null, items = first.items + StoredChatItem("Вы", "local", "local"))))
            val loaded = store.load().single()
            assertEquals(first.desktopSource, loaded.desktopSource)
            assertEquals(3, loaded.items.size)
            val encoded = java.io.File(directory, "chat-history.json").readText()
            assertFalse(encoded.contains("secretAlias")); assertFalse(encoded.contains("Bearer"))
        } finally { directory.deleteRecursively() }
    }
}
