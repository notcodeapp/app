package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.ProjectEntry
import com.notcode.mobile.filesystem.ProjectBinaryFileException
import com.notcode.mobile.filesystem.ProjectFileSystem
import com.notcode.mobile.filesystem.ProjectFileTooLargeException
import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.llm.CompleteToolCall
import com.notcode.mobile.llm.LlmTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.ArrayDeque
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlin.coroutines.coroutineContext

data class WriteProposal(
    val path: ProjectPath,
    val previousContent: String?,
    val proposedContent: String,
    val operation: String = "write",
    val targetPath: ProjectPath? = null,
)

fun interface WriteApprovalGateway {
    suspend fun approve(proposal: WriteProposal): Boolean
}

data class ToolExecutionResult(
    val json: String,
    val summary: String,
    val filePath: String? = null,
)

class ToolExecutor(
    private val fileSystem: ProjectFileSystem,
    private val json: Json,
    private val writeApproval: WriteApprovalGateway,
    private val externalTools: Map<String, suspend (JsonObject) -> ToolExecutionResult> = emptyMap(),
    extraDefinitions: List<LlmTool> = emptyList(),
    private val readOnly: Boolean = false,
) {
    val availableDefinitions: List<LlmTool> =
        if (readOnly) definitions.filterNot { it.name in MUTATING_TOOLS }
        else definitions + extraDefinitions

    suspend fun execute(call: CompleteToolCall): ToolExecutionResult {
        require(call.name.length <= MAX_TOOL_NAME_CHARS) { "Tool name is too long" }
        require(call.arguments.toByteArray(Charsets.UTF_8).size <= MAX_TOOL_ARGUMENT_BYTES) {
            "Tool arguments exceed the 512 KiB limit"
        }
        return when (call.name) {
            LIST_FILES -> listFiles(parseArguments(call))
            READ_FILE -> readFile(parseArguments(call))
            WRITE_FILE, CREATE_FILE, RENAME_FILE, DELETE_FILE -> {
                if (readOnly) errorResult("READ_ONLY", "Writes are disabled for analysis")
                else when (call.name) {
                    WRITE_FILE -> writeFile(parseArguments(call))
                    CREATE_FILE -> writeFile(parseArguments(call), createOnly = true)
                    else -> renameOrDeleteFile(parseArguments(call), rename = call.name == RENAME_FILE)
                }
            }
            SEARCH_IN_FILES -> searchInFiles(parseArguments(call))
            else -> {
                val callback = if (readOnly) null else externalTools[call.name]
                if (callback == null) errorResult("UNKNOWN_TOOL", "Unsupported tool: ${call.name}")
                else callback(parseArguments(call))
            }
        }
    }

    private suspend fun listFiles(arguments: JsonObject): ToolExecutionResult {
        val path = agentPath(arguments.requireString("dirPath"))
        val allEntries = fileSystem.list(path).filterNot { isSensitive(it.path) }
        val truncated = allEntries.size > MAX_LIST_ENTRIES
        val entries = allEntries.take(MAX_LIST_ENTRIES)
        val result = buildJsonObject {
            put("ok", true)
            put("directory", path.value)
            put("truncated", truncated)
            put("entries", buildJsonArray {
                entries.forEach { entry ->
                    add(buildJsonObject {
                        put("path", entry.path.value)
                        put("name", entry.name)
                        put("type", if (entry.isDirectory) "directory" else "file")
                        if (!entry.isDirectory) {
                            put("size", entry.size)
                            put("editable", entry.editable)
                        }
                    })
                }
            })
        }
        return success(result, "${entries.size} items in ${path.value.ifEmpty { "/" }}")
    }

    private suspend fun readFile(arguments: JsonObject): ToolExecutionResult {
        val path = agentPath(arguments.requireString("filePath"))
        val document = try {
            fileSystem.readText(path, MAX_AGENT_FILE_BYTES)
        } catch (_: ProjectBinaryFileException) {
            return previewFile(path)
        } catch (_: ProjectFileTooLargeException) {
            return previewFile(path)
        }
        val result = buildJsonObject {
            put("ok", true)
            put("path", path.value)
            put("chars", document.text.length)
            put("content", document.text)
            put("truncated", false)
        }
        return success(result, "Read ${path.value} (${lineCount(document.text)} lines)", path.value)
    }

    private suspend fun previewFile(path: ProjectPath): ToolExecutionResult {
        val preview = fileSystem.readPreview(path, MAX_AGENT_PREVIEW_BYTES)
        try {
            val text = decodePreview(preview.bytes, preview.truncated)
            val format = if (text == null) "hex" else "utf-8"
            val content = text ?: formatHexPreview(preview.bytes)
            val result = buildJsonObject {
                put("ok", true)
                put("path", path.value)
                put("previewOnly", true)
                put("format", format)
                put("size", preview.revision.size)
                put("bytesShown", preview.bytes.size)
                put("truncated", preview.truncated)
                put("content", content)
            }
            return success(
                result,
                "Preview ${path.value} (${preview.bytes.size} of ${preview.revision.size} bytes, $format${if (preview.truncated) ", truncated" else ""})",
                path.value,
            )
        } finally {
            preview.bytes.fill(0)
        }
    }

    private fun decodePreview(bytes: ByteArray, truncated: Boolean): String? {
        if (bytes.any { it == 0.toByte() }) return null
        val length = if (truncated) (bytes.size - 4).coerceAtLeast(0) else bytes.size
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun formatHexPreview(bytes: ByteArray): String = bytes.asList().chunked(16).mapIndexed { row, chunk ->
        val offset = String.format(java.util.Locale.ROOT, "%08X", row * 16)
        val hex = chunk.joinToString(" ") { String.format(java.util.Locale.ROOT, "%02X", it.toInt() and 0xFF) }
            .padEnd(47, ' ')
        val ascii = chunk.joinToString("") { byte ->
            val value = byte.toInt() and 0xFF
            if (value in 32..126) value.toChar().toString() else "."
        }
        "$offset  $hex  |$ascii|"
    }.joinToString("\n")

    private suspend fun writeFile(arguments: JsonObject, createOnly: Boolean = false): ToolExecutionResult {
        val path = agentPath(arguments.requireString("filePath"))
        require(!path.isRoot) { "A file path is required" }
        if (!createOnly && fileSystem is AgentProjectFileSystem &&
            fileSystem.exists(path) && !fileSystem.isWorkspaceFile(path)
        ) {
            return errorResult("SOURCE_ONLY", "This source-tree file is visible but is not in the editable workspace", path.value)
        }
        val content = arguments.requireString("content")
        if (createOnly && fileSystem.exists(path)) {
            return errorResult("FILE_EXISTS", "Cannot create an existing file", path.value)
        }
        if (content.toByteArray(Charsets.UTF_8).size > MAX_AGENT_WRITE_BYTES) {
            return errorResult(
                "CONTENT_TOO_LARGE",
                "Proposed content exceeds the ${MAX_AGENT_WRITE_BYTES / 1024} KB limit",
            )
        }

        val current = if (!createOnly && fileSystem.exists(path)) {
            runCatching { fileSystem.readText(path, MAX_AGENT_WRITE_BYTES) }
                .getOrElse {
                    if (it is CancellationException) throw it
                    return errorResult("READ_BEFORE_WRITE_FAILED", it.safeMessage())
                }
        } else {
            null
        }

        val approved = writeApproval.approve(
            WriteProposal(
                path = path,
                previousContent = current?.text,
                proposedContent = content,
                operation = if (createOnly) "create" else "write",
            ),
        )
        if (!approved) {
            return errorResult("USER_DENIED", "The user rejected this write", path.value)
        }

        // Null revisions do not express create-if-absent in the filesystem API.
        // Recheck after approval, immediately before the write.
        if (current == null && fileSystem.exists(path)) {
            return errorResult("FILE_EXISTS", "File appeared while awaiting approval", path.value)
        }
        val revision = fileSystem.writeText(
            path = path,
            content = content,
            expectedRevision = current?.revision,
        )
        val result = buildJsonObject {
            put("ok", true)
            put("path", path.value)
            put("chars", content.length)
            put("size", revision.size)
        }
        val verb = if (createOnly) "Created" else "Wrote"
        return success(result, "$verb ${path.value} (${lineCount(content)} lines)", path.value)
    }

    private suspend fun renameOrDeleteFile(arguments: JsonObject, rename: Boolean): ToolExecutionResult {
        val path = agentPath(arguments.requireString("filePath"))
        val target = if (rename) agentPath(arguments.requireString("targetPath")) else null
        require(!path.isRoot && target?.isRoot != true) { "A file path is required" }
        if (fileSystem is AgentProjectFileSystem &&
            fileSystem.exists(path) && !fileSystem.isWorkspaceFile(path)
        ) {
            return errorResult("SOURCE_ONLY", "This source-tree file is visible but is not in the editable workspace", path.value)
        }
        if (target != null && fileSystem.exists(target)) {
            return errorResult("FILE_EXISTS", "Rename destination already exists", path.value)
        }
        val current = try {
            val document = fileSystem.readText(path, MAX_AGENT_WRITE_BYTES)
            document.revision to document.text
        } catch (_: ProjectBinaryFileException) {
            val preview = fileSystem.readPreview(path, 0)
            try { preview.revision to "[binary file, ${preview.revision.size} bytes]" } finally { preview.bytes.fill(0) }
        } catch (_: ProjectFileTooLargeException) {
            val preview = fileSystem.readPreview(path, 0)
            try { preview.revision to "[large file, ${preview.revision.size} bytes]" } finally { preview.bytes.fill(0) }
        }
        val operation = if (rename) "rename" else "delete"
        val approved = writeApproval.approve(
            WriteProposal(
                path = path,
                previousContent = current.second,
                proposedContent = if (rename) current.second else "",
                operation = operation,
                targetPath = target,
            ),
        )
        if (!approved) return errorResult("USER_DENIED", "The user rejected this $operation", path.value)
        val revision = if (target != null) {
            fileSystem.renameFile(path, target, current.first)
        } else {
            fileSystem.deleteFile(path, current.first)
            null
        }
        val result = buildJsonObject {
            put("ok", true)
            put("path", path.value)
            put("lines", lineCount(current.second))
            if (target != null) put("targetPath", target.value)
            if (revision != null) put("size", revision.size)
        }
        val summary = if (target != null) "Renamed ${path.value} to ${target.value}"
            else "Deleted ${path.value}"
        return success(result, "$summary (${lineCount(current.second)} lines)", target?.value ?: path.value)
    }

    private fun lineCount(content: String): Int = if (content.isEmpty()) 0 else content.lineSequence().count()

    private suspend fun searchInFiles(arguments: JsonObject): ToolExecutionResult {
        val query = arguments.requireString("query")
        if (query.isBlank()) return errorResult("INVALID_ARGUMENTS", "query must not be blank")
        if (query.length > 500) return errorResult("INVALID_ARGUMENTS", "query is too long")

        val queue = ArrayDeque<ProjectPath>().apply { add(ProjectPath.Root) }
        val matches = mutableListOf<JsonObject>()
        var visitedFiles = 0
        var skippedFiles = 0

        while (queue.isNotEmpty() &&
            visitedFiles < MAX_SEARCH_FILES &&
            matches.size < MAX_SEARCH_MATCHES
        ) {
            coroutineContext.ensureActive()
            val directory = queue.removeFirst()
            val entries = runCatching { fileSystem.list(directory) }.getOrElse {
                if (it is CancellationException) throw it
                skippedFiles++
                emptyList()
            }
            entries.forEach { entry ->
                if (matches.size >= MAX_SEARCH_MATCHES || visitedFiles >= MAX_SEARCH_FILES) {
                    return@forEach
                }
                if (isSensitive(entry.path)) return@forEach
                if (entry.isDirectory) {
                    if (entry.name !in IGNORED_DIRECTORIES) queue.add(entry.path)
                } else {
                    visitedFiles++
                    searchEntry(entry, query, matches).onFailure {
                        if (it is CancellationException) throw it
                        skippedFiles++
                    }
                }
            }
        }

        val truncated = queue.isNotEmpty() ||
            visitedFiles >= MAX_SEARCH_FILES ||
            matches.size >= MAX_SEARCH_MATCHES
        val result = buildJsonObject {
            put("ok", true)
            put("query", query)
            put("visitedFiles", visitedFiles)
            put("skippedFiles", skippedFiles)
            put("truncated", truncated)
            put("matches", JsonArray(matches))
        }
        return success(result, "${matches.size} matches in $visitedFiles files")
    }

    private suspend fun searchEntry(
        entry: ProjectEntry,
        query: String,
        matches: MutableList<JsonObject>,
    ): Result<Unit> = runCatching {
        if (entry.size > MAX_SEARCH_FILE_BYTES && entry.size > 0L) return@runCatching
        val document = fileSystem.readText(entry.path, MAX_SEARCH_FILE_BYTES)
        document.text.lineSequence().forEachIndexed { index, line ->
            if (matches.size >= MAX_SEARCH_MATCHES) return@forEachIndexed
            val column = line.indexOf(query, ignoreCase = true)
            if (column >= 0) {
                matches += buildJsonObject {
                    put("path", entry.path.value)
                    put("line", index + 1)
                    put("column", column + 1)
                    put("preview", line.take(MAX_SEARCH_PREVIEW_CHARS))
                }
            }
        }
    }

    private fun agentPath(raw: String): ProjectPath {
        val path = ProjectPath.parse(raw)
        require(!isSensitive(path)) { "Sensitive paths are not available to the agent" }
        return path
    }

    private fun isSensitive(path: ProjectPath): Boolean = path.value.split('/').any {
        val name = it.lowercase()
        name in setOf(".git", ".notcode-staging", ".ssh", ".aws", ".gnupg", "credentials", "credentials.json", "secrets.json", "local.properties", "id_rsa", "id_ed25519") ||
            name == ".env" || name.startsWith(".env.") || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".keystore") || name.endsWith(".jks")
    }

    private fun parseArguments(call: CompleteToolCall): JsonObject {
        val element = runCatching { json.parseToJsonElement(call.arguments) }
            .getOrElse {
                if (it is CancellationException) throw it
                throw ToolArgumentsException("Arguments are not valid JSON")
            }
        return element as? JsonObject
            ?: throw ToolArgumentsException("Arguments must be a JSON object")
    }

    private fun JsonObject.requireString(name: String): String {
        val primitive = get(name) as? JsonPrimitive
            ?: throw ToolArgumentsException("Missing string field: $name")
        if (!primitive.isString) throw ToolArgumentsException("Missing string field: $name")
        return primitive.contentOrNull
            ?: throw ToolArgumentsException("Missing string field: $name")
    }

    private fun success(value: JsonObject, summary: String, filePath: String? = null) = ToolExecutionResult(
        json = json.encodeToString(JsonElement.serializer(), value),
        summary = summary,
        filePath = filePath,
    )

    private fun errorResult(code: String, message: String, filePath: String? = null): ToolExecutionResult {
        val value = buildJsonObject {
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message.take(500))
            })
        }
        return ToolExecutionResult(
            json = json.encodeToString(JsonElement.serializer(), value),
            summary = message.take(120),
            filePath = filePath,
        )
    }

    companion object {
        const val LIST_FILES = "list_files"
        const val READ_FILE = "read_file"
        const val WRITE_FILE = "write_file"
        const val CREATE_FILE = "create_file"
        const val RENAME_FILE = "rename_file"
        const val DELETE_FILE = "delete_file"
        private val MUTATING_TOOLS = setOf(WRITE_FILE, CREATE_FILE, RENAME_FILE, DELETE_FILE)
        const val SEARCH_IN_FILES = "search_in_files"
        const val RUN_COMMAND = "run_command"
        const val STAGE_FILE_FOR_TERMINAL = "stage_file_for_terminal"
        const val GIT = "git"
        const val DELEGATE_ANALYSIS = "delegate_analysis"
        const val MAX_TOOL_NAME_CHARS = 128
        const val MAX_TOOL_ARGUMENT_BYTES = 512 * 1024
        private const val MAX_AGENT_PREVIEW_BYTES = 4096L

        // Runtime callbacks enforce approval and local-only execution; these are opt-in schemas.
        val developmentDefinitions: List<LlmTool> = listOf(
            functionTool(
                RUN_COMMAND,
                "Run a development command in the opened project, subject to user approval. Local Ubuntu mounts the private working copy at /workspace; source-folder files must first be copied there with stage_file_for_terminal.",
                mapOf("command" to stringSchema("Command to execute in the project")),
                listOf("command"),
            ),
            functionTool(
                STAGE_FILE_FOR_TERMINAL,
                "Copy one project file byte-for-byte into private app storage mounted at /workspace for local Ubuntu commands. Use this when a file is listed by project tools but is not present in the terminal workspace. Returns its exact /workspace path. Files up to 512 MiB are supported; source files are not changed.",
                mapOf("filePath" to stringSchema("Project-relative file path to stage")),
                listOf("filePath"),
            ),
            functionTool(
                GIT,
                "Perform local Git operations only; no remote operations are supported",
                mapOf(
                    "action" to buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray {
                            listOf("init", "status", "diff", "log", "commit").forEach { add(JsonPrimitive(it)) }
                        })
                    },
                    "message" to stringSchema("Commit message, required for commit"),
                ),
                listOf("action"),
            ),
            functionTool(
                DELEGATE_ANALYSIS,
                "Ask a read-only analysis worker to inspect the project. Uses the configured external LLM " +
                    "provider, may send project content and incur additional cost under the parent user's consent. " +
                    "No commands, writes or nested delegation; at most three delegations per parent run.",
                mapOf("task" to stringSchema("Specific analysis or search task")),
                listOf("task"),
            ),
        )

        const val MAX_AGENT_FILE_BYTES = 256L * 1024L
        const val MAX_AGENT_WRITE_BYTES = 512L * 1024L
        private const val MAX_LIST_ENTRIES = 500
        private const val MAX_SEARCH_FILES = 2_000
        private const val MAX_SEARCH_MATCHES = 100
        private const val MAX_SEARCH_FILE_BYTES = 256L * 1024L
        private const val MAX_SEARCH_PREVIEW_CHARS = 500
        private val IGNORED_DIRECTORIES = setOf(
            ".notcode-staging",
            ".git",
            ".gradle",
            ".idea",
            "build",
            "node_modules",
            "target",
        )

        val definitions: List<LlmTool> = listOf(
            functionTool(
                name = LIST_FILES,
                description = "List direct children of a directory in the opened project, including source-tree files outside the editable workspace. Each file reports whether it is editable.",
                properties = mapOf(
                    "dirPath" to stringSchema(
                        "Project-relative directory; an empty string means the project root",
                    ),
                ),
                required = listOf("dirPath"),
            ),
            functionTool(
                name = READ_FILE,
                description = "Read UTF-8 text or get a bounded UTF-8/HEX preview of a binary or large file in the opened project",
                properties = mapOf(
                    "filePath" to stringSchema("Project-relative file path"),
                ),
                required = listOf("filePath"),
            ),
            functionTool(
                name = WRITE_FILE,
                description = "Propose creating or replacing a UTF-8 text file. The user must approve it.",
                properties = mapOf(
                    "filePath" to stringSchema("Project-relative file path"),
                    "content" to stringSchema("Complete new file content"),
                ),
                required = listOf("filePath", "content"),
            ),
            functionTool(
                name = CREATE_FILE,
                description = "Create a new UTF-8 text file after user approval. Never replace an existing file.",
                properties = mapOf(
                    "filePath" to stringSchema("Project-relative new file path"),
                    "content" to stringSchema("Complete new file content"),
                ),
                required = listOf("filePath", "content"),
            ),
            functionTool(
                name = RENAME_FILE,
                description = "Rename a text file after user approval. The destination must not exist.",
                properties = mapOf(
                    "filePath" to stringSchema("Project-relative source file path"),
                    "targetPath" to stringSchema("Project-relative destination file path"),
                ),
                required = listOf("filePath", "targetPath"),
            ),
            functionTool(
                name = DELETE_FILE,
                description = "Delete one text file after user approval. Does not delete directories.",
                properties = mapOf("filePath" to stringSchema("Project-relative file path")),
                required = listOf("filePath"),
            ),
            functionTool(
                name = SEARCH_IN_FILES,
                description = "Search for literal text in UTF-8 files in the opened project",
                properties = mapOf(
                    "query" to stringSchema("Literal case-insensitive search text"),
                ),
                required = listOf("query"),
            ),
        )

        private fun functionTool(
            name: String,
            description: String,
            properties: Map<String, JsonObject>,
            required: List<String>,
        ) = LlmTool(
            name = name,
            description = description,
            parameters = buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                put("required", buildJsonArray {
                    required.forEach { add(JsonPrimitive(it)) }
                })
                put("additionalProperties", false)
            },
        )

        private fun stringSchema(description: String) = buildJsonObject {
            put("type", "string")
            put("description", description)
        }
    }
}

class ToolArgumentsException(message: String) : Exception(message)

private fun Throwable.safeMessage(): String =
    message?.takeIf { it.isNotBlank() }?.take(500) ?: "Tool execution failed"
