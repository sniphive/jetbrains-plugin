package com.sniphive.idea.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Secure credential storage service using IDE Password Safe.
 */
@Service(Service.Level.APP)
class SecureCredentialStorage {

    companion object {
        private val LOG = Logger.getInstance(SecureCredentialStorage::class.java)

        // TSK-004: Non-blocking retry scheduler - schedules retries without blocking threads
        private val RETRY_SCHEDULER: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            val thread = Thread(r, "SnipHive-CredentialRetry")
            thread.isDaemon = true  // Daemon thread - won't prevent JVM shutdown
            thread
        }

        // In-memory token cache with LRU eviction to prevent memory leaks
        private val tokenCache = LinkedHashMap<String, String>()
        private const val MAX_CACHE_SIZE = 10

        private const val SERVICE_NAME = "SnipHive"
        private const val AUTH_TOKEN_PREFIX = "sniphive.auth.token."
        private const val API_KEY_PREFIX = "sniphive.api.key."
        private const val PRIVATE_KEY_PREFIX = "sniphive.private_key."
        private const val PUBLIC_KEY_PREFIX = "sniphive.public_key."
        private const val RECOVERY_CODE_PREFIX = "sniphive.recovery_code."
        private const val PASSWORD_PREFIX = "sniphive.password."
        private const val E2EE_SALT_PREFIX = "sniphive.e2ee_salt."
        private const val E2EE_IV_PREFIX = "sniphive.e2ee_iv."
        private const val E2EE_ITERATIONS_PREFIX = "sniphive.e2ee_iterations."
        private const val ENCRYPTED_PRIVATE_KEY_PREFIX = "sniphive.encrypted_private_key."
        private const val RECOVERY_ENCRYPTED_PRIVATE_KEY_PREFIX = "sniphive.recovery_encrypted_private_key."
        private const val RECOVERY_IV_PREFIX = "sniphive.recovery_iv."
        private const val RECOVERY_SALT_PREFIX = "sniphive.recovery_salt."
        private const val MASTER_PASSWORD_PREFIX = "sniphive.master_password."

        @JvmStatic
        fun getInstance(): SecureCredentialStorage = service()

        /**
         * Cache token with LRU eviction.
         * When cache reaches MAX_CACHE_SIZE, evict the oldest entry (first inserted).
         */
        private fun cacheToken(email: String, token: String) {
            // LRU eviction: remove oldest entry when cache is full
            if (tokenCache.size >= MAX_CACHE_SIZE) {
                tokenCache.keys.firstOrNull()?.let { oldestKey ->
                    tokenCache.remove(oldestKey)
                }
            }
            tokenCache[email] = token
        }

        /**
         * Clear all cached tokens.
         * Called on logout to prevent memory accumulation.
         */
        private fun clearTokenCache() {
            tokenCache.clear()
        }

        /**
         * Remove specific token from cache.
         * Called when removing auth token for a specific user.
         */
        private fun removeTokenFromCache(email: String) {
            tokenCache.remove(email)
        }
    }

    private fun createAttributes(key: String): CredentialAttributes {
        return CredentialAttributes(SERVICE_NAME, key)
    }

    fun storeAuthToken(project: Project?, email: String, token: String): Boolean {
        return try {
            val normalizedEmail = email.lowercase().trim()
            val key = "$AUTH_TOKEN_PREFIX$normalizedEmail"

            // Cache in memory first using LRU mechanism
            cacheToken(normalizedEmail, token)

            val credentials = Credentials(email, token)
            
            PasswordSafe.instance.set(createAttributes(key), credentials)

            // Verify storage by immediately retrieving
            val retrieved = PasswordSafe.instance.get(createAttributes(key))
            
            // Log if verification failed but still return true since we have it in memory cache
            if (retrieved == null) {
                LOG.warn("Auth token storage verification failed for $normalizedEmail, but token is cached in memory")
            }

            true
        } catch (e: Exception) {
            LOG.error("Failed to store authentication token for $email", e)
            false
        }
    }

/**
     * TSK-004: Synchronous version - NO retry logic to avoid Thread.sleep blocking.
     *
     * This method performs a single attempt without retries. For retry logic,
     * use getAuthTokenAsync() which uses non-blocking ScheduledExecutorService.
     *
     * Use this method only when:
     * - You need immediate synchronous result
     * - You're already on a background thread
     * - Retry logic is not critical
     *
     * For EDT operations or when retries are needed, use getAuthTokenAsync().
     *
     * @param project The project context (can be null)
     * @param email The user's email address
     * @return The auth token if found in cache or PasswordSafe, null otherwise
     */
    fun getAuthToken(project: Project?, email: String): String? {
        return try {
            val normalizedEmail = email.lowercase().trim()

            // Check memory cache first (PasswordSafe has timing issues)
            val cachedToken = tokenCache[normalizedEmail]
            if (cachedToken != null) {
                return cachedToken
            }

            val key = "$AUTH_TOKEN_PREFIX$normalizedEmail"

            // TSK-004: Single attempt only - no Thread.sleep blocking
            // For retry logic, use getAuthTokenAsync() with non-blocking ScheduledExecutorService
            val credentials = PasswordSafe.instance.get(createAttributes(key))

            if (credentials != null) {
                val token = credentials.getPasswordAsString()
                if (token != null) {
                    cacheToken(normalizedEmail, token)
                }
                return token
            }

            null
        } catch (e: Exception) {
            LOG.error("Failed to retrieve authentication token for $email", e)
            null
        }
    }

    /**
     * TSK-004: Non-blocking async version of getAuthToken.
     *
     * Uses ScheduledExecutorService to schedule retries with exponential backoff delays,
     * without blocking the calling thread. This is the recommended approach for IntelliJ
     * plugins where blocking Thread.sleep can block EDT or pooled threads.
     *
     * @param project The project context (can be null)
     * @param email The user's email address
     * @return CompletableFuture<String?> that completes with the token or null
     */
    fun getAuthTokenAsync(project: Project?, email: String): CompletableFuture<String?> {
        val result = CompletableFuture<String?>()
        val normalizedEmail = email.lowercase().trim()

        // Check memory cache first (PasswordSafe has timing issues)
        val cachedToken = tokenCache[normalizedEmail]
        if (cachedToken != null) {
            result.complete(cachedToken)
            return result
        }

        val key = "$AUTH_TOKEN_PREFIX$normalizedEmail"
        val maxRetries = 3
        val initialBackoffMs = 50L

        // Attempt to get credentials immediately (first try)
        attemptCredentialRetrieval(result, key, normalizedEmail, 0, maxRetries, initialBackoffMs)

        return result
    }

    /**
     * TSK-004: Internal helper for non-blocking credential retrieval with retries.
     *
     * Schedules retry attempts using RETRY_SCHEDULER instead of Thread.sleep.
     * Each retry is scheduled with exponential backoff delay.
     *
     * @param result The CompletableFuture to complete
     * @param key The credential key
     * @param normalizedEmail The normalized email for caching
     * @param currentRetry Current retry count (0-based)
     * @param maxRetries Maximum retry attempts
     * @param backoffMs Current backoff delay in milliseconds
     */
    private fun attemptCredentialRetrieval(
        result: CompletableFuture<String?>,
        key: String,
        normalizedEmail: String,
        currentRetry: Int,
        maxRetries: Int,
        backoffMs: Long
    ) {
        try {
            val credentials = PasswordSafe.instance.get(createAttributes(key))

            if (credentials != null) {
                // Success - cache and complete
                val token = credentials.getPasswordAsString()
                if (token != null) {
                    cacheToken(normalizedEmail, token)
                }
                result.complete(token)
                return
            }

            // Credentials not found - schedule retry if we have attempts left
            if (currentRetry < maxRetries - 1) {
                val nextRetry = currentRetry + 1
                val nextBackoffMs = backoffMs * 2  // Exponential backoff: 50ms -> 100ms -> 200ms

                // Schedule retry without blocking thread
                RETRY_SCHEDULER.schedule({
                    attemptCredentialRetrieval(result, key, normalizedEmail, nextRetry, maxRetries, nextBackoffMs)
                }, backoffMs, TimeUnit.MILLISECONDS)
            } else {
                // Max retries exhausted - complete with null
                result.complete(null)
            }
        } catch (e: Exception) {
            LOG.warn("Error retrieving credentials on retry $currentRetry: ${e.message}")
            result.complete(null)
        }
    }

    fun removeAuthToken(project: Project?, email: String): Boolean {
        return try {
            val normalizedEmail = email.lowercase().trim()
            val key = "$AUTH_TOKEN_PREFIX$normalizedEmail"
            
            // Clear from memory cache using removeTokenFromCache
            removeTokenFromCache(normalizedEmail)
            
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove authentication token for $email", e)
            false
        }
    }

    fun storeApiKey(project: Project?, email: String, apiKey: String): Boolean {
        return try {
            val key = "$API_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, apiKey))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store API key for user $email", e)
            false
        }
    }

    fun getApiKey(project: Project?, email: String): String? {
        return try {
            val key = "$API_KEY_PREFIX${email.lowercase()}"
            val credentials = PasswordSafe.instance.get(createAttributes(key))
            credentials?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve API key for user $email", e)
            null
        }
    }

    fun removeApiKey(project: Project?, email: String): Boolean {
        return try {
            val key = "$API_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove API key for user $email", e)
            false
        }
    }

    fun storePrivateKey(project: Project?, email: String, privateKey: String): Boolean {
        return try {
            val key = "$PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, privateKey))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store private key for user $email", e)
            false
        }
    }

    fun getPrivateKey(project: Project?, email: String): String? {
        return try {
            val key = "$PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve private key for user $email", e)
            null
        }
    }

    fun removePrivateKey(project: Project?, email: String): Boolean {
        return try {
            val key = "$PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove private key for user $email", e)
            false
        }
    }

    fun storePublicKey(project: Project?, email: String, publicKey: String): Boolean {
        return try {
            val key = "$PUBLIC_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, publicKey))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store public key for user $email", e)
            false
        }
    }

    fun getPublicKey(project: Project?, email: String): String? {
        return try {
            val key = "$PUBLIC_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve public key for user $email", e)
            null
        }
    }

    fun removePublicKey(project: Project?, email: String): Boolean {
        return try {
            val key = "$PUBLIC_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove public key for user $email", e)
            false
        }
    }

    fun storeRecoveryCode(project: Project?, email: String, recoveryCode: String): Boolean {
        return try {
            val key = "$RECOVERY_CODE_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, recoveryCode))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store recovery code for user $email", e)
            false
        }
    }

    fun getRecoveryCode(project: Project?, email: String): String? {
        return try {
            val key = "$RECOVERY_CODE_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve recovery code for user $email", e)
            null
        }
    }

    fun removeRecoveryCode(project: Project?, email: String): Boolean {
        return try {
            val key = "$RECOVERY_CODE_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove recovery code for user $email", e)
            false
        }
    }

    fun storePassword(project: Project?, email: String, password: String): Boolean {
        return try {
            val key = "$PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, password))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store password for user $email", e)
            false
        }
    }

    fun getPassword(project: Project?, email: String): String? {
        return try {
            val key = "$PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve password for user $email", e)
            null
        }
    }

    fun removePassword(project: Project?, email: String): Boolean {
        return try {
            val key = "$PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove password for user $email", e)
            false
        }
    }

    // Master Password methods (for E2EE auto-unlock)
    /**
     * Store the master password for auto-unlock functionality.
     * The password is stored securely in the OS keychain.
     */
    fun storeMasterPassword(project: Project?, email: String, masterPassword: String): Boolean {
        return try {
            val key = "$MASTER_PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, masterPassword))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store master password for user $email", e)
            false
        }
    }

    /**
     * Retrieve the stored master password for auto-unlock.
     */
    fun getMasterPassword(project: Project?, email: String): String? {
        return try {
            val key = "$MASTER_PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve master password for user $email", e)
            null
        }
    }

    /**
     * Remove the stored master password (on logout or failed unlock).
     */
    fun removeMasterPassword(project: Project?, email: String): Boolean {
        return try {
            val key = "$MASTER_PASSWORD_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), null)
            true
        } catch (e: Exception) {
            LOG.error("Failed to remove master password for user $email", e)
            false
        }
    }

fun removeAllCredentialsForUser(project: Project?, email: String): Boolean {
        var allSuccess = true
        allSuccess = removeAuthToken(project, email) && allSuccess
        allSuccess = removeApiKey(project, email) && allSuccess
        allSuccess = removePrivateKey(project, email) && allSuccess
        allSuccess = removePublicKey(project, email) && allSuccess
        allSuccess = removeRecoveryCode(project, email) && allSuccess
        allSuccess = removePassword(project, email) && allSuccess
        allSuccess = removeMasterPassword(project, email) && allSuccess

        // Clear all cached tokens on logout to prevent memory accumulation
        clearTokenCache()

        if (!allSuccess) {
            LOG.warn("Some credentials could not be removed for user: ${email.lowercase()}")
        }
        return allSuccess
    }

    fun hasAnyCredentials(project: Project?, email: String): Boolean {
        return getAuthToken(project, email) != null ||
                getApiKey(project, email) != null ||
                getPrivateKey(project, email) != null ||
                getPublicKey(project, email) != null ||
                getRecoveryCode(project, email) != null ||
                getPassword(project, email) != null ||
                getMasterPassword(project, email) != null
    }

    // E2EE-related methods
    fun getEncryptedPrivateKey(project: Project?, email: String): String? {
        return try {
            val key = "$ENCRYPTED_PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve encrypted private key for user $email", e)
            null
        }
    }

    fun storeEncryptedPrivateKey(project: Project?, email: String, encryptedKey: String): Boolean {
        return try {
            val key = "$ENCRYPTED_PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, encryptedKey))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store encrypted private key for user $email", e)
            false
        }
    }

    fun getE2EESalt(project: Project?, email: String): String? {
        return try {
            val key = "$E2EE_SALT_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve E2EE salt for user $email", e)
            null
        }
    }

    fun storeE2EESalt(project: Project?, email: String, salt: String): Boolean {
        return try {
            val key = "$E2EE_SALT_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, salt))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store E2EE salt for user $email", e)
            false
        }
    }

    fun getE2EEIterations(project: Project?, email: String): Int? {
        return try {
            val key = "$E2EE_ITERATIONS_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()?.toIntOrNull()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve E2EE iterations for user $email", e)
            null
        }
    }

    fun storeE2EEIterations(project: Project?, email: String, iterations: Int): Boolean {
        return try {
            val key = "$E2EE_ITERATIONS_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, iterations.toString()))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store E2EE iterations for user $email", e)
            false
        }
    }

    fun getE2EEIV(project: Project?, email: String): String? {
        return try {
            val key = "$E2EE_IV_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve E2EE IV for user $email", e)
            null
        }
    }

    fun storeE2EEIV(project: Project?, email: String, iv: String): Boolean {
        return try {
            val key = "$E2EE_IV_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, iv))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store E2EE IV for user $email", e)
            false
        }
    }

    /**
     * Store all E2EE data in a single call.
     */
    fun storeE2EEData(
        project: Project?,
        email: String,
        encryptedPrivateKey: String,
        recoveryEncryptedPrivateKey: String,
        privateKeyIV: String,
        recoveryIV: String,
        kdfSalt: String,
        recoverySalt: String,
        kdfIterations: Int
    ): Boolean {
        var allSuccess = true
        allSuccess = storeEncryptedPrivateKey(project, email, encryptedPrivateKey) && allSuccess
        allSuccess = storeRecoveryEncryptedPrivateKey(project, email, recoveryEncryptedPrivateKey) && allSuccess
        allSuccess = storeE2EEIV(project, email, privateKeyIV) && allSuccess
        allSuccess = storeRecoveryIV(project, email, recoveryIV) && allSuccess
        allSuccess = storeE2EESalt(project, email, kdfSalt) && allSuccess
        allSuccess = storeRecoverySalt(project, email, recoverySalt) && allSuccess
        allSuccess = storeE2EEIterations(project, email, kdfIterations) && allSuccess

        if (!allSuccess) {
            LOG.warn("Some E2EE data could not be stored for user: ${email.lowercase()}")
        }
        return allSuccess
    }

    fun storeRecoveryEncryptedPrivateKey(project: Project?, email: String, encryptedKey: String): Boolean {
        return try {
            val key = "$RECOVERY_ENCRYPTED_PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, encryptedKey))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store recovery encrypted private key for user $email", e)
            false
        }
    }

    fun getRecoveryEncryptedPrivateKey(project: Project?, email: String): String? {
        return try {
            val key = "$RECOVERY_ENCRYPTED_PRIVATE_KEY_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve recovery encrypted private key for user $email", e)
            null
        }
    }

    fun storeRecoveryIV(project: Project?, email: String, iv: String): Boolean {
        return try {
            val key = "$RECOVERY_IV_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, iv))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store recovery IV for user $email", e)
            false
        }
    }

    fun getRecoveryIV(project: Project?, email: String): String? {
        return try {
            val key = "$RECOVERY_IV_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve recovery IV for user $email", e)
            null
        }
    }

    fun storeRecoverySalt(project: Project?, email: String, salt: String): Boolean {
        return try {
            val key = "$RECOVERY_SALT_PREFIX${email.lowercase()}"
            PasswordSafe.instance.set(createAttributes(key), Credentials(email, salt))
            true
        } catch (e: Exception) {
            LOG.error("Failed to store recovery salt for user $email", e)
            false
        }
    }

    fun getRecoverySalt(project: Project?, email: String): String? {
        return try {
            val key = "$RECOVERY_SALT_PREFIX${email.lowercase()}"
            PasswordSafe.instance.get(createAttributes(key))?.getPasswordAsString()
        } catch (e: Exception) {
            LOG.error("Failed to retrieve recovery salt for user $email", e)
            null
        }
    }
}