package com.notcode.mobile.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AuthMode {
    BEARER,
    NONE,
}

@Serializable
enum class CapabilityMode {
    AUTO,
    ENABLED,
    DISABLED,
}

/** What the provider profile is used for: chat completions or the Images API. */
@Serializable
enum class ModelUsage {
    CHAT,
    IMAGE,
}

@Serializable
data class ProviderProfile(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String = "",
    val baseUrl: String,
    val modelId: String,
    val authMode: AuthMode = AuthMode.BEARER,
    val toolsMode: CapabilityMode = CapabilityMode.AUTO,
    val streamingMode: CapabilityMode = CapabilityMode.AUTO,
    val allowInsecureHttp: Boolean = false,
    val secretAlias: String? = "provider.$id.api-key",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val providerName: String = "",
    val usage: ModelUsage = ModelUsage.CHAT,
) {
    val title: String get() = displayName.ifBlank { modelId }
}

@Serializable
data class ProjectRecord(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val treeUri: String,
    val lastOpenedAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
data class SshConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val remoteDirectory: String = "/",
    val authType: SshAuthType = SshAuthType.PASSWORD,
)

@Serializable
enum class SshAuthType { PASSWORD, PRIVATE_KEY }

data class ProviderDraft(
    val id: String? = null,
    val displayName: String = "",
    val baseUrl: String,
    val modelId: String,
    val authMode: AuthMode,
    val toolsMode: CapabilityMode,
    val streamingMode: CapabilityMode,
    val allowInsecureHttp: Boolean,
)
