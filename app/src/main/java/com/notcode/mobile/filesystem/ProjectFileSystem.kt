package com.notcode.mobile.filesystem

@JvmInline
value class ProjectPath private constructor(val value: String) {
    val isRoot: Boolean get() = value.isEmpty()
    val name: String get() = value.substringAfterLast('/', value)
    val parent: ProjectPath
        get() = if ('/' in value) parse(value.substringBeforeLast('/')) else Root

    fun child(name: String): ProjectPath = parse(
        if (isRoot) name else "$value/$name",
    )

    override fun toString(): String = value

    companion object {
        val Root = ProjectPath("")

        fun parse(raw: String): ProjectPath {
            val candidate = raw.trim()
            require('\u0000' !in candidate) { "NUL is not allowed in a path" }
            require(!candidate.startsWith('/') && !candidate.startsWith('\\')) {
                "Absolute paths are not allowed"
            }
            require(!Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(candidate)) {
                "Absolute paths are not allowed"
            }

            val normalized = candidate.replace('\\', '/').trimEnd('/')
            val parts = normalized.split('/').filter { it.isNotEmpty() }
            require(parts.none { it == "." || it == ".." }) {
                "Path traversal is not allowed"
            }
            return if (parts.isEmpty()) Root else ProjectPath(parts.joinToString("/"))
        }
    }
}

data class FileRevision(
    val documentUri: String,
    val size: Long,
    val lastModifiedEpochMs: Long,
)

data class ProjectEntry(
    val path: ProjectPath,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModifiedEpochMs: Long,
    /** False for files exposed by the source folder but not editable in the text workspace. */
    val editable: Boolean = true,
)

data class TextDocument(
    val path: ProjectPath,
    val text: String,
    val revision: FileRevision,
)

/** Bounded, read-only byte preview for files that are not UTF-8 text. */
data class FilePreviewDocument(
    val path: ProjectPath,
    val bytes: ByteArray,
    val revision: FileRevision,
    val truncated: Boolean,
)

interface ProjectFileSystem {
    suspend fun list(directory: ProjectPath): List<ProjectEntry>
    suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument
    suspend fun readPreview(path: ProjectPath, maxBytes: Long): FilePreviewDocument {
        val document = readText(path, maxBytes)
        val bytes = document.text.toByteArray(Charsets.UTF_8)
        return FilePreviewDocument(path, bytes, document.revision, truncated = false)
    }
    suspend fun writeText(
        path: ProjectPath,
        content: String,
        expectedRevision: FileRevision? = null,
    ): FileRevision

    /** Binary write for content the text workspace cannot represent, such as generated images. */
    suspend fun writeBytes(
        path: ProjectPath,
        content: ByteArray,
        expectedRevision: FileRevision? = null,
    ): FileRevision = throw UnsupportedOperationException("Binary write is not supported")
    /** File-only mutations. Authorization belongs to the caller, not the filesystem. */
    suspend fun renameFile(
        path: ProjectPath,
        target: ProjectPath,
        expectedRevision: FileRevision? = null,
    ): FileRevision = throw UnsupportedOperationException("File rename is not supported")

    suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision? = null): Unit =
        throw UnsupportedOperationException("File deletion is not supported")

    suspend fun exists(path: ProjectPath): Boolean
}

open class ProjectFileException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

class ProjectAccessRevokedException(cause: Throwable? = null) :
    ProjectFileException("Access to the project folder was revoked", cause)

class ProjectFileNotFoundException(path: ProjectPath) :
    ProjectFileException("File not found: ${path.value}")

class ProjectFileConflictException(path: ProjectPath) :
    ProjectFileException("File was changed outside NotCode: ${path.value}")

class ProjectFileTooLargeException(path: ProjectPath, limit: Long) :
    ProjectFileException("File is larger than the ${limit / 1024} KB limit: ${path.value}")

class ProjectBinaryFileException(path: ProjectPath) :
    ProjectFileException("File is not UTF-8 text: ${path.value}")
