package com.notcode.mobile.browser

import android.content.Context
import com.notcode.mobile.filesystem.ProjectPath
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PreviewState(val url: String? = null, val title: String = "", val error: String? = null)

/** Owned by the caller/VM, not by a terminal or a composition. No model-action bridge. */
class PreviewController(context: Context) {
    private val projects = File(context.applicationContext.filesDir, "projects").canonicalFile
    private val mutableState = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = mutableState.asStateFlow()
    private var server: ProjectPreviewServer? = null
    private var serverRoot: File? = null

    @Synchronized
    fun openUrl(raw: String) {
        runCatching { PreviewUrlPolicy.normalize(raw) }
            .onSuccess { mutableState.value = PreviewState(url = it) }
            .onFailure { reportError(it.message ?: "Cannot open URL") }
    }

    @Synchronized
    fun openProjectFile(root: File, path: ProjectPath) {
        runCatching {
            val canonical = root.canonicalFile
            // Accept only the existing private working-copy layout, not filesDir, SAF, or terminal home.
            require(root.absoluteFile == canonical && canonical.name == "work" &&
                canonical.parentFile?.parentFile == projects) { "Preview root must be a project workspace" }
            if (serverRoot != canonical) {
                server?.close()
                server = null
                serverRoot = null
                server = ProjectPreviewServer(canonical)
                serverRoot = canonical
            }
            requireNotNull(server).urlFor(path.value)
        }.onSuccess { mutableState.value = PreviewState(url = it, title = path.name) }
            .onFailure { reportError(it.message ?: "Cannot preview project file") }
    }

    internal fun reportError(message: String) {
        mutableState.value = mutableState.value.copy(error = message)
    }

    internal fun page(url: String?, title: String?) {
        if (url != null && PreviewUrlPolicy.allows(url)) {
            mutableState.value = mutableState.value.copy(url = url, title = title.orEmpty().take(512))
        }
    }

    @Synchronized
    fun close() {
        server?.close()
        server = null
        serverRoot = null
        mutableState.value = PreviewState()
    }
}
