package com.sniphive.idea.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Note

/**
 * Listener interface for note cache changes.
 */
interface NoteCacheListener {
    fun onNotesRefreshed(notes: List<Note>)
    fun onRefreshFailed(error: String)
    fun onRefreshStarted()
}

/**
 * Service for looking up and caching notes.
 *
 * This service provides:
 * - Note search and filtering
 * - Local caching for performance
 * - Background refresh from server
 * - Event notification for UI updates
 *
 * @property project The current project context
 */
@Service(Service.Level.PROJECT)
class NoteLookupService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(NoteLookupService::class.java)

        /**
         * Topic for note cache change notifications.
         */
        val NOTE_CACHE_TOPIC: Topic<NoteCacheListener> = Topic.create(
            "SnipHive Note Cache Changes",
            NoteCacheListener::class.java
        )

        @JvmStatic
        fun getInstance(project: Project): NoteLookupService = project.service()
    }

    // In-memory note cache
    private val noteCache = mutableListOf<Note>()
    private var lastRefreshTime: Long = 0
    private var isRefreshing = false
    private var lastError: String? = null

    /**
     * Get all cached notes.
     *
     * @return List of all cached notes
     */
    fun getAllNotes(): List<Note> {
        return noteCache.toList()
    }

    /**
     * Search notes by query string.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @return List of matching notes
     */
    fun searchNotes(query: String, maxResults: Int = 50): List<Note> {
        val lowerQuery = query.lowercase()

        return noteCache
            .filter { note ->
                note.title.lowercase().contains(lowerQuery) ||
                note.content.lowercase().contains(lowerQuery) ||
                note.tags.any { it.name.lowercase().contains(lowerQuery) }
            }
            .take(maxResults)
    }

    /**
     * Get notes by tag.
     *
     * @param tagId The tag ID to filter by
     * @return List of notes with the specified tag
     */
    fun getNotesByTag(tagId: String): List<Note> {
        return noteCache.filter { note ->
            note.tags.any { it.id == tagId }
        }
    }

    /**
     * Get pinned notes.
     *
     * @return List of pinned notes
     */
    fun getPinnedNotes(): List<Note> {
        return noteCache.filter { it.isPinned }
    }

    /**
     * Get favorite notes.
     *
     * @return List of favorite notes
     */
    fun getFavoriteNotes(): List<Note> {
        return noteCache.filter { it.isFavorite }
    }

    /**
     * Refresh notes from the server.
     *
     * This method triggers an asynchronous refresh of the note cache.
     * Listeners will be notified when the refresh completes.
     */
    fun refreshNotes() {
        if (isRefreshing) {
            LOG.debug("Refresh already in progress, skipping")
            return
        }

        LOG.info("Refreshing notes from server")
        isRefreshing = true
        lastError = null

        // Notify listeners that refresh started
        notifyRefreshStarted()

        // Perform refresh in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val notes = apiService.getNotes(project)

                // Update cache on success
                synchronized(noteCache) {
                    noteCache.clear()
                    noteCache.addAll(notes)
                    lastRefreshTime = System.currentTimeMillis()
                }

                LOG.info("Note refresh completed: ${notes.size} notes loaded")

                // Notify listeners on EDT
                ApplicationManager.getApplication().invokeLater {
                    notifyNotesRefreshed(notes)
                }
            } catch (e: Exception) {
                LOG.error("Failed to refresh notes", e)
                lastError = e.message ?: "Unknown error"

                // Notify listeners of failure on EDT
                ApplicationManager.getApplication().invokeLater {
                    notifyRefreshFailed(lastError!!)
                }
            } finally {
                isRefreshing = false
            }
        }
    }

    /**
     * Check if notes are currently being refreshed.
     *
     * @return true if refresh is in progress
     */
    fun isRefreshing(): Boolean = isRefreshing

    /**
     * Get the last refresh error, if any.
     *
     * @return The error message, or null if no error
     */
    fun getLastError(): String? = lastError

    /**
     * Get the timestamp of the last successful refresh.
     *
     * @return The timestamp in milliseconds, or 0 if never refreshed
     */
    fun getLastRefreshTime(): Long = lastRefreshTime

    /**
     * Get a note by ID.
     *
     * @param noteId The note ID
     * @return The note, or null if not found
     */
    fun getNoteById(noteId: String): Note? {
        return noteCache.find { it.id == noteId }
    }

    /**
     * Clear the note cache.
     */
    fun clearCache() {
        synchronized(noteCache) {
            noteCache.clear()
            lastRefreshTime = 0
        }
        LOG.debug("Note cache cleared")
    }

    /**
     * Add a note to the cache.
     *
     * @param note The note to add
     */
    fun addNote(note: Note) {
        synchronized(noteCache) {
            // Remove existing note with same ID if exists
            noteCache.removeAll { it.id == note.id }
            noteCache.add(note)
        }
        LOG.debug("Note added to cache: ${note.id}")
    }

    /**
     * Update a note in the cache.
     *
     * @param note The note to update
     */
    fun updateNote(note: Note) {
        synchronized(noteCache) {
            val index = noteCache.indexOfFirst { it.id == note.id }
            if (index >= 0) {
                noteCache[index] = note
                LOG.debug("Note updated in cache: ${note.id}")
            }
        }
    }

    /**
     * Remove a note from the cache.
     *
     * @param noteId The ID of the note to remove
     */
    fun removeNote(noteId: String) {
        synchronized(noteCache) {
            noteCache.removeAll { it.id == noteId }
        }
        LOG.debug("Note removed from cache: $noteId")
    }

    /**
     * Get the number of cached notes.
     *
     * @return The count of cached notes
     */
    fun getCacheSize(): Int = noteCache.size

    // Notification methods

    private fun notifyRefreshStarted() {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(NOTE_CACHE_TOPIC).onRefreshStarted()
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh start", e)
        }
    }

    private fun notifyNotesRefreshed(notes: List<Note>) {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(NOTE_CACHE_TOPIC).onNotesRefreshed(notes)
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh completion", e)
        }
    }

    private fun notifyRefreshFailed(error: String) {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(NOTE_CACHE_TOPIC).onRefreshFailed(error)
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh failure", e)
        }
    }
}