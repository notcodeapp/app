package com.notcode.mobile.agent

import com.notcode.mobile.filesystem.FilePreviewDocument
import com.notcode.mobile.filesystem.FileRevision
import com.notcode.mobile.filesystem.ProjectAccessRevokedException
import com.notcode.mobile.filesystem.ProjectEntry
import com.notcode.mobile.filesystem.ProjectFileNotFoundException
import com.notcode.mobile.filesystem.ProjectFileSystem
import com.notcode.mobile.filesystem.ProjectPath
import com.notcode.mobile.filesystem.TextDocument
import com.notcode.mobile.filesystem.SafProjectFileSystem
import com.notcode.mobile.filesystem.WorkspaceProjectFileSystem
import java.io.File

/** Lets the agent inspect source-tree files that are intentionally absent from its text workspace. */
class AgentProjectFileSystem(
    private val workspace: ProjectFileSystem,
    private val source: ProjectFileSystem,
) : ProjectFileSystem {
    suspend fun stageFileForTerminal(path: ProjectPath, workspaceRoot: File): Pair<File, String> {
        require(!path.isRoot && !isSensitive(path)) { "This file cannot be staged for terminal access" }
        val workspaceFs = workspace as? WorkspaceProjectFileSystem
            ?: error("The local project workspace is unavailable")
        require(workspaceFs.directory.canonicalFile == workspaceRoot.canonicalFile) {
            "Terminal workspace does not match the active project"
        }
        val root = workspaceRoot.canonicalFile
        val staging = File(root, STAGING_DIRECTORY)
        if (java.nio.file.Files.isSymbolicLink(staging.toPath())) throw IllegalStateException("Symbolic staging folder is not allowed")
        if (!staging.isDirectory && !staging.mkdirs()) error("Cannot create terminal staging folder")
        val safeName = path.name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").ifBlank { "input.bin" }
        val staged = File(staging, "${java.util.UUID.randomUUID()}-$safeName")
        val sourceFs: ProjectFileSystem = if (workspaceFs.exists(path)) workspaceFs else source
        when (sourceFs) {
            is WorkspaceProjectFileSystem -> sourceFs.copyRawFileTo(path, staged, MAX_STAGED_FILE_BYTES)
            is SafProjectFileSystem -> sourceFs.copyRawFileTo(path, staged, MAX_STAGED_FILE_BYTES)
            else -> error("This project source cannot stage raw files")
        }
        val relative = staged.relativeTo(root).invariantSeparatorsPath
        return staged to "/workspace/$relative"
    }

    override suspend fun list(directory: ProjectPath): List<ProjectEntry> {
        val local = try {
            workspace.list(directory)
        } catch (_: ProjectFileNotFoundException) {
            emptyList()
        }
        val external = try {
            source.list(directory)
        } catch (_: ProjectFileNotFoundException) {
            emptyList()
        } catch (_: ProjectAccessRevokedException) {
            emptyList()
        }

        val entries = linkedMapOf<String, ProjectEntry>()
        external.forEach { entries[it.path.value] = it.copy(editable = false) }
        local.forEach { entries[it.path.value] = it.copy(editable = true) }
        return entries.values.sortedWith(
            compareByDescending<ProjectEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
        )
    }

    override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument = try {
        workspace.readText(path, maxBytes)
    } catch (_: ProjectFileNotFoundException) {
        source.readText(path, maxBytes)
    }

    override suspend fun readPreview(path: ProjectPath, maxBytes: Long): FilePreviewDocument = try {
        workspace.readPreview(path, maxBytes)
    } catch (_: ProjectFileNotFoundException) {
        source.readPreview(path, maxBytes)
    }

    override suspend fun writeText(
        path: ProjectPath,
        content: String,
        expectedRevision: FileRevision?,
    ): FileRevision = workspace.writeText(path, content, expectedRevision)

    override suspend fun writeBytes(
        path: ProjectPath,
        content: ByteArray,
        expectedRevision: FileRevision?,
    ): FileRevision = workspace.writeBytes(path, content, expectedRevision)

    override suspend fun renameFile(
        path: ProjectPath,
        target: ProjectPath,
        expectedRevision: FileRevision?,
    ): FileRevision = workspace.renameFile(path, target, expectedRevision)

    override suspend fun deleteFile(path: ProjectPath, expectedRevision: FileRevision?) =
        workspace.deleteFile(path, expectedRevision)

    override suspend fun exists(path: ProjectPath): Boolean =
        workspace.exists(path) || try {
            source.exists(path)
        } catch (_: ProjectAccessRevokedException) {
            false
        }

    suspend fun isWorkspaceFile(path: ProjectPath): Boolean = workspace.exists(path)

    private fun isSensitive(path: ProjectPath): Boolean = path.value.split('/').any {
        it.lowercase() in SENSITIVE_NAMES || it.lowercase().endsWith(".pem") || it.lowercase().endsWith(".p12") ||
            it.lowercase().endsWith(".key") || it.lowercase().endsWith(".keystore") || it.lowercase().endsWith(".jks")
    }

    companion object {
        const val STAGING_DIRECTORY = ".notcode-staging"
        const val MAX_STAGED_FILE_BYTES = 512L * 1024 * 1024
        private val SENSITIVE_NAMES = setOf(
            ".env", ".ssh", ".aws", ".gnupg", "credentials", "credentials.json", "secrets.json",
            "id_rsa", "id_ed25519", "keystore", "local.properties",
        )
    }
}
