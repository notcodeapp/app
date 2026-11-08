package com.notcode.mobile

import androidx.test.platform.app.InstrumentationRegistry
import com.notcode.mobile.filesystem.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class WorkspaceManifestTest {
    @Test fun existingWorkspaceManifestsOpenWithoutChangingFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val projects = File(context.filesDir.canonicalFile, "projects")
        for (project in projects.listFiles().orEmpty()) {
            if (!File(project, "work-sync.json").isFile) continue
            val fs = WorkspaceProjectFileSystem(File(project, "work"))
            val noImport = object : ProjectFileSystem {
                override suspend fun list(directory: ProjectPath): List<ProjectEntry> = error("Existing workspace must not import")
                override suspend fun readText(path: ProjectPath, maxBytes: Long): TextDocument = error("Existing workspace must not read external files")
                override suspend fun writeText(path: ProjectPath, content: String, expectedRevision: FileRevision?): FileRevision = error("No writes allowed")
                override suspend fun exists(path: ProjectPath): Boolean = error("No external access allowed")
            }
            fs.importFrom(noImport)
        }
    }
}
