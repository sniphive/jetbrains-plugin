package com.sniphive.idea.models

import com.google.gson.annotations.SerializedName

/**
 * Response model for snippet data.
 *
 * This class represents the JSON response from the SnipHive API for snippet endpoints.
 * It contains all snippet metadata, content, tags, and user information.
 *
 * @property id The unique snippet ID
 * @property uuid The UUID of the snippet
 * @property slug The URL-friendly slug for the snippet
 * @property title The snippet title
 * @property content The snippet content (may be encrypted if E2EE is enabled)
 * @property language The programming language of the snippet
 * @property encryptedDek The encrypted data encryption key (for E2EE)
 * @property isPublic Whether the snippet is publicly accessible
 * @property isPinned Whether the snippet is pinned for quick access
 * @property isFavorite Whether the current user has favorited this snippet
 * @property archivedAt The timestamp when the snippet was archived (null if not archived)
 * @property url The internal URL to the snippet
 * @property publicUrl The public URL to the snippet (if public)
 * @property createdAt The timestamp when the snippet was created
 * @property updatedAt The timestamp when the snippet was last updated
 * @property user The user who created the snippet
 * @property tags List of tags associated with the snippet
 */
data class Snippet(
    @SerializedName("id")
    val id: String,

    @SerializedName("uuid")
    val uuid: String? = null,

    @SerializedName("slug")
    val slug: String? = null,

    @SerializedName("title")
    val title: String,

    @SerializedName("content")
    val content: String,

    @SerializedName("language")
    val language: String? = null,

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
     * User information for the snippet creator.
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
     * Check if this snippet is archived.
     *
     * @return true if the snippet has an archived_at timestamp
     */
    fun isArchived(): Boolean = archivedAt != null

    /**
     * Check if this snippet content is encrypted.
     *
     * @return true if the snippet has an encrypted data encryption key
     */
    fun isEncrypted(): Boolean = encryptedDek != null

    /**
     * Get the display language or a default value.
     *
     * @return The language name or "Plain Text" if not specified
     */
    fun getDisplayLanguage(): String = language ?: "Plain Text"
}
