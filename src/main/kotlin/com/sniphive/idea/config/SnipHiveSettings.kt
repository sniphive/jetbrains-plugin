package com.sniphive.idea.config

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.sniphive.idea.services.SecureCredentialStorage

/**
 * Persistent settings for SnipHive plugin configuration.
 *
 * This class stores user-configurable settings that persist across IDE restarts.
 * Settings are stored in the IDE's configuration directory.
 *
 * Security Note:
 * - This class only stores non-sensitive configuration (API URL, workspace ID, preferences)
 * - Authentication tokens and private keys are stored in SecureCredentialStorage (IDE Password Safe)
 * - Do not store passwords, tokens, or encryption keys in this class
 */
@State(
    name = "SnipHiveSettings",
    storages = [Storage("SnipHiveSettings.xml")]
)
@Service(Service.Level.APP)
class SnipHiveSettings : PersistentStateComponent<SnipHiveSettings.State> {

    /**
     * Internal state class for serialization.
     *
     * This class is automatically serialized/deserialized by the IntelliJ Platform.
     * All properties must be mutable and have default values.
     */
    data class State(
        /**
         * Base URL for SnipHive API.
         * Default is the production API endpoint.
         */
        var apiUrl: String = "https://api.sniphive.net",

        /**
         * Selected workspace ID for the current project.
         * Empty string indicates no workspace selected.
         */
        var workspaceId: String = "",

        /**
         * User's email address for display purposes.
         * Actual authentication is handled by SecureCredentialStorage.
         */
        var userEmail: String = "",

        /**
         * Whether E2EE (End-to-End Encryption) is enabled for this user.
         * E2EE is always enabled (mandatory).
         */
        var e2eeEnabled: Boolean = true,

        /**
         * User's display name.
         */
        var userName: String = "",

        /**
         * Whether to show encrypted content in the tool window.
         * When false, only decrypted content is shown.
         */
        var showEncryptedContent: Boolean = true,

        /**
         * Whether to auto-refresh snippets when tool window opens.
         */
        var autoRefreshOnOpen: Boolean = true,

        /**
         * Refresh interval in minutes for auto-refresh (0 = disabled).
         */
        var autoRefreshIntervalMinutes: Int = 0,

        /**
         * Whether to enable code completion for snippets.
         */
        var enableCodeCompletion: Boolean = true,

        /**
         * Minimum prefix length before code completion suggestions appear.
         */
        var codeCompletionMinPrefixLength: Int = 3,

        /**
         * Maximum number of suggestions to show in code completion.
         */
        var codeCompletionMaxSuggestions: Int = 10,

        /**
         * Master password hint (not the actual password - stored in SecureCredentialStorage).
         */
        var masterPasswordHint: String = "",

        /**
         * E2EE iterations count (stored here for convenience, actual key in SecureCredentialStorage).
         */
        var e2eeIterations: Int = 100000,

        /**
         * Whether E2EE has been unlocked in the current session.
         * Used to track if master password has been entered.
         */
        var e2eeUnlocked: Boolean = false,

        /**
         * Timestamp of last successful E2EE unlock (milliseconds since epoch).
         */
        var lastUnlockTime: Long = 0L,

        /**
         * Whether to remember the master password for auto-unlock.
         */
        var rememberMasterPassword: Boolean = true
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }

    /**
     * Get the current API URL.
     */
    fun getApiUrl(): String = state.apiUrl

    /**
     * Set the API URL.
     */
    fun setApiUrl(url: String) {
        state.apiUrl = url
    }

    /**
     * Get the selected workspace ID.
     */
    fun getWorkspaceId(): String = state.workspaceId

    /**
     * Set the selected workspace ID.
     */
    fun setWorkspaceId(workspaceId: String) {
        state.workspaceId = workspaceId
    }

    /**
     * Get the user's email.
     */
    fun getUserEmail(): String = state.userEmail

    /**
     * Set the user's email.
     */
    fun setUserEmail(email: String) {
        state.userEmail = email
    }

    /**
     * Check if E2EE is enabled. Always returns true (E2EE is mandatory).
     */
    fun isE2eeEnabled(): Boolean = true

    /**
     * Set E2EE enabled status. No-op since E2EE is always enabled.
     */
    fun setE2eeEnabled(enabled: Boolean) {
        // E2EE is always enabled, this is a no-op
    }

    /**
     * Get the user's display name.
     */
    fun getUserName(): String = state.userName

    /**
     * Set the user's display name.
     */
    fun setUserName(name: String) {
        state.userName = name
    }

    /**
     * Check if encrypted content should be shown.
     */
    fun showEncryptedContent(): Boolean = state.showEncryptedContent

    /**
     * Set whether to show encrypted content.
     */
    fun setShowEncryptedContent(show: Boolean) {
        state.showEncryptedContent = show
    }

    /**
     * Check if auto-refresh on open is enabled.
     */
    fun isAutoRefreshOnOpen(): Boolean = state.autoRefreshOnOpen

    /**
     * Set auto-refresh on open.
     */
    fun setAutoRefreshOnOpen(enabled: Boolean) {
        state.autoRefreshOnOpen = enabled
    }

    /**
     * Get auto-refresh interval in minutes.
     */
    fun getAutoRefreshIntervalMinutes(): Int = state.autoRefreshIntervalMinutes

    /**
     * Set auto-refresh interval in minutes.
     */
    fun setAutoRefreshIntervalMinutes(minutes: Int) {
        state.autoRefreshIntervalMinutes = minutes
    }

    /**
     * Check if code completion is enabled.
     */
    fun isCodeCompletionEnabled(): Boolean = state.enableCodeCompletion

    /**
     * Set code completion enabled.
     */
    fun setCodeCompletionEnabled(enabled: Boolean) {
        state.enableCodeCompletion = enabled
    }

    /**
     * Get minimum prefix length for code completion.
     */
    fun getCodeCompletionMinPrefixLength(): Int = state.codeCompletionMinPrefixLength

    /**
     * Set minimum prefix length for code completion.
     */
    fun setCodeCompletionMinPrefixLength(length: Int) {
        state.codeCompletionMinPrefixLength = length
    }

    /**
     * Get maximum suggestions for code completion.
     */
    fun getCodeCompletionMaxSuggestions(): Int = state.codeCompletionMaxSuggestions

    /**
     * Set maximum suggestions for code completion.
     */
    fun setCodeCompletionMaxSuggestions(max: Int) {
        state.codeCompletionMaxSuggestions = max
    }

    /**
     * Get E2EE iterations.
     */
    fun getE2EEIterations(): Int = state.e2eeIterations

    /**
     * Set E2EE iterations.
     */
    fun setE2EEIterations(iterations: Int) {
        state.e2eeIterations = iterations
    }

    /**
     * Get master password from secure storage.
     * Note: This retrieves from SecureCredentialStorage, not from local state.
     */
    fun getMasterPassword(): String {
        return SecureCredentialStorage.getInstance().getPassword(null, state.userEmail) ?: ""
    }

    companion object {
        /**
         * Get the application-level settings instance.
         * Settings are now shared across all projects (APP-level service).
         *
         * @return The settings instance
         */
        @JvmStatic
        fun getInstance(): SnipHiveSettings {
            return ApplicationManager.getApplication().getService(SnipHiveSettings::class.java)
        }

        /**
         * Create a default state for testing purposes.
         */
        @JvmStatic
        fun createDefaultState(): State {
            return State()
        }
    }

    /**
     * Reset all settings to default values.
     */
    fun resetToDefaults() {
        state = State()
    }

    /**
     * Check if user is logged in (has valid email and workspace).
     */
    fun isLoggedIn(): Boolean {
        return state.userEmail.isNotEmpty() && state.workspaceId.isNotEmpty()
    }

    /**
     * Check if E2EE is unlocked in the current session.
     */
    fun isE2eeUnlocked(): Boolean = state.e2eeUnlocked

    /**
     * Set E2EE unlocked status.
     */
    fun setE2eeUnlocked(unlocked: Boolean) {
        state.e2eeUnlocked = unlocked
        if (unlocked) {
            state.lastUnlockTime = System.currentTimeMillis()
        }
    }

    /**
     * Get the last E2EE unlock timestamp.
     */
    fun getLastUnlockTime(): Long = state.lastUnlockTime

    /**
     * Check if master password should be remembered for auto-unlock.
     */
    fun isRememberMasterPassword(): Boolean = state.rememberMasterPassword

    /**
     * Set whether to remember master password.
     */
    fun setRememberMasterPassword(remember: Boolean) {
        state.rememberMasterPassword = remember
    }

    /**
     * Clear E2EE session state (on logout or lock).
     */
    fun clearE2eeSession() {
        state.e2eeUnlocked = false
        state.lastUnlockTime = 0L
    }

    /**
     * Get a summary of current settings for display purposes.
     *
     * Security: This method does not expose sensitive data.
     */
    fun getSettingsSummary(): String {
        return """
            SnipHive Settings:
            - API URL: ${state.apiUrl}
            - Workspace: ${if (state.workspaceId.isNotEmpty()) state.workspaceId else "Not selected"}
            - User: ${if (state.userEmail.isNotEmpty()) state.userEmail else "Not logged in"}
            - E2EE: ${if (state.e2eeEnabled) "Enabled" else "Disabled"}
            - Auto-refresh on open: ${if (state.autoRefreshOnOpen) "Enabled" else "Disabled"}
            - Auto-refresh interval: ${if (state.autoRefreshIntervalMinutes > 0) "${state.autoRefreshIntervalMinutes}min" else "Disabled"}
            - Code completion: ${if (state.enableCodeCompletion) "Enabled" else "Disabled"}
        """.trimIndent()
    }
}