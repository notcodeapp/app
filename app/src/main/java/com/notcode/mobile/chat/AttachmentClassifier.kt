package com.notcode.mobile.chat

/** How an attached file should reach the model. */
enum class AttachmentKind { IMAGE, TEXT, BINARY, SECRET }

data class AttachmentDecision(
    val kind: AttachmentKind,
    val displayName: String,
)

/** Text inlined straight into the model message. */
data class InlineAttachment(val name: String, val content: String, val truncated: Boolean)

/** Binary file copied into the project so tools and the terminal can open it. */
data class StagedAttachment(val displayName: String, val projectPath: String)

data class AttachmentBundle(
    val imageDataUrls: List<String>,
    val inlineFiles: List<InlineAttachment>,
    val stagedFiles: List<StagedAttachment>,
    val skipped: List<String>,
) {
    val hasContent: Boolean
        get() = imageDataUrls.isNotEmpty() || inlineFiles.isNotEmpty() || stagedFiles.isNotEmpty()

    fun summary(): String {
        val parts = buildList {
            if (imageDataUrls.isNotEmpty()) add("изображений: ${imageDataUrls.size}")
            val documents = inlineFiles.size + stagedFiles.size
            if (documents > 0) add("файлов: $documents")
            if (skipped.isNotEmpty()) add("пропущено: ${skipped.size}")
        }
        return if (parts.isEmpty()) "" else "\n[Прикреплено ${parts.joinToString(", ")}]"
    }
}

/**
 * Classifies content URIs for chat attachments. Content providers often report
 * `application/octet-stream`, so the display name is used as a second signal.
 */
object AttachmentClassifier {
    const val MAX_IMAGE_TOTAL_BYTES = 20L * 1024 * 1024
    const val MAX_INLINE_TEXT_BYTES = 64L * 1024
    const val MAX_INLINE_TEXT_TOTAL_BYTES = 200_000L
    const val MAX_BINARY_FILE_BYTES = 64L * 1024 * 1024
    const val MAX_ATTACHMENT_FILES = 4

    private val TEXT_MIME_TYPES = setOf(
        "application/json", "application/ld+json", "application/xml", "application/xhtml+xml",
        "application/x-yaml", "application/yaml", "application/toml", "application/sql",
        "application/javascript", "application/x-javascript", "application/typescript",
        "application/x-sh", "application/shellscript", "application/x-httpd-php",
        "application/x-python", "application/python", "application/csv", "image/svg+xml",
        "application/x-empty",
    )

    private val TEXT_EXTENSIONS = setOf(
        "txt", "md", "mdx", "markdown", "rst", "log", "csv", "tsv", "json", "jsonc", "json5",
        "yaml", "yml", "toml", "ini", "cfg", "conf", "properties", "xml", "html", "htm", "css",
        "scss", "sass", "less", "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "astro",
        "kt", "kts", "java", "scala", "groovy", "py", "pyi", "rb", "go", "rs", "c", "h", "cpp",
        "hpp", "cc", "hh", "cs", "php", "swift", "m", "mm", "dart", "lua", "r", "pl", "pm", "sh",
        "bash", "zsh", "fish", "ps1", "psm1", "bat", "cmd", "sql", "graphql", "prisma", "proto",
        "tf", "tfvars", "dockerfile", "dockerignore", "makefile", "gradle", "pro", "editorconfig",
        "gitignore", "gitattributes", "ipynb", "svg", "avr", "ino",
    )

    /** Extensionless files that are still plain text. */
    private val TEXT_NAMES = setOf(
        "dockerfile", "makefile", "gemfile", "rakefile", "podfile", "procfile", "vagrantfile",
        "jenkinsfile", "cmakelists", "readme", "license", "copying", "changelog", "notice",
        "authors", "contributing", "todo", "hosts", "passwd", "group",
    )

    private val SECRET_EXTENSIONS = setOf(
        "pem", "key", "p12", "pfx", "jks", "keystore", "asc", "kdbx",
    )

    private val SECRET_NAMES = setOf(
        ".env", ".ssh", ".aws", ".gnupg", ".npmrc", ".netrc", "credentials", "credentials.json",
        "secrets.json", "id_rsa", "id_ed25519", "id_ecdsa", "google-services.json",
        "service-account.json", "git-credentials", "token.json",
    )

    fun classify(rawMimeType: String?, rawName: String?): AttachmentDecision {
        val name = rawName?.trim().orEmpty().ifBlank { "файл" }
        val lowerName = name.lowercase()
        val extension = lowerName.substringAfterLast('.', "")
        val mime = rawMimeType?.trim()?.lowercase()?.takeIf { it.isNotBlank() && it != "application/octet-stream" }

        if (extension == "env" || lowerName.startsWith(".env") ||
            lowerName in SECRET_NAMES || extension in SECRET_EXTENSIONS
        ) {
            return AttachmentDecision(AttachmentKind.SECRET, name)
        }
        if (mime != null && mime.startsWith("image/") && mime != "image/svg+xml") {
            return AttachmentDecision(AttachmentKind.IMAGE, name)
        }
        if (mime != null && (mime.startsWith("text/") || mime in TEXT_MIME_TYPES)) {
            return AttachmentDecision(AttachmentKind.TEXT, name)
        }
        if (mime == null && (extension in TEXT_EXTENSIONS || lowerName in TEXT_NAMES)) {
            return AttachmentDecision(AttachmentKind.TEXT, name)
        }
        return AttachmentDecision(AttachmentKind.BINARY, name)
    }

    fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.size.coerceAtMost(4096)
        for (index in 0 until sample) {
            if (bytes[index] == 0.toByte()) return false
        }
        val decoded = String(bytes, 0, sample, Charsets.UTF_8)
        return decoded.indexOf('\uFFFD') < 0
    }

    fun safeStoredName(rawName: String): String {
        val cleaned = rawName.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").trim('.', '_')
        return cleaned.take(80).ifBlank { "attachment" }
    }
}
