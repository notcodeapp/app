package com.notcode.mobile.llm

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

data class GeneratedImage(
    val bytes: ByteArray,
    val mimeType: String,
    val revisedPrompt: String?,
) {
    val extension: String get() = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    override fun equals(other: Any?): Boolean =
        other is GeneratedImage && mimeType == other.mimeType &&
            revisedPrompt == other.revisedPrompt && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * mimeType.hashCode() + revisedPrompt.hashCode()) + bytes.contentHashCode()
}

/** OpenAI-compatible `POST /images/generations` request and response mapping. */
object ImageGenerationProtocol {
    const val MAX_RESPONSE_BYTES = 48L * 1024 * 1024

    private val ALLOWED_SIZES = setOf(
        "auto", "256x256", "512x512", "768x768", "1024x1024",
        "1024x1536", "1536x1024", "512x1024", "1024x512",
    )

    fun normalizedSize(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it in ALLOWED_SIZES }

    fun requestPayload(model: String, prompt: String, size: String?): JsonObject = buildJsonObject {
        put("model", model)
        put("prompt", prompt)
        put("n", 1)
        normalizedSize(size)?.let { put("size", it) }
        put("response_format", "b64_json")
    }

    /** Reads the first image from an Images API body; base64 is preferred over a remote URL. */
    fun parse(root: JsonObject): ParsedImage {
        val entries = (root["data"] as? JsonArray)
            ?: throw ProviderProtocolException("Images response has no data array")
        val first = entries.firstOrNull() as? JsonObject
            ?: throw ProviderProtocolException("Images response returned no image")
        val b64 = (first["b64_json"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val url = (first["url"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val revised = (first["revised_prompt"] as? JsonPrimitive)?.contentOrNull
        return when {
            b64 != null -> ParsedImage.Base64(b64, guessMime(first["mime_type"]), revised)
            url != null -> ParsedImage.RemoteUrl(url, revised)
            else -> throw ProviderProtocolException("Images response has neither b64_json nor url")
        }
    }

    fun decodeBase64(value: String): ByteArray =
        try {
            // The MIME decoder silently drops characters outside the alphabet, so reject them first.
            value.forEach { character ->
                if (!isBase64Character(character)) throw IllegalArgumentException("unexpected character '$character'")
            }
            java.util.Base64.getMimeDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw ProviderProtocolException("Images response base64 is invalid", error)
        }

    private fun isBase64Character(character: Char) =
        character.isWhitespace() || character in 'A'..'Z' || character in 'a'..'z' ||
            character in '0'..'9' || character == '+' || character == '/' || character == '='

    fun guessMime(element: JsonElement?, url: String? = null): String {
        val declared = (element as? JsonPrimitive)?.contentOrNull?.lowercase()?.takeIf {
            it.startsWith("image/")
        }
        if (declared != null) return declared
        val extension = url?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "webp" -> "image/webp"
            else -> "image/png"
        }
    }
}

sealed class ParsedImage {
    data class Base64(val data: String, val mimeType: String, val revisedPrompt: String?) : ParsedImage()
    data class RemoteUrl(val url: String, val revisedPrompt: String?) : ParsedImage()
}
