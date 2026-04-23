package com.sniphive.idea.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Snippet

/**
 * Listener interface for snippet cache changes.
 */
interface SnippetCacheListener {
    fun onSnippetsRefreshed(snippets: List<Snippet>)
    fun onRefreshFailed(error: String)
    fun onRefreshStarted()
}

/**
 * Service for looking up and caching snippets.
 *
 * This service provides:
 * - Snippet search and filtering
 * - Local caching for performance
 * - Background refresh from server
 * - Event notification for UI updates
 *
 * @property project The current project context
 */
@Service(Service.Level.PROJECT)
class SnippetLookupService(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(SnippetLookupService::class.java)

        /**
         * Topic for snippet cache change notifications.
         */
        val SNIPPET_CACHE_TOPIC: Topic<SnippetCacheListener> = Topic.create(
            "SnipHive Snippet Cache Changes",
            SnippetCacheListener::class.java
        )

        @JvmStatic
        fun getInstance(project: Project): SnippetLookupService = project.service()
    }

    // In-memory snippet cache
    private val snippetCache = mutableListOf<Snippet>()
    private var lastRefreshTime: Long = 0
    private var isRefreshing = false
    private var lastError: String? = null

    /**
     * Get all cached snippets.
     *
     * @return List of all cached snippets
     */
    fun getAllSnippets(): List<Snippet> {
        return snippetCache.toList()
    }

    /**
     * Search snippets by query string.
     *
     * @param query The search query
     * @param maxResults Maximum number of results to return
     * @return List of matching snippets
     */
    fun searchSnippets(query: String, maxResults: Int = 50): List<Snippet> {
        val lowerQuery = query.lowercase()

        return snippetCache
            .filter { snippet ->
                snippet.title.lowercase().contains(lowerQuery) ||
                snippet.content.lowercase().contains(lowerQuery) ||
                snippet.tags.any { it.name.lowercase().contains(lowerQuery) }
            }
            .take(maxResults)
    }

    /**
     * Get snippets by language.
     *
     * @param language The programming language to filter by
     * @return List of snippets for the specified language
     */
    fun getSnippetsByLanguage(language: String): List<Snippet> {
        return snippetCache.filter { it.language.equals(language, ignoreCase = true) }
    }

    /**
     * Get snippets by tag.
     *
     * @param tagId The tag ID to filter by
     * @return List of snippets with the specified tag
     */
    fun getSnippetsByTag(tagId: String): List<Snippet> {
        return snippetCache.filter { snippet ->
            snippet.tags.any { it.id == tagId }
        }
    }

    /**
     * Refresh snippets from the server.
     *
     * This method triggers an asynchronous refresh of the snippet cache.
     * Listeners will be notified when the refresh completes.
     */
    fun refreshSnippets() {
        if (isRefreshing) {
            LOG.debug("Refresh already in progress, skipping")
            return
        }

        LOG.info("Refreshing snippets from server")
        isRefreshing = true
        lastError = null

        // Notify listeners that refresh started
        notifyRefreshStarted()

        // Perform refresh in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val snippets = apiService.getSnippets(project)

                // Update cache on success
                synchronized(snippetCache) {
                    snippetCache.clear()
                    snippetCache.addAll(snippets)
                    lastRefreshTime = System.currentTimeMillis()
                }

                LOG.info("Snippet refresh completed: ${snippets.size} snippets loaded")

                // Notify listeners on EDT
                ApplicationManager.getApplication().invokeLater {
                    notifySnippetsRefreshed(snippets)
                }
            } catch (e: Exception) {
                LOG.error("Failed to refresh snippets", e)
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
     * Check if snippets are currently being refreshed.
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
     * Check if snippets need refresh based on settings.
     *
     * @return true if refresh is needed
     */
    fun needsRefresh(): Boolean {
        val settings = SnipHiveSettings.getInstance()
        val intervalMinutes = settings.getAutoRefreshIntervalMinutes()

        if (intervalMinutes <= 0) {
            return false
        }

        val intervalMs = intervalMinutes * 60 * 1000L
        return System.currentTimeMillis() - lastRefreshTime > intervalMs
    }

    /**
     * Get a snippet by ID.
     *
     * @param snippetId The snippet ID
     * @return The snippet, or null if not found
     */
    fun getSnippetById(snippetId: String): Snippet? {
        return snippetCache.find { it.id == snippetId }
    }

    /**
     * Clear the snippet cache.
     */
    fun clearCache() {
        synchronized(snippetCache) {
            snippetCache.clear()
            lastRefreshTime = 0
        }
        LOG.debug("Snippet cache cleared")
    }

    /**
     * Add a snippet to the cache.
     *
     * @param snippet The snippet to add
     */
    fun addSnippet(snippet: Snippet) {
        synchronized(snippetCache) {
            // Remove existing snippet with same ID if exists
            snippetCache.removeAll { it.id == snippet.id }
            snippetCache.add(snippet)
        }
        LOG.debug("Snippet added to cache: ${snippet.id}")
    }

    /**
     * Update a snippet in the cache.
     *
     * @param snippet The snippet to update
     */
    fun updateSnippet(snippet: Snippet) {
        synchronized(snippetCache) {
            val index = snippetCache.indexOfFirst { it.id == snippet.id }
            if (index >= 0) {
                snippetCache[index] = snippet
                LOG.debug("Snippet updated in cache: ${snippet.id}")
            }
        }
    }

    /**
     * Remove a snippet from the cache.
     *
     * @param snippetId The ID of the snippet to remove
     */
    fun removeSnippet(snippetId: String) {
        synchronized(snippetCache) {
            snippetCache.removeAll { it.id == snippetId }
        }
        LOG.debug("Snippet removed from cache: $snippetId")
    }

    /**
     * Get the number of cached snippets.
     *
     * @return The count of cached snippets
     */
    fun getCacheSize(): Int = snippetCache.size

    // Notification methods

    private fun notifyRefreshStarted() {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(SNIPPET_CACHE_TOPIC).onRefreshStarted()
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh start", e)
        }
    }

    private fun notifySnippetsRefreshed(snippets: List<Snippet>) {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(SNIPPET_CACHE_TOPIC).onSnippetsRefreshed(snippets)
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh completion", e)
        }
    }

    private fun notifyRefreshFailed(error: String) {
        try {
            val bus = ApplicationManager.getApplication().messageBus
            bus.syncPublisher(SNIPPET_CACHE_TOPIC).onRefreshFailed(error)
        } catch (e: Exception) {
            LOG.warn("Error notifying listeners of refresh failure", e)
        }
    }
}