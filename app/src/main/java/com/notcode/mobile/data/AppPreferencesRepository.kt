package com.notcode.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.notcode.mobile.update.DEFAULT_RELEASE_REPOSITORY
import androidx.datastore.preferences.preferencesDataStore
import com.notcode.mobile.model.ProjectRecord
import com.notcode.mobile.model.ProviderProfile
import com.notcode.mobile.model.SshConnectionProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.IOException

private val Context.notCodeDataStore by preferencesDataStore(name = "notcode_preferences")

class AppPreferencesRepository(
    context: Context,
    private val json: Json,
) {
    private val dataStore = context.notCodeDataStore

    private object Keys {
        val profiles = stringPreferencesKey("provider_profiles_v1")
        val activeProfileId = stringPreferencesKey("active_provider_profile_id")
        val projects = stringPreferencesKey("projects_v1")
        val activeProjectId = stringPreferencesKey("active_project_id")
        val agentPrivacyAccepted = booleanPreferencesKey("agent_privacy_accepted")
        val themeMode = stringPreferencesKey("theme_mode")
        val sshProfile = stringPreferencesKey("ssh_profile_v1")
        val updateRepository = stringPreferencesKey("update_repository")
        val autoUpdateEnabled = booleanPreferencesKey("auto_update_enabled")
        val lastUpdateCheckAt = longPreferencesKey("last_update_check_at")
    }

    private val safeData: Flow<Preferences> = dataStore.data.catch { error ->
        if (error is IOException) emit(emptyPreferences()) else throw error
    }

    val profiles: Flow<List<ProviderProfile>> = safeData.map { preferences ->
        decodeProfiles(preferences[Keys.profiles])
    }

    val activeProfileId: Flow<String?> = safeData.map { it[Keys.activeProfileId] }

    val projects: Flow<List<ProjectRecord>> = safeData.map { preferences ->
        decodeProjects(preferences[Keys.projects])
            .sortedByDescending { it.lastOpenedAtEpochMs }
    }

    val activeProjectId: Flow<String?> = safeData.map { it[Keys.activeProjectId] }

    val agentPrivacyAccepted: Flow<Boolean> = safeData.map {
        it[Keys.agentPrivacyAccepted] ?: false
    }

    val themeMode: Flow<String> = safeData.map { it[Keys.themeMode] ?: "system" }

    val sshProfile: Flow<SshConnectionProfile?> = safeData.map { preferences ->
        preferences[Keys.sshProfile]?.let { runCatching { json.decodeFromString(SshConnectionProfile.serializer(), it) }.getOrNull() }
    }

    suspend fun saveSshProfile(profile: SshConnectionProfile) {
        dataStore.edit { it[Keys.sshProfile] = json.encodeToString(SshConnectionProfile.serializer(), profile) }
    }

    val updateRepository: Flow<String> = safeData.map {
        it[Keys.updateRepository] ?: DEFAULT_RELEASE_REPOSITORY
    }

    val autoUpdateEnabled: Flow<Boolean> = safeData.map { it[Keys.autoUpdateEnabled] ?: true }

    val lastUpdateCheckAt: Flow<Long> = safeData.map { it[Keys.lastUpdateCheckAt] ?: 0L }

    suspend fun setUpdateRepository(repository: String) {
        dataStore.edit { it[Keys.updateRepository] = repository }
    }

    suspend fun setAutoUpdateEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.autoUpdateEnabled] = enabled }
    }

    suspend fun setLastUpdateCheckAt(epochMs: Long) {
        dataStore.edit { it[Keys.lastUpdateCheckAt] = epochMs }
    }

    suspend fun setThemeMode(mode: String) {
        require(mode in setOf("system", "light", "dark"))
        dataStore.edit { it[Keys.themeMode] = mode }
    }

    suspend fun moveProfile(id: String, direction: Int) {
        require(direction == -1 || direction == 1)
        dataStore.edit { preferences ->
            val items = decodeProfiles(preferences[Keys.profiles]).toMutableList()
            val index = items.indexOfFirst { it.id == id }
            val target = index + direction
            if (index >= 0 && target in items.indices) {
                val item = items.removeAt(index)
                items.add(target, item)
                preferences[Keys.profiles] = json.encodeToString(ListSerializer(ProviderProfile.serializer()), items)
            }
        }
    }

    suspend fun saveProfile(profile: ProviderProfile) {
        dataStore.edit { preferences ->
            val profiles = decodeProfiles(preferences[Keys.profiles]).toMutableList()
            val index = profiles.indexOfFirst { it.id == profile.id }
            if (index >= 0) profiles[index] = profile else profiles += profile
            preferences[Keys.profiles] = json.encodeToString(
                ListSerializer(ProviderProfile.serializer()),
                profiles,
            )
            if (preferences[Keys.activeProfileId] == null) {
                preferences[Keys.activeProfileId] = profile.id
            }
        }
    }

    suspend fun deleteProfile(profileId: String) {
        dataStore.edit { preferences ->
            val remaining = decodeProfiles(preferences[Keys.profiles])
                .filterNot { it.id == profileId }
            preferences[Keys.profiles] = json.encodeToString(
                ListSerializer(ProviderProfile.serializer()),
                remaining,
            )
            if (preferences[Keys.activeProfileId] == profileId) {
                remaining.firstOrNull()?.id?.let {
                    preferences[Keys.activeProfileId] = it
                } ?: preferences.remove(Keys.activeProfileId)
            }
        }
    }

    suspend fun setActiveProfile(profileId: String) {
        dataStore.edit { it[Keys.activeProfileId] = profileId }
    }

    suspend fun requireProfile(profileId: String): ProviderProfile {
        var result: ProviderProfile? = null
        dataStore.edit { preferences ->
            result = decodeProfiles(preferences[Keys.profiles])
                .firstOrNull { it.id == profileId }
        }
        return requireNotNull(result) { "Provider profile not found" }
    }

    suspend fun saveProject(project: ProjectRecord) {
        dataStore.edit { preferences ->
            val projects = decodeProjects(preferences[Keys.projects]).toMutableList()
            val duplicateUri = projects.indexOfFirst { it.treeUri == project.treeUri }
            val duplicateId = projects.indexOfFirst { it.id == project.id }
            val index = if (duplicateId >= 0) duplicateId else duplicateUri
            if (index >= 0) projects[index] = project else projects += project
            preferences[Keys.projects] = json.encodeToString(
                ListSerializer(ProjectRecord.serializer()),
                projects,
            )
            preferences[Keys.activeProjectId] = project.id
        }
    }

    suspend fun deleteProject(projectId: String) {
        dataStore.edit { preferences ->
            val remaining = decodeProjects(preferences[Keys.projects])
                .filterNot { it.id == projectId }
            preferences[Keys.projects] = json.encodeToString(
                ListSerializer(ProjectRecord.serializer()),
                remaining,
            )
            if (preferences[Keys.activeProjectId] == projectId) {
                preferences.remove(Keys.activeProjectId)
            }
        }
    }

    suspend fun setActiveProject(projectId: String?) {
        dataStore.edit { preferences ->
            if (projectId == null) preferences.remove(Keys.activeProjectId)
            else preferences[Keys.activeProjectId] = projectId
        }
    }

    suspend fun setAgentPrivacyAccepted(accepted: Boolean) {
        dataStore.edit { it[Keys.agentPrivacyAccepted] = accepted }
    }

    private fun decodeProfiles(value: String?): List<ProviderProfile> =
        decodeList(value, ProviderProfile.serializer())

    private fun decodeProjects(value: String?): List<ProjectRecord> =
        decodeList(value, ProjectRecord.serializer())

    private fun <T> decodeList(
        value: String?,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(serializer), value)
        }.getOrElse { emptyList() }
    }
}
