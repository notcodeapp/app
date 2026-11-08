package com.notcode.mobile.agent

import com.notcode.mobile.llm.GeneratedImage
import com.notcode.mobile.model.ModelUsage
import com.notcode.mobile.model.ProviderProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ImageGenerationToolsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val saved = mutableListOf<Pair<String, ByteArray>>()
    private var approved = true
    private var generator: suspend (String, String, String?, String?) -> GeneratedImage =
        { _, _, _, _ -> GeneratedImage(byteArrayOf(1, 2, 3, 4), "image/png", null) }

    private fun tool(profiles: List<ProviderProfile>) = ImageGenerationTools(
        profiles = { profiles },
        generate = { profileId, prompt, modelId, size -> generator(profileId, prompt, modelId, size) },
        save = { path, bytes -> saved += path.value to bytes },
        approve = { _, _ -> approved },
    )

    private fun imageProfile() = ProviderProfile(
        baseUrl = "https://images.example/v1",
        modelId = "image-model",
        usage = ModelUsage.IMAGE,
    )

    private fun args(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
        kotlinx.serialization.json.buildJsonObject(builder)

    private fun errorCode(result: ToolExecutionResult): String =
        json.parseToJsonElement(result.json).jsonObject.getValue("error").jsonPrimitive.content

    @Test fun refusesWithoutConfiguredImageModel() = runBlocking {
        val result = tool(listOf(ProviderProfile(baseUrl = "https://chat.example/v1", modelId = "chat")))
            .generateImage(args { put("prompt", "кот в шляпе") })
        assertEquals("NO_IMAGE_MODEL", errorCode(result))
        assertTrue(saved.isEmpty())
    }

    @Test fun nothingIsSavedWhenUserDeclines() = runBlocking {
        approved = false
        val result = tool(listOf(imageProfile())).generateImage(args { put("prompt", "закат") })
        assertEquals("DECLINED", errorCode(result))
        assertTrue(saved.isEmpty())
    }

    @Test fun defaultPathUsesImagesFolderAndPngExtension() = runBlocking {
        val result = tool(listOf(imageProfile())).generateImage(args { put("prompt", "закат") })
        assertTrue(result.filePath!!.startsWith("images/generated-"))
        assertTrue(result.filePath!!.endsWith(".png"))
        assertEquals(1, saved.size)
    }

    @Test fun requestedPathWithoutExtensionGetsTheRealOne() = runBlocking {
        val result = tool(listOf(imageProfile())).generateImage(
            args {
                put("prompt", "иконка")
                put("filePath", "assets/logo")
            },
        )
        assertEquals("assets/logo.png", result.filePath)
    }

    @Test fun jpegBytesRenameTheRequestedExtension() = runBlocking {
        generator = { _, _, _, _ -> GeneratedImage(byteArrayOf(9), "image/jpeg", null) }
        val result = tool(listOf(imageProfile())).generateImage(
            args {
                put("prompt", "фото")
                put("filePath", "photos/shot.png")
            },
        )
        assertEquals("photos/shot.jpg", result.filePath)
    }

    @Test fun pathsOutsideTheProjectAreRejected() = runBlocking {
        val result = tool(listOf(imageProfile())).generateImage(
            args {
                put("prompt", "фото")
                put("filePath", "../outside.png")
            },
        )
        assertEquals("INVALID_PATH", errorCode(result))
        assertTrue(saved.isEmpty())
    }

    @Test fun secretPathsCannotBeOverwritten() = runBlocking {
        listOf("project/.env", "secrets/id_rsa.png", "keys/server.pem", "app/.env.backup.png").forEach { candidate ->
            val result = tool(listOf(imageProfile())).generateImage(
                args {
                    put("prompt", "фото")
                    put("filePath", candidate)
                },
            )
            assertEquals("INVALID_PATH for $candidate", "INVALID_PATH", errorCode(result))
        }
        assertTrue(saved.isEmpty())
    }

    @Test fun emptyPromptIsRejectedBeforeAnyRequest() = runBlocking {
        var called = false
        generator = { _, _, _, _ -> called = true; GeneratedImage(byteArrayOf(1), "image/png", null) }
        val result = tool(listOf(imageProfile())).generateImage(args { put("prompt", "   ") })
        assertEquals("INVALID_ARGUMENT", errorCode(result))
        assertFalse(called)
    }

    @Test fun providerFailureBecomesToolError() = runBlocking {
        generator = { _, _, _, _ -> throw IllegalStateException("429 slow down") }
        val result = tool(listOf(imageProfile())).generateImage(args { put("prompt", "фото") })
        assertEquals("GENERATION_FAILED", errorCode(result))
        assertTrue(result.summary.contains("429 slow down"))
    }

    @Test fun emptyImageIsRejected() = runBlocking {
        generator = { _, _, _, _ -> GeneratedImage(ByteArray(0), "image/png", null) }
        val result = tool(listOf(imageProfile())).generateImage(args { put("prompt", "фото") })
        assertEquals("EMPTY_IMAGE", errorCode(result))
    }

    @Test fun saveFailureIsReportedWithoutClaimingSuccess() = runBlocking {
        val failing = ImageGenerationTools(
            profiles = { listOf(imageProfile()) },
            generate = { _, _, _, _ -> GeneratedImage(byteArrayOf(1), "image/png", null) },
            save = { _, _ -> throw IllegalStateException("нет места") },
            approve = { _, _ -> true },
        )
        val result = failing.generateImage(args { put("prompt", "фото") })
        assertEquals("SAVE_FAILED", errorCode(result))
        assertFalse(result.summary.contains("сохранено"))
    }

    @Test fun modelAndSizeOverridesReachTheProvider() = runBlocking {
        var seen: Pair<String?, String?>? = null
        generator = { _, _, modelId, size -> seen = modelId to size; GeneratedImage(byteArrayOf(1), "image/png", null) }
        tool(listOf(imageProfile())).generateImage(
            args {
                put("prompt", "фото")
                put("model", "flux-fast")
                put("size", "1024x1536")
            },
        )
        assertEquals("flux-fast" to "1024x1536", seen)
    }

    @Test fun definitionRequiresOnlyPrompt() {
        val tool = ImageGenerationTools.definitions.single()
        assertEquals("generate_image", tool.name)
        val schema = tool.parameters
        assertEquals(listOf("prompt"), (schema["required"] as? kotlinx.serialization.json.JsonArray)?.map {
            it.jsonPrimitive.content
        })
        assertTrue(saved.isEmpty())
    }
}
