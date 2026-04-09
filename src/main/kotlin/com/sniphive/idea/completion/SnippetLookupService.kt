package com.sniphive.idea.completion

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnipHiveApiClient
import com.sniphive.idea.services.SnipHiveAuthService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service for snippet lookup and filtering for code completion.
 *
 * This service provides a centralized way to:
 * - Fetch snippets from the SnipHive API
 * - Filter snippets by various criteria (prefix, language, tags)
 * - Cache snippets for improved performance
 * - Provide search functionality for completion
 *
 * The service is designed to be used by code completion providers
 * and other components that need access to snippets.
 *
 * Caching Strategy:
 * - Snippets are cached per project for 5 minutes
 * - Cache is invalidated when user logs out or settings change
 * - Cache keys include search parameters for different queries
 *
 * Security Note:
 * - No credentials or tokens logged or exposed
 * - Authentication tokens retrieved from SecureCredentialStorage
 * - API requests use Bearer token authentication
 *
 * @see Snippet
 * @see SnippetCompletionProvider
 * @see SnipHiveApiClient
 */
@Service(Service.Level.APP)
class SnippetLookupService {

    companion object {
        private val LOG = Logger.getInstance(SnippetLookupService::class.java)

        // API endpoints
        private const val FETCH_SNIPPETS_ENDPOINT = "/snippets"

        // Cache duration in milliseconds (5 minutes)
        private val CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(5)

        // Default limit for API requests
        private const val DEFAULT_LIMIT = 100

        // Maximum limit for API requests
        private const val MAX_LIMIT = 200

        /**
         * Get the singleton instance of the snippet lookup service.
         */
        @JvmStatic
        fun getInstance(): SnippetLookupService {
            return service()
        }
    }

    /**
     * Cache entry for storing snippets with metadata.
     *
     * @property snippets The list of snippets
     * @property timestamp The time when this cache entry was created
     * @property cacheKey The unique key for this cache entry
     */
    private data class CacheEntry(
        val snippets: List<Snippet>,
        val timestamp: Long,
        val cacheKey: String
    )

    /**
     * Result of a snippet lookup operation.
     *
     * @property success Whether the lookup was successful
     * @property snippets The list of snippets found
     * @property error An error message if the lookup failed
     * @property fromCache Whether the results came from cache
     * @property totalCount The total count of snippets available (may be greater than returned list)
     */
    data class LookupResult(
        val success: Boolean,
        val snippets: List<Snippet> = emptyList(),
        val error: String? = null,
        val fromCache: Boolean = false,
        val totalCount: Int = 0
    )

    /**
     * Filter options for snippet queries.
     *
     * @property search The search query string (matches title, content)
     * @property language Filter by programming language
     * @property tagIds Filter by list of tag IDs
     * @property tags Filter by list of Tag objects
     * @property isPublic Filter by public/private status
     * @property isPinned Filter by pinned status
     * @property isFavorite Filter by favorite status
     * @property isArchived Filter by archived status
     * @property limit Maximum number of results to return
     * @property offset Number of results to skip (for pagination)
     */
    data class FilterOptions(
        val search: String? = null,
        val language: String? = null,
        val tagIds: List<String>? = null,
        val tags: List<Tag>? = null,
        val isPublic: Boolean? = null,
        val isPinned: Boolean? = null,
        val isFavorite: Boolean? = null,
        val isArchived: Boolean? = null,
        val limit: Int = DEFAULT_LIMIT,
        val offset: Int = 0
    )

    // In-memory cache for snippet lookups
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /**
     * Lookup snippets with optional filters.
     *
     * This method:
     * 1. Checks cache for existing results
     * 2. Fetches from API if cache miss or expired
     * 3. Applies filters to the results
     * 4. Returns the filtered list
     *
     * @param project The current project (for settings and auth)
     * @param options Filter options for the query
     * @return LookupResult with snippets and metadata
     */
    fun lookup(project: Project, options: FilterOptions = FilterOptions()): LookupResult {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val apiUrl = settings.getApiUrl()

            if (apiUrl.isEmpty()) {
                LOG.debug("API URL not configured")
                return LookupResult(
                    success = false,
                    error = "API URL not configured"
                )
            }

            val authService = SnipHiveAuthService.getInstance()
            if (!authService.isCurrentAuthenticated(project)) {
                LOG.debug("User not authenticated")
                return LookupResult(
                    success = false,
                    error = "User not authenticated"
                )
            }

            // Build cache key from filter options
            val cacheKey = buildCacheKey(options)

            // Check cache
            val cachedEntry = cache[cacheKey]
            if (cachedEntry != null && !isCacheExpired(cachedEntry.timestamp)) {
                LOG.debug("Returning cached snippets for key: $cacheKey")
                val filtered = filterSnippets(cachedEntry.snippets, options)
                return LookupResult(
                    success = true,
                    snippets = applyLimit(filtered, options.limit),
                    fromCache = true,
                    totalCount = filtered.size
                )
            }

            // Fetch from API
            val snippets = fetchSnippetsFromApi(project, apiUrl, options)

            if (snippets.isEmpty()) {
                LOG.debug("No snippets found for options: $options")
                return LookupResult(
                    success = true,
                    snippets = emptyList(),
                    totalCount = 0
                )
            }

            // Cache the results
            cache[cacheKey] = CacheEntry(
                snippets = snippets,
                timestamp = System.currentTimeMillis(),
                cacheKey = cacheKey
            )

            // Apply filters
            val filtered = filterSnippets(snippets, options)
            val limited = applyLimit(filtered, options.limit)

            LOG.debug("Found ${limited.size} snippets (from API)")

            LookupResult(
                success = true,
                snippets = limited,
                totalCount = filtered.size
            )
        } catch (e: Exception) {
            LOG.error("Error during snippet lookup", e)
            LookupResult(
                success = false,
                error = "Failed to lookup snippets: ${e.message}"
            )
        }
    }

    /**
     * Search snippets by prefix (for code completion).
     *
     * This is a convenience method optimized for code completion.
     * It searches for snippets whose titles start with or contain the prefix.
     *
     * @param project The current project
     * @param prefix The prefix string to match
     * @param language Optional language filter
     * @param maxResults Maximum number of results to return
     * @return LookupResult with matching snippets
     */
    fun searchByPrefix(
        project: Project,
        prefix: String,
        language: String? = null,
        maxResults: Int = DEFAULT_LIMIT
    ): LookupResult {
        return lookup(
            project = project,
            options = FilterOptions(
                search = prefix,
                language = language,
                limit = maxResults,
                isArchived = false // Exclude archived snippets by default
            )
        )
    }

    /**
     * Get a snippet by slug or ID.
     * Uses slug for API calls (route key binding).
     *
     * @param project The current project
     * @param snippetSlug The snippet slug (or ID as fallback) to fetch
     * @return The snippet if found, null otherwise
     */
    fun getById(project: Project, snippetSlug: String): Snippet? {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val apiUrl = settings.getApiUrl()

            if (apiUrl.isEmpty()) {
                LOG.debug("API URL not configured")
                return null
            }

            val authService = SnipHiveAuthService.getInstance()
            if (!authService.isCurrentAuthenticated(project)) {
                LOG.debug("User not authenticated")
                return null
            }

            val email = settings.getUserEmail()
            val token = authService.getAuthToken(project, email)

            if (token == null) {
                LOG.warn("Authentication token not found for user: $email")
                return null
            }

            val apiClient = SnipHiveApiClient.getInstance()

            // Fetch specific snippet from API
            val response = apiClient.get<Snippet>(
                apiUrl = apiUrl,
                endpoint = "$FETCH_SNIPPETS_ENDPOINT/$snippetSlug",
                token = token,
                queryParams = null,
                responseType = Snippet::class.java
            )

            if (response.success && response.data != null) {
                LOG.debug("Fetched snippet: $snippetSlug")
                response.data
            } else {
                LOG.warn("Failed to fetch snippet $snippetSlug: ${response.error}")
                null
            }
        } catch (e: Exception) {
            LOG.error("Error fetching snippet $snippetSlug", e)
            null
        }
    }

    /**
     * Fetch all snippets from API.
     *
     * @param project The current project
     * @return LookupResult with all snippets
     */
    fun fetchAll(project: Project): LookupResult {
        return lookup(
            project = project,
            options = FilterOptions(
                limit = MAX_LIMIT,
                isArchived = false
            )
        )
    }

    /**
     * Invalidate the cache for a specific project/user.
     *
     * This should be called when:
     * - User logs out
     * - User creates/updates a snippet
     * - User deletes a snippet
     * - Settings change
     *
     * @param project The project context
     */
    fun invalidateCache(project: Project) {
        val settings = SnipHiveSettings.getInstance(project)
        val email = settings.getUserEmail()

        if (email.isNotEmpty()) {
            // Invalidate all cache entries for this user
            val keysToRemove = cache.keys.filter { it.startsWith(email) }
            keysToRemove.forEach { cache.remove(it) }

            LOG.debug("Invalidated ${keysToRemove.size} cache entries for user: $email")
        }
    }

    /**
     * Invalidate all cache entries.
     *
     * This is useful for testing or force refresh scenarios.
     */
    fun clearAllCache() {
        val size = cache.size
        cache.clear()
        LOG.debug("Cleared all $size cache entries")
    }

    /**
     * Fetch snippets from the SnipHive API.
     *
     * @param project The current project
     * @param apiUrl The base API URL
     * @param options Filter options for the query
     * @return List of snippets from the API
     */
    private fun fetchSnippetsFromApi(
        project: Project,
        apiUrl: String,
        options: FilterOptions
    ): List<Snippet> {
        val authService = SnipHiveAuthService.getInstance()
        val settings = SnipHiveSettings.getInstance(project)
        val email = settings.getUserEmail()
        val token = authService.getAuthToken(project, email)

        if (token == null) {
            LOG.warn("Authentication token not found for user: $email")
            return emptyList()
        }

        val apiClient = SnipHiveApiClient.getInstance()

        // Build query parameters
        val queryParams = buildMap<String, String> {
            put("limit", minOf(options.limit, MAX_LIMIT).toString())
            put("offset", options.offset.toString())

            options.search?.let { if (it.isNotBlank()) put("search", it) }
            options.language?.let { if (it.isNotBlank()) put("language", it) }
            options.isPublic?.let { put("is_public", it.toString()) }
            options.isPinned?.let { put("is_pinned", it.toString()) }
            options.isFavorite?.let { put("is_favorite", it.toString()) }
            options.isArchived?.let { put("is_archived", it.toString()) }

            // Add tag filters
            val tagIdsToFilter = options.tagIds ?: options.tags?.map { it.id }
            tagIdsToFilter?.let {
                if (it.isNotEmpty()) {
                    put("tag_ids", it.joinToString(","))
                }
            }
        }

        // Fetch snippets from API
        val response = apiClient.get<SnippetsResponse>(
            apiUrl = apiUrl,
            endpoint = FETCH_SNIPPETS_ENDPOINT,
            token = token,
            queryParams = queryParams,
            responseType = SnippetsResponse::class.java
        )

        return if (response.success && response.data != null) {
            response.data.data
        } else {
            LOG.warn("Failed to fetch snippets: ${response.error}")
            emptyList()
        }
    }

    /**
     * Filter a list of snippets based on filter options.
     *
     * Client-side filtering for conditions not supported by the API.
     *
     * @param snippets The list of snippets to filter
     * @param options The filter options to apply
     * @return Filtered list of snippets
     */
    private fun filterSnippets(snippets: List<Snippet>, options: FilterOptions): List<Snippet> {
        return snippets.filter { snippet ->
            // Filter by archived status
            options.isArchived?.let {
                if (it) {
                    snippet.isArchived()
                } else {
                    !snippet.isArchived()
                }
            } ?: true

            // Filter by public status
            options.isPublic?.let {
                snippet.isPublic == it
            } ?: true

            // Filter by pinned status
            options.isPinned?.let {
                snippet.isPinned == it
            } ?: true

            // Filter by favorite status
            options.isFavorite?.let {
                snippet.isFavorite == it
            } ?: true

            // Filter by language
            options.language?.let {
                snippet.language.equals(it, ignoreCase = true)
            } ?: true

            // Filter by tags
            val tagIdsToFilter = options.tagIds ?: options.tags?.map { it.id }
            tagIdsToFilter?.let { requiredTagIds ->
                if (requiredTagIds.isNotEmpty()) {
                    snippet.tags.any { tag -> tag.id in requiredTagIds }
                } else {
                    true
                }
            } ?: true

            // Filter by search (case-insensitive prefix match in title)
            options.search?.let { search ->
                search.isNotBlank() && snippet.title.contains(search, ignoreCase = true)
            } ?: true
        }
    }

    /**
     * Apply limit to a list of snippets.
     *
     * @param snippets The list of snippets
     * @param limit The maximum number to return
     * @return Limited list of snippets
     */
    private fun applyLimit(snippets: List<Snippet>, limit: Int): List<Snippet> {
        return if (limit > 0 && snippets.size > limit) {
            snippets.take(limit)
        } else {
            snippets
        }
    }

    /**
     * Build a cache key from filter options.
     *
     * @param options The filter options
     * @return A unique cache key string
     */
    private fun buildCacheKey(options: FilterOptions): String {
        return buildString {
            append(options.search?.lowercase() ?: "")
            append(":")
            append(options.language?.lowercase() ?: "")
            append(":")
            append(options.tagIds?.joinToString(",") ?: "")
            append(":")
            append(options.tags?.joinToString(",") { it.id } ?: "")
            append(":")
            append(options.isPublic ?: "")
            append(":")
            append(options.isPinned ?: "")
            append(":")
            append(options.isFavorite ?: "")
            append(":")
            append(options.isArchived ?: "")
            append(":")
            append(options.limit)
            append(":")
            append(options.offset)
        }
    }

    /**
     * Check if a cache entry has expired.
     *
     * @param timestamp The cache entry timestamp
     * @return true if the cache entry is expired
     */
    private fun isCacheExpired(timestamp: Long): Boolean {
        val age = System.currentTimeMillis() - timestamp
        return age > CACHE_DURATION_MS
    }

    /**
     * Get the size of the cache.
     *
     * @return Number of cached entries
     */
    fun getCacheSize(): Int = cache.size

    /**
     * API response wrapper for snippets list.
     *
     * @property data The array of snippets
     */
    data class SnippetsResponse(
        val data: List<Snippet> = emptyList()
    )
}
