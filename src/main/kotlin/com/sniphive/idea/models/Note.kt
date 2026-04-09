package com.sniphive.idea.models

import com.google.gson.annotations.SerializedName

/**
 * Response model for note data.
 *
 * This class represents the JSON response from the SnipHive API for note endpoints.
 * It contains all note metadata, content, tags, and user information.
 *
 * @property id The unique note ID
 * @property slug The URL-friendly slug for the note
 * @property uuid The UUID of the note
 * @property title The note title
 * @property content The note content in Markdown format (may be encrypted if E2EE is enabled)
 * @property encryptedDek The encrypted data encryption key (for E2EE)
 * @property isPublic Whether the note is publicly accessible
 * @property isPinned Whether the note is pinned for quick access
 * @property isFavorite Whether the current user has favorited this note
 * @property archivedAt The timestamp when the note was archived (null if not archived)
 * @property url The internal URL to the note
 * @property publicUrl The public URL to the note (if public)
 * @property createdAt The timestamp when the note was created
 * @property updatedAt The timestamp when the note was last updated
 * @property user The user who created the note
 * @property tags List of tags associated with the note
 */
data class Note(
    @SerializedName("id")
    val id: String,

    @SerializedName("slug")
    val slug: String? = null,

    @SerializedName("uuid")
    val uuid: String? = null,

    @SerializedName("title")
    val title: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("encrypted_dek")
    val encryptedDek: String? = null,

    @SerializedName("is_public")
    val isPublic: Boolean = false,

    @SerializedName("is_pinned")
    val isPinned: Boolean = false,

    @SerializedName("is_favorite")
    val isFavorite: Boolean = false,

    @SerializedName("archived_at")
    val archivedAt: String? = null,

    @SerializedName("url")
    val url: String? = null,

    @SerializedName("public_url")
    val publicUrl: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null,

    @SerializedName("user")
    val user: User? = null,

    @SerializedName("tags")
    val tags: List<Tag> = emptyList()
) {
    /**
     * User information for the note creator.
     *
     * @property name The user's display name
     * @property email The user's email address
     */
    data class User(
        @SerializedName("name")
        val name: String? = null,

        @SerializedName("email")
        val email: String? = null
    )

    /**
     * Check if this note is archived.
     *
     * @return true if the note has an archived_at timestamp
     */
    fun isArchived(): Boolean = archivedAt != null

    /**
     * Check if this note content is encrypted.
     *
     * @return true if the note has an encrypted data encryption key
     */
    fun isEncrypted(): Boolean = encryptedDek != null

    /**
     * Get a preview of the note content (first few lines).
     *
     * @param maxLines Maximum number of lines to include
     * @return Preview text
     */
    fun getPreview(maxLines: Int = 3): String {
        return content.lines().take(maxLines).joinToString("\n")
    }
}