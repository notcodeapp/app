package com.notcode.mobile.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun response(vararg items: String) = buildJsonObject {
        put("data", JsonArray(items.map { json.parseToJsonElement(it) }))
    }

    @Test fun payloadAlwaysRequestsBase64() {
        val payload = ImageGenerationProtocol.requestPayload("dall-e-3", "рыжий кот", null)
        assertEquals("dall-e-3", payload.getValue("model").jsonPrimitive.content)
        assertEquals("рыжий кот", payload.getValue("prompt").jsonPrimitive.content)
        assertEquals("b64_json", payload.getValue("response_format").jsonPrimitive.content)
        assertFalse(payload.containsKey("size"))
    }

    @Test fun onlyKnownSizesAreSent() {
        assertNotNull(ImageGenerationProtocol.normalizedSize(" 1024x1536 "))
        assertNull(ImageGenerationProtocol.normalizedSize("1024x1537"))
        assertFalse(ImageGenerationProtocol.requestPayload("m", "p", "9999x9999").containsKey("size"))
        assertEquals(
            "1024x1024",
            ImageGenerationProtocol.requestPayload("m", "p", "1024X1024").getValue("size").jsonPrimitive.content,
        )
    }

    @Test fun base64ImageIsDecodedWithDeclaredType() {
        val parsed = ImageGenerationProtocol.parse(
            response("""{"b64_json":"aGVsbG8=","mime_type":"image/webp"}""")
        ) as ParsedImage.Base64
        assertArrayEquals("hello".toByteArray(), ImageGenerationProtocol.decodeBase64(parsed.data))
        assertEquals("image/webp", parsed.mimeType)
    }

    @Test fun missingMimeTypeDefaultsToPng() {
        val parsed = ImageGenerationProtocol.parse(response("""{"b64_json":"AAA="}""")) as ParsedImage.Base64
        val image = GeneratedImage(ImageGenerationProtocol.decodeBase64(parsed.data), parsed.mimeType, null)
        assertEquals("png", image.extension)
    }

    @Test fun remoteUrlIsKeptForHttpsDownload() {
        val parsed = ImageGenerationProtocol.parse(
            response("""{"url":"https://cdn.example.com/out.jpg?token=1","revised_prompt":"уточнено"}""")
        ) as ParsedImage.RemoteUrl
        assertEquals("https://cdn.example.com/out.jpg?token=1", parsed.url)
        assertEquals("уточнено", parsed.revisedPrompt)
        assertEquals("image/jpeg", ImageGenerationProtocol.guessMime(null, parsed.url))
    }

    @Test fun unexpectedShapesFailInsteadOfSilentlyPassing() {
        listOf(
            buildJsonObject { },
            buildJsonObject { put("data", JsonArray(emptyList())) },
            response("""{"caption":"нет картинки"}"""),
        ).forEach { root ->
            assertThrows(ProviderProtocolException::class.java) { ImageGenerationProtocol.parse(root) }
        }
    }

    @Test fun invalidBase64IsRejected() {
        assertThrows(ProviderProtocolException::class.java) {
            ImageGenerationProtocol.decodeBase64("не-base64!!!")
        }
    }
}
