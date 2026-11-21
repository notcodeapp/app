package com.notcode.mobile.desktop

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class DesktopControllerTest {
    private val pending = DesktopPendingSend("source-session", "hello", "src/main.kt", UUID.randomUUID().toString())

    @Test fun escapedPendingRequestSurvivesRestartWithSameIdentity() {
        val original = pending.copy(content = "x" + "\\".repeat(60_000))
        val encoded = encodePendingSend(original)
        assertTrue(encoded.length > 100_000)
        assertEquals(original, decodePendingSend(encoded))
    }

    @Test fun worstCaseJsonEscapingStaysInsideRestoreLimit() {
        val original = pending.copy(content = "x" + "\u0001".repeat(64 * 1024 - 1))
        val encoded = encodePendingSend(original)
        assertTrue(desktopUtf8Bytes(encoded).size <= MAX_PENDING_BYTES)
        assertEquals(original, decodePendingSend(encoded))
    }

    @Test fun corruptPendingCannotSilentlyBecomeANewRequest() {
        listOf("{}", "broken", "x".repeat(MAX_PENDING_BYTES + 1)).forEach {
            assertTrue(runCatching { decodePendingSend(it) }.isFailure)
        }
        listOf(pending.copy(requestId = "bad"), pending.copy(sessionId = "../wrong"),
            pending.copy(content = " "), pending.copy(content = "x".repeat(64 * 1024 + 1)),
            pending.copy(content = "\uD800"), pending.copy(contextPath = ".keys/token")).forEach {
            assertTrue(runCatching { encodePendingSend(it) }.isFailure)
        }
    }

    @Test fun pairingUnauthorizedNeverRevokesAnExistingCredential() {
        assertFalse(revokesDesktopCredential(DesktopHttpException(401), authenticated = false))
        assertTrue(revokesDesktopCredential(DesktopHttpException(401), authenticated = true))
        assertFalse(revokesDesktopCredential(DesktopHttpException(409), authenticated = true))
        assertFalse(revokesDesktopCredential(java.io.IOException("offline"), authenticated = true))
    }

    @Test fun liveActionsPairRunningWithFinalAndKeepRepeatedNamesDistinct() {
        var list = emptyList<DesktopLiveAction>()
        list = mergeLiveAction(list, "write_file", "running", "a.kt")
        list = mergeLiveAction(list, "write_file", "ok", "a.kt записан")
        assertEquals(1, list.size)
        assertEquals("ok", list[0].status)
        list = mergeLiveAction(list, "write_file", "running", "b.kt")
        list = mergeLiveAction(list, "write_file", "running", "c.kt")
        assertEquals(3, list.size)
        list = mergeLiveAction(list, "write_file", "error", "c.kt ошибка")
        assertEquals(3, list.size)
        assertEquals("error", list.last().status)
        assertEquals("running", list[1].status)
    }

    @Test fun liveActionListIsBounded() {
        var list = emptyList<DesktopLiveAction>()
        repeat(600) { list = mergeLiveAction(list, "tool-$it", "ok", "") }
        assertEquals(500, list.size)
        assertEquals("tool-599", list.last().name)
    }
}
