package com.sniphive.idea.models

import com.google.gson.annotations.SerializedName

/**
 * Response model for login and authentication requests.
 *
 * This class represents the JSON response from the SnipHive API login endpoint.
 * It contains the authentication token, user information, and workspace data.
 *
 * @property token The authentication token (Sanctum token)
 * @property user The user information object
 * @property workspaces List of workspaces the user has access to
 */
data class LoginResponse(
    @SerializedName("token")
    val token: String,

    @SerializedName("user")
    val user: User? = null,

    @SerializedName("workspaces")
    val workspaces: List<Workspace> = emptyList()
) {
    /**
     * User information contained in the login response.
     *
     * @property name The user's display name
     * @property email The user's email address
     */
    data class User(
        @SerializedName("name")
        val name: String? = null,

        @SerializedName("email")
        val email: String
    )

    /**
     * Workspace information contained in the login response.
     *
     * @property id The unique workspace ID
     * @property uuid The workspace UUID
     * @property name The workspace name
     * @property type The workspace type (personal, team, etc.)
     */
    data class Workspace(
        @SerializedName("id")
        val id: Int,

        @SerializedName("uuid")
        val uuid: String? = null,

        @SerializedName("name")
        val name: String,

        @SerializedName("type")
        val type: String? = null
    )
}