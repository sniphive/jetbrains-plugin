package com.sniphive.idea.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.E2EEProfile
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Tag
import com.sniphive.idea.models.Workspace
import com.google.gson.annotations.SerializedName

/**
 * Service for interacting with SnipHive API endpoints.
 *
 * API Endpoints (all under /api/v1/):
 * - GET  /snippets - List snippets
 * - POST /snippets - Create snippet
 * - GET  /snippets/{id} - Get snippet
 * - PUT  /snippets/{id} - Update snippet
 * - DELETE /snippets/{id} - Delete snippet
 * - GET  /tags - List tags
 * - POST /tags - Create tag
 * - GET  /security/status - E2EE status
 * - POST /security/setup - E2EE setup
 * - POST /security/recover - E2EE recovery
 */
@Service(Service.Level.APP)
class SnipHiveApiService {

    companion object {
        private val LOG = Logger.getInstance(SnipHiveApiService::class.java)
        private const val API_URL = "https://api.sniphive.net"

        // Endpoints
        private const val SNIPPETS_ENDPOINT = "/api/v1/snippets"
        private const val NOTES_ENDPOINT = "/api/v1/notes"
        private const val TAGS_ENDPOINT = "/api/v1/tags"
        private const val GISTS_ENDPOINT = "/api/v1/gists"
        private const val SECURITY_STATUS_ENDPOINT = "/api/v1/security/status"
        private const val SECURITY_SETUP_ENDPOINT = "/api/v1/security/setup"
        private const val SECURITY_RECOVER_ENDPOINT = "/api/v1/security/recover"
        private const val WORKSPACES_ENDPOINT = "/api/v1/workspaces"

        @JvmStatic
        fun getInstance(): SnipHiveApiService = service()
    }

    /**
     * Get the auth token for the current user.
     */
    private fun getToken(project: Project?): String? {
        if (project == null) return null
        val settings = SnipHiveSettings.getInstance()
        val email = settings.getUserEmail()
        if (email.isEmpty()) return null
        return SecureCredentialStorage.getInstance().getAuthToken(project, email)
    }

    /**
     * Get the workspace ID for the current user.
     */
    private fun getWorkspaceId(project: Project?): String? {
        if (project == null) return null
        val settings = SnipHiveSettings.getInstance()
        return settings.getWorkspaceId().ifEmpty { null }
    }

    /**
     * Get all snippets for the user's workspace.
     */
    fun getSnippets(project: Project): List<Snippet> {
        val token = getToken(project) ?: return emptyList()
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        return apiClient.getPaginated(
            apiUrl = API_URL,
            endpoint = SNIPPETS_ENDPOINT,
            token = token,
            itemClass = Snippet::class.java,
            workspaceId = workspaceId
        )
    }

    /**
     * Get a single snippet by slug or ID.
     * Uses slug for API calls (route key binding).
     */
    fun getSnippet(project: Project, snippetSlug: String): Snippet? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.get<Snippet>(
            apiUrl = API_URL,
            endpoint = "$SNIPPETS_ENDPOINT/$snippetSlug",
            token = token,
            responseType = Snippet::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            response.data
        } else {
            LOG.warn("Failed to get snippet $snippetSlug: ${response.error}")
            null
        }
    }

    /**
     * Create a new snippet.
     */
    fun createSnippet(
        project: Project,
        title: String,
        content: String,
        language: String,
        tags: List<String> = emptyList(),
        encryptedDek: String? = null,
        isPublic: Boolean = false
    ): Snippet? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mutableMapOf<String, Any?>(
            "title" to title,
            "content" to content,
            "language" to language,
            "tags" to tags,
            "is_public" to isPublic
        )
        if (!workspaceId.isNullOrEmpty()) {
            body["workspace_id"] = workspaceId
        }
        encryptedDek?.let { body["encrypted_dek"] = it }

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Snippet>(
            apiUrl = API_URL,
            endpoint = SNIPPETS_ENDPOINT,
            token = token,
            body = body,
            responseType = Snippet::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Created snippet: $title")
            response.data
        } else {
            LOG.warn("Failed to create snippet: ${response.error}")
            null
        }
    }

    /**
     * Get all tags for the user's workspace.
     */
    fun getTags(project: Project): List<Tag> {
        val token = getToken(project) ?: return emptyList()
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        return apiClient.getPaginated(
            apiUrl = API_URL,
            endpoint = TAGS_ENDPOINT,
            token = token,
            itemClass = Tag::class.java,
            workspaceId = workspaceId
        )
    }

    /**
     * Get E2EE security status.
     */
    fun getSecurityStatus(project: Project): SecurityStatus? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.get<SecurityStatus>(
            apiUrl = API_URL,
            endpoint = SECURITY_STATUS_ENDPOINT,
            token = token,
            responseType = SecurityStatus::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            response.data
        } else {
            LOG.warn("Failed to get security status: ${response.error}")
            null
        }
    }

    /**
     * Setup E2EE with public key.
     */
    fun setupE2EE(
        project: Project,
        publicKeyJwk: String,
        encryptedPrivateKey: String,
        recoveryEncryptedPrivateKey: String,
        privateKeyIv: String,
        recoveryIv: String,
        kdfSalt: String,
        recoverySalt: String,
        kdfIterations: Int
    ): Boolean {
        val token = getToken(project) ?: return false

        val body = mapOf(
            "public_key_jwk" to publicKeyJwk,
            "encrypted_private_key" to encryptedPrivateKey,
            "recovery_encrypted_private_key" to recoveryEncryptedPrivateKey,
            "private_key_iv" to privateKeyIv,
            "recovery_iv" to recoveryIv,
            "kdf_salt" to kdfSalt,
            "recovery_salt" to recoverySalt,
            "kdf_iterations" to kdfIterations
        )

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Any>(
            apiUrl = API_URL,
            endpoint = SECURITY_SETUP_ENDPOINT,
            token = token,
            body = body,
            responseType = Any::class.java
        )

        return if (response.success) {
            LOG.info("E2EE setup successful")
            true
        } else {
            LOG.warn("Failed to setup E2EE: ${response.error}")
            false
        }
    }

    /**
     * Update an existing snippet.
     * Uses slug for API calls (route key binding).
     */
    fun updateSnippet(
        project: Project,
        snippetSlug: String,
        title: String? = null,
        content: String? = null,
        language: String? = null,
        tags: List<String>? = null,
        archivedAt: String? = null,
        encryptedDek: String? = null,
        isPublic: Boolean? = null
    ): Snippet? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mutableMapOf<String, Any?>()
        title?.let { body["title"] = it }
        content?.let { body["content"] = it }
        language?.let { body["language"] = it }
        tags?.let { body["tags"] = it }
        encryptedDek?.let { body["encrypted_dek"] = it }
        body["archived_at"] = archivedAt
        isPublic?.let { body["is_public"] = it }

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.put<Snippet>(
            apiUrl = API_URL,
            endpoint = "$SNIPPETS_ENDPOINT/$snippetSlug",
            token = token,
            body = body,
            responseType = Snippet::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Updated snippet: $snippetSlug")
            response.data
        } else {
            LOG.warn("Failed to update snippet: ${response.error}")
            null
        }
    }

    /**
     * Archive a snippet (soft delete).
     */
    fun archiveSnippet(project: Project, snippetSlug: String): Snippet? {
        val snippet = getSnippet(project, snippetSlug) ?: return null
        val timestamp = java.time.Instant.now().toString()
        return updateSnippet(
            project = project,
            snippetSlug = snippetSlug,
            title = snippet.title,
            content = snippet.content,
            language = snippet.language,
            tags = snippet.tags?.map { it.id },
            archivedAt = timestamp,
            encryptedDek = snippet.encryptedDek,
            isPublic = snippet.isPublic
        )
    }

    /**
     * Unarchive a snippet (restore from archive).
     */
    fun unarchiveSnippet(project: Project, snippetSlug: String): Snippet? {
        val snippet = getSnippet(project, snippetSlug) ?: return null
        return updateSnippet(
            project = project,
            snippetSlug = snippetSlug,
            title = snippet.title,
            content = snippet.content,
            language = snippet.language,
            tags = snippet.tags?.map { it.id },
            archivedAt = null,
            encryptedDek = snippet.encryptedDek,
            isPublic = snippet.isPublic
        )
    }

    /**
     * Delete a snippet.
     * Uses slug for API calls (route key binding).
     */
    fun deleteSnippet(project: Project, snippetSlug: String): Boolean {
        val token = getToken(project) ?: return false
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.delete(
            apiUrl = API_URL,
            endpoint = "$SNIPPETS_ENDPOINT/$snippetSlug",
            token = token,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Deleted snippet: $snippetSlug")
            true
        } else {
            LOG.warn("Failed to delete snippet: ${response.error}")
            false
        }
    }

    /**
     * Toggle pin status for a snippet.
     * Uses slug for API calls (route key binding).
     */
    fun togglePin(project: Project, snippetSlug: String): Snippet? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Snippet>(
            apiUrl = API_URL,
            endpoint = "$SNIPPETS_ENDPOINT/$snippetSlug/pin",
            token = token,
            body = null,
            responseType = Snippet::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Toggled pin for snippet: $snippetSlug")
            response.data
        } else {
            LOG.warn("Failed to toggle pin: ${response.error}")
            null
        }
    }

    /**
     * Toggle favorite status for a snippet.
     * Uses slug for API calls (route key binding).
     */
    fun toggleFavorite(project: Project, snippetSlug: String): Snippet? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Snippet>(
            apiUrl = API_URL,
            endpoint = "$SNIPPETS_ENDPOINT/$snippetSlug/favorite",
            token = token,
            body = null,
            responseType = Snippet::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Toggled favorite for snippet: $snippetSlug")
            response.data
        } else {
            LOG.warn("Failed to toggle favorite: ${response.error}")
            null
        }
    }

    // ==========================================
    // NOTES API
    // ==========================================

    /**
     * Get all notes for the user's workspace.
     */
    fun getNotes(project: Project): List<Note> {
        val token = getToken(project) ?: return emptyList()
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        return apiClient.getPaginated(
            apiUrl = API_URL,
            endpoint = NOTES_ENDPOINT,
            token = token,
            itemClass = Note::class.java,
            workspaceId = workspaceId
        )
    }

    /**
     * Get a single note by slug or ID.
     * Uses slug for API calls (route key binding).
     */
    fun getNote(project: Project, noteSlug: String): Note? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.get<Note>(
            apiUrl = API_URL,
            endpoint = "$NOTES_ENDPOINT/$noteSlug",
            token = token,
            responseType = Note::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            response.data
        } else {
            LOG.warn("Failed to get note $noteSlug: ${response.error}")
            null
        }
    }

    /**
     * Create a new note.
     */
    fun createNote(
        project: Project,
        title: String,
        content: String,
        tags: List<String> = emptyList(),
        encryptedDek: String? = null
    ): Note? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mutableMapOf<String, Any?>(
            "title" to title,
            "content" to content,
            "tags" to tags
        )
        if (!workspaceId.isNullOrEmpty()) {
            body["workspace_id"] = workspaceId
        }
        encryptedDek?.let { body["encrypted_dek"] = it }

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Note>(
            apiUrl = API_URL,
            endpoint = NOTES_ENDPOINT,
            token = token,
            body = body,
            responseType = Note::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Created note: $title")
            response.data
        } else {
            LOG.warn("Failed to create note: ${response.error}")
            null
        }
    }

    /**
     * Update an existing note.
     * Uses slug for API calls (route key binding).
     */
    fun updateNote(
        project: Project,
        noteSlug: String,
        title: String? = null,
        content: String? = null,
        tags: List<String>? = null,
        archivedAt: String? = null,
        encryptedDek: String? = null,
        isPublic: Boolean? = null
    ): Note? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mutableMapOf<String, Any?>()
        title?.let { body["title"] = it }
        content?.let { body["content"] = it }
        tags?.let { body["tags"] = it }
        encryptedDek?.let { body["encrypted_dek"] = it }
        body["archived_at"] = archivedAt
        isPublic?.let { body["is_public"] = it }

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.put<Note>(
            apiUrl = API_URL,
            endpoint = "$NOTES_ENDPOINT/$noteSlug",
            token = token,
            body = body,
            responseType = Note::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Updated note: $noteSlug")
            response.data
        } else {
            LOG.warn("Failed to update note: ${response.error}")
            null
        }
    }

    /**
     * Archive a note (soft delete).
     */
    fun archiveNote(project: Project, noteSlug: String): Note? {
        val note = getNote(project, noteSlug) ?: return null
        val timestamp = java.time.Instant.now().toString()
        return updateNote(
            project = project,
            noteSlug = noteSlug,
            title = note.title,
            content = note.content,
            tags = note.tags?.map { it.id },
            archivedAt = timestamp,
            encryptedDek = note.encryptedDek,
            isPublic = note.isPublic
        )
    }

    /**
     * Unarchive a note (restore from archive).
     * Uses slug for API calls (route key binding).
     */
    fun unarchiveNote(project: Project, noteSlug: String): Note? {
        val note = getNote(project, noteSlug) ?: return null
        return updateNote(
            project = project,
            noteSlug = noteSlug,
            title = note.title,
            content = note.content,
            tags = note.tags?.map { it.id },
            archivedAt = null,
            encryptedDek = note.encryptedDek,
            isPublic = note.isPublic
        )
    }

    /**
     * Delete a note.
     * Uses slug for API calls (route key binding).
     */
    fun deleteNote(project: Project, noteSlug: String): Boolean {
        val token = getToken(project) ?: return false
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.delete(
            apiUrl = API_URL,
            endpoint = "$NOTES_ENDPOINT/$noteSlug",
            token = token,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Deleted note: $noteSlug")
            true
        } else {
            LOG.warn("Failed to delete note: ${response.error}")
            false
        }
    }

    /**
     * Toggle pin status for a note.
     * Uses slug for API calls (route key binding).
     */
    fun toggleNotePin(project: Project, noteSlug: String): Note? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Note>(
            apiUrl = API_URL,
            endpoint = "$NOTES_ENDPOINT/$noteSlug/pin",
            token = token,
            body = null,
            responseType = Note::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Toggled pin for note: $noteSlug")
            response.data
        } else {
            LOG.warn("Failed to toggle pin for note: ${response.error}")
            null
        }
    }

    /**
     * Toggle favorite status for a note.
     * Uses slug for API calls (route key binding).
     */
    fun toggleNoteFavorite(project: Project, noteSlug: String): Note? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Note>(
            apiUrl = API_URL,
            endpoint = "$NOTES_ENDPOINT/$noteSlug/favorite",
            token = token,
            body = null,
            responseType = Note::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Toggled favorite for note: $noteSlug")
            response.data
        } else {
            LOG.warn("Failed to toggle favorite for note: ${response.error}")
            null
        }
    }

    // ==========================================
    // WORKSPACES API
    // ==========================================

    /**
     * Get all workspaces for the current user.
     * Note: Workspaces endpoint doesn't require workspaceId header.
     */
    fun getWorkspaces(project: Project): List<Workspace> {
        val token = getToken(project) ?: return emptyList()

        val apiClient = SnipHiveApiClient.getInstance()
        return apiClient.getPaginated(
            apiUrl = API_URL,
            endpoint = WORKSPACES_ENDPOINT,
            token = token,
            itemClass = Workspace::class.java,
            workspaceId = null
        )
    }

    /**
     * Get a single workspace by UUID.
     */
    fun getWorkspace(project: Project, workspaceUuid: String): Workspace? {
        val token = getToken(project) ?: return null

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.get<Workspace>(
            apiUrl = API_URL,
            endpoint = "$WORKSPACES_ENDPOINT/$workspaceUuid",
            token = token,
            responseType = Workspace::class.java,
            workspaceId = null
        )

        return if (response.success) {
            response.data
        } else {
            LOG.warn("Failed to get workspace $workspaceUuid: ${response.error}")
            null
        }
    }

    // ==========================================
    // GIST IMPORT API
    // ==========================================

    /**
     * Get list of imported gists.
     */
    fun getGistImports(project: Project): List<GistImport> {
        val token = getToken(project) ?: return emptyList()
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        return apiClient.getPaginated(
            apiUrl = API_URL,
            endpoint = GISTS_ENDPOINT,
            token = token,
            itemClass = GistImport::class.java,
            workspaceId = workspaceId
        )
    }

    /**
     * Import a GitHub Gist.
     */
    fun importGist(project: Project, gistUrl: String, encrypt: Boolean = false): GistImport? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mapOf(
            "gist_url" to gistUrl,
            "encrypt" to encrypt
        )

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<GistImport>(
            apiUrl = API_URL,
            endpoint = "$GISTS_ENDPOINT/import",
            token = token,
            body = body,
            responseType = GistImport::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Imported gist: $gistUrl")
            response.data
        } else {
            LOG.warn("Failed to import gist: ${response.error}")
            null
        }
    }

    // ==========================================
    // TAGS API
    // ==========================================

    /**
     * Create a new tag.
     */
    fun createTag(
        project: Project,
        name: String,
        color: String
    ): Tag? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mapOf(
            "name" to name,
            "color" to color
        )

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.post<Tag>(
            apiUrl = API_URL,
            endpoint = TAGS_ENDPOINT,
            token = token,
            body = body,
            responseType = Tag::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Created tag: $name")
            response.data
        } else {
            LOG.warn("Failed to create tag: ${response.error}")
            null
        }
    }

    /**
     * Update an existing tag.
     */
    fun updateTag(
        project: Project,
        tagId: String,
        name: String? = null,
        color: String? = null
    ): Tag? {
        val token = getToken(project) ?: return null
        val workspaceId = getWorkspaceId(project)

        val body = mutableMapOf<String, Any>()
        name?.let { body["name"] = it }
        color?.let { body["color"] = it }

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.put<Tag>(
            apiUrl = API_URL,
            endpoint = "$TAGS_ENDPOINT/$tagId",
            token = token,
            body = body,
            responseType = Tag::class.java,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Updated tag: $tagId")
            response.data
        } else {
            LOG.warn("Failed to update tag: ${response.error}")
            null
        }
    }

    /**
     * Delete a tag.
     */
    fun deleteTag(project: Project, tagId: String): Boolean {
        val token = getToken(project) ?: return false
        val workspaceId = getWorkspaceId(project)

        val apiClient = SnipHiveApiClient.getInstance()
        val response = apiClient.delete(
            apiUrl = API_URL,
            endpoint = "$TAGS_ENDPOINT/$tagId",
            token = token,
            workspaceId = workspaceId
        )

        return if (response.success) {
            LOG.info("Deleted tag: $tagId")
            true
        } else {
            LOG.warn("Failed to delete tag: ${response.error}")
            false
        }
    }

    /**
     * Gist import record.
     */
    data class GistImport(
        val id: String,
        val gistId: String,
        val gistUrl: String,
        val status: String,
        val importedSnippets: List<String> = emptyList(),
        val createdAt: String? = null
    )

    /**
     * Security status response.
     */
    data class SecurityStatus(
        @SerializedName("setup_complete")
        val setupComplete: Boolean,
        
        @SerializedName("e2ee_profile")
        val e2eeProfile: E2EEProfile? = null
    )
}