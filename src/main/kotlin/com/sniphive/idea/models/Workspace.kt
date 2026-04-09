package com.sniphive.idea.models

import com.google.gson.annotations.SerializedName

/**
 * Response model for workspace data.
 *
 * This class represents the JSON response from the SnipHive API for workspace endpoints.
 * It contains workspace metadata and the user's role in the workspace.
 *
 * @property id The unique workspace ID
 * @property uuid The UUID of the workspace
 * @property name The workspace name
 * @property type The workspace type (e.g., "personal", "team")
 * @property role The user's role in this workspace (e.g., "owner", "admin", "member")
 * @property createdAt The timestamp when the workspace was created
 * @property updatedAt The timestamp when the workspace was last updated
 */
data class Workspace(
    @SerializedName("id")
    val id: String,

    @SerializedName("uuid")
    val uuid: String? = null,

    @SerializedName("name")
    val name: String,

    @SerializedName("type")
    val type: String? = null,

    @SerializedName("role")
    val role: String? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null
) {
    /**
     * Check if this is a personal workspace.
     *
     * @return true if the workspace type is "personal"
     */
    fun isPersonal(): Boolean = type == "personal"

    /**
     * Check if this is a team workspace.
     *
     * @return true if the workspace type is "team"
     */
    fun isTeam(): Boolean = type == "team"

    /**
     * Check if the current user is the owner of this workspace.
     *
     * @return true if the user's role is "owner"
     */
    fun isOwner(): Boolean = role == "owner"

    /**
     * Check if the current user is an admin of this workspace.
     *
     * @return true if the user's role is "admin" or "owner"
     */
    fun isAdmin(): Boolean = role == "admin" || role == "owner"

    /**
     * Get the display role name.
     *
     * @return The role name capitalized or "Member" if null
     */
    fun getDisplayRole(): String = role?.replaceFirstChar { it.uppercaseChar() } ?: "Member"

    companion object {
        /**
         * Workspace type constants.
         */
        const val TYPE_PERSONAL = "personal"
        const val TYPE_TEAM = "team"

        /**
         * Role constants.
         */
        const val ROLE_OWNER = "owner"
        const val ROLE_ADMIN = "admin"
        const val ROLE_MEMBER = "member"
        const val ROLE_VIEWER = "viewer"
    }
}
