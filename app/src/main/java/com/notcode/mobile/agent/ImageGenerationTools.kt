package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.llm.GeneratedImage
import com.notcode.mobile.llm.LlmTool
import com.notcode.mobile.model.ModelUsage
import com.notcode.mobile.model.ProviderProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lets the main agent delegate picture creation to a separately configured Images API model.
 * The tool is only advertised while such a profile exists.
 */
class ImageGenerationTools(
    private val profiles: () -> List<ProviderProfile>,
    private val generate: suspend (profileId: String, prompt: String, modelId: String?, size: String?) -> GeneratedImage,
    private val save: suspend (ProjectPath, ByteArray) -> Unit,
    private val approve: suspend (title: String, detail: String) -> Boolean,
) {
    val activeProfile: ProviderProfile? get() = profiles().firstOrNull { it.usage == ModelUsage.IMAGE }

    suspend fun generateImage(arguments: JsonObject): ToolExecutionResult {
        val profile = activeProfile
            ?: return failure("NO_IMAGE_MODEL", "Модель генерации изображений не настроена")
        val prompt = (arguments["prompt"] as? JsonPrimitive)?.contentOrNull
            ?.trim().orEmpty()
        if (prompt.isEmpty()) return failure("INVALID_ARGUMENT", "Нужен текст описания изображения")
        if (prompt.length > MAX_PROMPT_CHARS) {
            return failure("INVALID_ARGUMENT", "Описание длиннее ${MAX_PROMPT_CHARS} символов")
        }
        val requestedPath = (arguments["filePath"] as? JsonPrimitive)?.contentOrNull
        val size = (arguments["size"] as? JsonPrimitive)?.contentOrNull
        val modelId = (arguments["model"] as? JsonPrimitive)?.contentOrNull

        val image = try {
            generate(profile.id, prompt, modelId, size)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            return failure("GENERATION_FAILED", "Генерация не удалась: ${error.message?.take(200) ?: "ошибка провайдера"}")
        }
        if (image.bytes.isEmpty()) return failure("EMPTY_IMAGE", "Провайдер вернул пустое изображение")

        val path = targetPath(requestedPath, image.extension)
            ?: return failure("INVALID_PATH", "Нужен относительный путь внутри проекта без .. и секретов")
        if (!approve(
                "Сохранить изображение · ${path.value}",
                "Сгенерировано моделью ${profile.modelId} (${image.bytes.size / 1024} КБ, ${image.mimeType}). " +
                    (image.revisedPrompt?.let { "Провайдер уточнил запрос: ${it.take(200)}\n" } ?: "") +
                    "Сохранить в проект?",
            )
        ) {
            return failure("DECLINED", "Пользователь отклонил сохранение изображения")
        }
        return try {
            save(path, image.bytes)
            val result = buildJsonObject {
                put("ok", true)
                put("filePath", path.value)
                put("mimeType", image.mimeType)
                put("bytes", image.bytes.size)
                put("prompt", prompt.take(MAX_PROMPT_CHARS))
                image.revisedPrompt?.let { put("revisedPrompt", it) }
            }
            ToolExecutionResult(result.toString(), "Изображение сохранено: ${path.value}", path.value)
        } catch (error: Exception) {
            failure("SAVE_FAILED", "Изображение сгенерировано, но сохранение не удалось: ${error.message?.take(200)}")
        }
    }

    private fun targetPath(raw: String?, extension: String): ProjectPath? {
        val parsed = try {
            ProjectPath.parse(raw?.takeIf { it.isNotBlank() } ?: defaultName())
        } catch (_: Exception) {
            return null
        }
        if (parsed.isRoot) return null
        val segments = parsed.value.split('/')
        if (segments.any { segment ->
                val lower = segment.lowercase()
                lower in BLOCKED_SEGMENTS ||
                    BLOCKED_SEGMENTS.any { blocked -> lower.startsWith("$blocked.") || lower.startsWith("$blocked-") } ||
                    BLOCKED_SUFFIXES.any { lower.endsWith(it) }
            }
        ) {
            return null
        }
        val name = segments.last()
        if (name.lowercase().endsWith(".$extension")) return parsed
        val stem = name.substringBeforeLast('.', name).ifBlank { "image" }
        val parent = parsed.parent
        return ProjectPath.parse(
            if (parent.isRoot) "$stem.$extension" else "${parent.value}/$stem.$extension",
        )
    }

    private fun defaultName(): String =
        "images/generated-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.png"

    private fun failure(code: String, message: String) = ToolExecutionResult(
        buildJsonObject {
            put("ok", false)
            put("error", code)
            put("message", message)
        }.toString(),
        message,
    )

    companion object {
        const val NAME = "generate_image"
        const val MAX_PROMPT_CHARS = 4_000
        private val BLOCKED_SEGMENTS = setOf(
            ".env", ".ssh", ".aws", ".gnupg", "credentials", "credentials.json", "secrets.json",
            "id_rsa", "id_ed25519", "keystore", "local.properties",
        )
        private val BLOCKED_SUFFIXES = setOf(".pem", ".p12", ".key", ".keystore", ".jks")

        val definitions: List<LlmTool> = listOf(LlmTool(
            NAME,
            "Generate a picture with the separately configured image model (Images API) and save it into the project. " +
                "Use only when the user asks for an image; the save always needs user confirmation.",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("prompt", buildJsonObject {
                        put("type", "string")
                        put("description", "Detailed description of the picture")
                    })
                    put("filePath", buildJsonObject {
                        put("type", "string")
                        put("description", "Relative project path, for example images/hero.png")
                    })
                    put("size", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional size such as 1024x1024")
                    })
                    put("model", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional image model ID override")
                    })
                })
                put("required", JsonArray(
                    listOf(JsonPrimitive("prompt")),
                ))
                put("additionalProperties", false)
            },
        ))
    }
}
