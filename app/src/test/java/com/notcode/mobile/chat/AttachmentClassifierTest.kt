package com.notcode.mobile.chat

import org.junit.Assert.*
import org.junit.Test

class AttachmentClassifierTest {
    @Test fun imagesUseMimeType() {
        assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("image/png", "photo.png").kind)
        assertEquals(AttachmentKind.IMAGE, AttachmentClassifier.classify("IMAGE/JPEG", "IMG_0001").kind)
    }

    @Test fun svgIsTextNotImage() {
        assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("image/svg+xml", "icon.svg").kind)
    }

    @Test fun textDetectedByMimeType() {
        assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("text/plain", "notes.txt").kind)
        assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("application/json", "package.json").kind)
    }

    @Test fun textDetectedByExtensionWhenMimeTypeUnknown() {
        assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify("application/octet-stream", "Main.kt").kind)
        assertEquals(AttachmentKind.TEXT, AttachmentClassifier.classify(null, "Dockerfile").kind)
    }

    @Test fun archivesFallBackToBinary() {
        assertEquals(AttachmentKind.BINARY, AttachmentClassifier.classify("application/zip", "app.zip").kind)
        assertEquals(AttachmentKind.BINARY, AttachmentClassifier.classify(null, "app-release.apk").kind)
    }

    @Test fun secretsAreRejectedWhateverTheMimeType() {
        assertEquals(AttachmentKind.SECRET, AttachmentClassifier.classify("text/plain", ".env").kind)
        assertEquals(AttachmentKind.SECRET, AttachmentClassifier.classify("application/octet-stream", "server.pem").kind)
        assertEquals(AttachmentKind.SECRET, AttachmentClassifier.classify("application/json", "credentials.json").kind)
        assertEquals(AttachmentKind.SECRET, AttachmentClassifier.classify("text/plain", "id_ed25519").kind)
    }

    @Test fun missingNameFallsBackToGenericLabel() {
        val decision = AttachmentClassifier.classify(null, null)
        assertEquals("файл", decision.displayName)
        assertEquals(AttachmentKind.BINARY, decision.kind)
    }

    @Test fun looksLikeTextRejectsNulAndInvalidUtf8() {
        assertTrue(AttachmentClassifier.looksLikeText("привет".toByteArray()))
        assertTrue(AttachmentClassifier.looksLikeText(ByteArray(0)))
        assertFalse(AttachmentClassifier.looksLikeText(byteArrayOf(1, 0, 2)))
        assertFalse(AttachmentClassifier.looksLikeText(byteArrayOf(-1, -2, -3, 0x27)))
    }

    @Test fun safeStoredNameStripsPathSeparators() {
        assertEquals("etc_app-release.apk", AttachmentClassifier.safeStoredName("../../etc/app-release.apk"))
        assertEquals("мой_файл.txt", AttachmentClassifier.safeStoredName("мой файл.txt"))
        assertEquals("attachment", AttachmentClassifier.safeStoredName("///"))
    }

    @Test fun bundleSummaryListsEverything() {
        val bundle = AttachmentBundle(
            imageDataUrls = listOf("data:image/png;base64,AA==", "data:image/png;base64,AA=="),
            inlineFiles = listOf(InlineAttachment("a.txt", "text", truncated = false)),
            stagedFiles = listOf(StagedAttachment("app.apk", ".notcode-attachments/app.apk")),
            skipped = listOf("секретный файл .env"),
        )
        assertEquals("\n[Прикреплено изображений: 2, файлов: 2, пропущено: 1]", bundle.summary())
        assertTrue(bundle.hasContent)
    }

    @Test fun emptyBundleHasNoSummary() {
        val bundle = AttachmentBundle(emptyList(), emptyList(), emptyList(), emptyList())
        assertFalse(bundle.hasContent)
        assertEquals("", bundle.summary())
    }
}
