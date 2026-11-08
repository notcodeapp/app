package com.notcode.mobile.security

interface SecretStore {
    suspend fun put(alias: String, plaintext: ByteArray)
    suspend fun get(alias: String): ByteArray?
    suspend fun delete(alias: String)
    suspend fun contains(alias: String): Boolean
}

class SecretStoreException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
