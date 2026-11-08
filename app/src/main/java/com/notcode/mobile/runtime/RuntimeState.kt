package com.notcode.mobile.runtime

data class RuntimeState(
    val installing: Boolean = false,
    val ubuntuReady: Boolean = false,
    val powershellReady: Boolean = false,
    val progress: String = "",
    val unsupportedReason: String? = null,
)

data class RuntimeArtifact(val url: String, val sha256: String, val maxBytes: Long)
