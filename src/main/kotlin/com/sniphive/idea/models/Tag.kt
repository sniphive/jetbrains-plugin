package com.sniphive.idea.models

import com.google.gson.annotations.SerializedName
import kotlin.math.abs

/**
 * Response model for tag data.
 *
 * This class represents the JSON response from the SnipHive API for tag endpoints.
 * It contains tag metadata and usage statistics.
 *
 * @property id The unique tag ID
 * @property name The tag name
 * @property slug The URL-friendly slug for the tag
 * @property color The tag color (hex code)
 * @property snippetsCount The number of snippets using this tag
 * @property notesCount The number of notes using this tag
 * @property createdAt The timestamp when the tag was created
 * @property updatedAt The timestamp when the tag was last updated
 */
data class Tag(
    @SerializedName("id")
    val id: String,

    @SerializedName("name")
    val name: String,

    @SerializedName("slug")
    val slug: String? = null,

    @SerializedName("color")
    val color: String? = null,

    @SerializedName("snippets_count")
    val snippetsCount: Int? = null,

    @SerializedName("notes_count")
    val notesCount: Int? = null,

    @SerializedName("created_at")
    val createdAt: String? = null,

    @SerializedName("updated_at")
    val updatedAt: String? = null
) {
    /**
     * Get the total number of items (snippets + notes) using this tag.
     *
     * @return The total count of items associated with this tag
     */
    fun getTotalCount(): Int = (snippetsCount ?: 0) + (notesCount ?: 0)

    /**
     * Check if this tag has a defined color.
     *
     * @return true if the tag has a non-empty color value
     */
    fun hasColor(): Boolean = !color.isNullOrEmpty()

    /**
     * Get the tag color or a default color.
     *
     * @return The tag color hex code or "#6366f1" (indigo) as default
     */
    fun getColorOrDefault(): String = if (hasColor()) color!! else "#6366f1"

    companion object {
        /**
         * Default tag colors for auto-assignment.
         */
        val DEFAULT_COLORS = listOf(
            "#ef4444", // red
            "#f97316", // orange
            "#eab308", // yellow
            "#22c55e", // green
            "#06b6d4", // cyan
            "#3b82f6", // blue
            "#8b5cf6", // violet
            "#ec4899", // pink
            "#f43f5e", // rose
            "#6366f1"  // indigo
        )

        /**
         * Get a default color for a tag based on its name hash.
         *
         * @param tagName The tag name to hash
         * @return A color from the default palette
         */
        fun getDefaultColorForName(tagName: String): String {
            val index = abs(tagName.hashCode()) % DEFAULT_COLORS.size
            return DEFAULT_COLORS[index]
        }
    }
}
