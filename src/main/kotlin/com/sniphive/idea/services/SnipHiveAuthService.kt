package com.sniphive.idea.services

import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.LoginResponse
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ConcurrentHashMap

/**
 * Authentication service for SnipHive API login and token management.
 */
@Service(Service.Level.APP)
class SnipHiveAuthService : Disposable {

    companion object {
        private val LOG = Logger.getInstance(SnipHiveAuthService::class.java)

        private const val LOGIN_ENDPOINT = "/api/v1/login"
        // API doesn't have refresh/logout/verify endpoints - token management is client-side
        // Logout is handled by removing the token locally

        private val projectSettingsCache = ConcurrentHashMap<String, SnipHiveSettings>()

        @JvmStatic
        fun getInstance(): SnipHiveAuthService = service()
    }

    data class LoginRequest(val email: String, val password: String)

    data class LoginResult(
        val success: Boolean,
        val message: String,
        val user: LoginResponse.User? = null,
        val workspaces: List<LoginResponse.Workspace> = emptyList()
    )

    fun login(project: Project?, apiUrl: String, email: String, password: String): LoginResult {
        return try {

            val apiClient = SnipHiveApiClient.getInstance()
            val secureStorage = SecureCredentialStorage.getInstance()

            val loginRequest = LoginRequest(email.trim(), password)

            val response = apiClient.post<LoginResponse>(
                apiUrl = apiUrl,
                endpoint = LOGIN_ENDPOINT,
                token = null,
                body = loginRequest,
                responseType = LoginResponse::class.java
            )


            if (response.success && response.data != null) {
                val loginData = response.data

                val tokenStored = secureStorage.storeAuthToken(project, email, loginData.token)

                if (tokenStored) {
                    if (project != null) {
                        val settings = SnipHiveSettings.getInstance(project)
                        settings.setUserEmail(email)
                        loginData.user?.name?.let { settings.setUserName(it) }
                        // Set first workspace as default if available
                        loginData.workspaces.firstOrNull()?.let { ws ->
                            val workspaceId = ws.uuid ?: ws.id.toString()
                            settings.setWorkspaceId(workspaceId)
                        }
                    }

                    LoginResult(
                        success = true,
                        message = "Login successful",
                        user = loginData.user,
                        workspaces = loginData.workspaces
                    )
                } else {
                    LoginResult(success = false, message = "Failed to store authentication token securely")
                }
            } else {
                val errorMessage = response.error ?: "Login failed"
                LoginResult(success = false, message = errorMessage)
            }
        } catch (e: Exception) {
            LoginResult(success = false, message = "An unexpected error occurred during login: ${e.message}")
        }
    }

    fun getAuthToken(project: Project?, email: String): String? {
        return try {
            val secureStorage = SecureCredentialStorage.getInstance()
            val token = secureStorage.getAuthToken(project, email)
            if (token != null) {
                LOG.debug("Authentication token retrieved for user: ${email.lowercase()}")
            }
            token
        } catch (e: Exception) {
            LOG.error("Failed to retrieve authentication token for user ${email.lowercase()}", e)
            null
        }
    }

    fun isAuthenticated(project: Project?, email: String): Boolean = getAuthToken(project, email) != null

    fun getCurrentAuthToken(project: Project?): String? {
        return if (project != null) {
            val settings = SnipHiveSettings.getInstance(project)
            val email = settings.getUserEmail()
            if (email.isNotEmpty()) getAuthToken(project, email) else null
        } else null
    }

    fun isCurrentAuthenticated(project: Project): Boolean {
        val settings = SnipHiveSettings.getInstance(project)
        val email = settings.getUserEmail()
        return email.isNotEmpty() && isAuthenticated(project, email)
    }

    fun logout(project: Project?, apiUrl: String, email: String, notifyApi: Boolean = true): Boolean {
        return try {
            val lowercaseEmail = email.lowercase()
            LOG.debug("Attempting logout for user: $lowercaseEmail")

            val secureStorage = SecureCredentialStorage.getInstance()

            // API doesn't have logout endpoint - just remove token locally
            val tokenRemoved = secureStorage.removeAuthToken(project, email)

            // Clear in-memory caches to prevent memory accumulation
            SecureCredentialStorage.clearCredentialCaches()
            projectSettingsCache.clear()

            if (project != null) {
                val settings = SnipHiveSettings.getInstance(project)
                settings.setUserEmail("")
                settings.setUserName("")
                settings.setWorkspaceId("")
            }

            LOG.info("Logout successful for user: $lowercaseEmail")
            true
        } catch (e: Exception) {
            LOG.error("Failed to logout user ${email.lowercase()}", e)
            false
        }
    }

    fun refreshToken(project: Project?, apiUrl: String, email: String): Boolean {
        // API doesn't support token refresh - Sanctum tokens don't expire
        LOG.debug("Token refresh not supported - Sanctum tokens don't expire")
        return true
    }

    fun verifyToken(project: Project?, apiUrl: String, email: String): Boolean {
        val token = getAuthToken(project, email) ?: return false
        return try {
            val apiClient = SnipHiveApiClient.getInstance()
            val response = apiClient.get<Any>(
                apiUrl = apiUrl,
                endpoint = "/api/v1/security/status",
                token = token,
                responseType = Any::class.java
            )
            if (!response.success) {
                LOG.warn("Token validation failed for $email: HTTP ${response.statusCode} – ${response.error}")
            }
            response.success
        } catch (e: Exception) {
            LOG.warn("Token validation failed for $email: ${e.message}")
            false
        }
    }

    fun clearAllCredentials(project: Project?, email: String): Boolean {
        return try {
            LOG.info("Clearing all credentials for user: ${email.lowercase()}")
            val secureStorage = SecureCredentialStorage.getInstance()
            secureStorage.removeAllCredentialsForUser(project, email)
        } catch (e: Exception) {
            LOG.error("Failed to clear credentials for user ${email.lowercase()}", e)
            false
        }
    }

    override fun dispose() {
        LOG.info("Disposing SnipHiveAuthService")
        projectSettingsCache.clear()
    }
}
