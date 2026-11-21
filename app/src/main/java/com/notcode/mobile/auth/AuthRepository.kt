package com.notcode.mobile.auth

import com.notcode.mobile.security.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val AUTH_TOKEN_ALIAS = "github_session_token"
private const val API_BASE = "https://gentle-base-825d.notkovvik.workers.dev"

data class AuthProfile(val username: String, val avatarUrl: String, val expiresAt: String)

class AuthRepository(private val secrets: SecretStore) {
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun acceptCallback(token: String, username: String): AuthProfile {
        require(token.matches(Regex("ncs_[A-Za-z0-9]{16,128}"))) { "Некорректный токен авторизации" }
        require(username.matches(Regex("[A-Za-z0-9-]{1,39}"))) { "Некорректное имя пользователя" }
        secrets.put(AUTH_TOKEN_ALIAS, token.toByteArray(Charsets.UTF_8))
        return try {
            profile(token).also { if (!it.username.equals(username, ignoreCase = true)) throw IllegalStateException("Имя пользователя не совпадает с профилем сервера") }
        } catch (error: Exception) {
            secrets.delete(AUTH_TOKEN_ALIAS)
            throw error
        }
    }

    suspend fun restore(): AuthProfile? {
        val bytes = secrets.get(AUTH_TOKEN_ALIAS) ?: return null
        val token = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        return try { profile(token) } catch (error: UnauthorizedException) {
            secrets.delete(AUTH_TOKEN_ALIAS)
            null
        }
    }

    suspend fun logout() { secrets.delete(AUTH_TOKEN_ALIAS) }

    suspend fun registerPushToken(fcmToken: String) {
        val bytes = secrets.get(AUTH_TOKEN_ALIAS) ?: return
        val bearer = try { bytes.toString(Charsets.UTF_8) } finally { bytes.fill(0) }
        val body = buildJsonObject { put("token", fcmToken) }.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$API_BASE/api/push/register")
                .header("Authorization", "Bearer $bearer").post(body).build()
            client.newCall(request).execute().use { response -> check(response.isSuccessful) { "Не удалось зарегистрировать push: HTTP ${response.code}" } }
        }
    }

    private suspend fun profile(token: String): AuthProfile = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$API_BASE/api/me").header("Authorization", "Bearer $token").get().build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401) throw UnauthorizedException()
            check(response.isSuccessful) { "Сервер авторизации вернул HTTP ${response.code}" }
            val body = requireNotNull(response.body?.string()) { "Пустой ответ сервера авторизации" }
            val user = requireNotNull(json.parseToJsonElement(body).jsonObject["user"]?.jsonObject) { "Неверный формат профиля" }
            AuthProfile(
                username = requireNotNull(user["username"]?.jsonPrimitive?.content),
                avatarUrl = user["avatar_url"]?.jsonPrimitive?.content.orEmpty(),
                expiresAt = user["expires_at"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    private class UnauthorizedException : Exception()
}
