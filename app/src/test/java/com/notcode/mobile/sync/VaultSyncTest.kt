package com.notcode.mobile.sync

import com.notcode.mobile.model.AuthMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VaultSyncTest {
    @Test
    fun `vault item превращается в профиль`() {
        val item = buildJsonObject {
            put("kind", "provider")
            put("id", "acc-123")
            put("updatedAt", 1000)
            put("data", buildJsonObject {
                put("name", "OpenRouter")
                put("baseUrl", "https://openrouter.ai/api/v1")
                put("models", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("m1"), kotlinx.serialization.json.JsonPrimitive("m2"))))
                put("key", "sk-secret")
            })
        }
        val provider = parseVaultItems(kotlinx.serialization.json.JsonArray(listOf(item))).single()
        assertEquals("acc-123", provider.id)
        assertEquals("sk-secret", provider.key)

        val profile = vaultProviderToProfile(provider)!!
        assertEquals("vault-acc-123", profile.id)
        assertEquals("OpenRouter", profile.displayName)
        assertEquals("https://openrouter.ai/api/v1", profile.baseUrl)
        assertEquals("m1", profile.modelId)
        assertEquals(AuthMode.BEARER, profile.authMode)
    }

    @Test
    fun `локальный ollama помечается как insecure`() {
        val item = buildJsonObject {
            put("kind", "provider")
            put("id", "ollama")
            put("updatedAt", 1)
            put("data", buildJsonObject {
                put("name", "Ollama")
                put("baseUrl", "http://127.0.0.1:11434/v1")
                put("models", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("llama3"))))
            })
        }
        val provider = parseVaultItems(kotlinx.serialization.json.JsonArray(listOf(item))).single()
        val profile = vaultProviderToProfile(provider)!!
        assertEquals(true, profile.allowInsecureHttp)
        assertEquals(AuthMode.NONE, profile.authMode)
    }

    @Test
    fun `битые элементы отбрасываются`() {
        val json = Json.parseToJsonElement("""
            [
              {"kind": "provider", "id": "", "data": {"baseUrl": "https://x", "models": ["a"]}},
              {"kind": "provider", "id": "ok", "data": {"baseUrl": "ftp://bad", "models": ["a"]}},
              {"kind": "provider", "id": "ok2", "data": {"baseUrl": "https://x", "models": []}},
              {"kind": "chat", "id": "chat-1", "data": {}}
            ]
        """.trimIndent())
        // Парсер отбирает только kind=provider с id; валидность проверяет маппинг.
        val parsed = parseVaultItems(json.jsonArray)
        assertEquals(3, parsed.size)
        // Маппинг отбрасывает: пустой id/url, ftp, пустые модели.
        assertEquals(0, parsed.count { vaultProviderToProfile(it) != null })
        assertNull(vaultProviderToProfile(
            VaultProvider(id = "x", name = "", baseUrl = "https://x", models = emptyList(), key = null, updatedAt = 0)
        ))
    }
}
