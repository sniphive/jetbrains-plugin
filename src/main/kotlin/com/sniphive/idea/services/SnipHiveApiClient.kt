package com.sniphive.idea.services

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client wrapper for SnipHive REST API.
 *
 * This service provides a typed interface for making HTTP requests to the SnipHive API.
 * It handles:
 * - JSON serialization/deserialization with Gson
 * - Authentication via Bearer tokens
 * - Request/response logging
 * - Error handling and retry logic
 * - Rate limiting awareness
 *
 * Security Note:
 * - Authentication tokens are retrieved from SecureCredentialStorage
 * - Tokens are never logged or exposed
 * - All requests use HTTPS by default
 * - API URLs are validated before requests
 *
 * @see SecureCredentialStorage
 * @see SnipHiveAuthService
 */
@Service(Service.Level.APP)
class SnipHiveApiClient {

    companion object {
        private val LOG = Logger.getInstance(SnipHiveApiClient::class.java)

        // HTTP client with sensible defaults
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .addInterceptor { chain ->
                    val request = chain.request()
                    val startMs = System.currentTimeMillis()
                    LOG.debug("API Request: ${request.method} ${request.url}")

                    // NOTE: Request body is intentionally NOT logged to prevent
                    // exposure of sensitive fields (content, encrypted_dek, password, etc.)

                    val response = chain.proceed(request)
                    val durationMs = System.currentTimeMillis() - startMs

                    LOG.debug("API Response: ${response.code} ${request.method} ${request.url} (${durationMs}ms)")

                    response
                }
                .build()
        }

        // Gson instance for JSON serialization
        private val gson: Gson by lazy {
            GsonBuilder()
                .setDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .create()
        }

        // Media types
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // HTTP status codes
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_BAD_REQUEST = 400
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_UNPROCESSABLE_ENTITY = 422
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR = 500

        @JvmStatic
        fun getInstance(): SnipHiveApiClient = service()
    }

    /**
     * Wrapper for API responses containing data, error information, and metadata.
     *
     * @param T The type of data in the response
     * @property success Whether the request was successful
     * @property data The response data (if successful)
     * @property error The error message (if failed)
     * @property statusCode The HTTP status code
     * @property headers The response headers
     */
    data class ApiResponse<T>(
        val success: Boolean,
        val data: T? = null,
        val error: String? = null,
        val statusCode: Int,
        val headers: Map<String, String> = emptyMap()
    ) {
        fun isAuthError(): Boolean = statusCode == HTTP_UNAUTHORIZED || statusCode == HTTP_FORBIDDEN
        fun isValidationError(): Boolean = statusCode == HTTP_UNPROCESSABLE_ENTITY || statusCode == HTTP_BAD_REQUEST
        fun isRateLimitError(): Boolean = statusCode == HTTP_TOO_MANY_REQUESTS
        fun isServerError(): Boolean = statusCode >= HTTP_SERVER_ERROR
        fun isNotFoundError(): Boolean = statusCode == HTTP_NOT_FOUND
    }

    /**
     * Wrapper for paginated API responses.
     * The SnipHive API returns data in format: {data: [...], meta: {...}}
     */
    data class PaginatedResponse<T>(
        val data: List<T>? = null,
        val meta: PaginationMeta? = null
    )

    data class PaginationMeta(
        val current_page: Int = 1,
        val from: Int = 1,
        val last_page: Int = 1,
        val per_page: Int = 15,
        val to: Int = 0,
        val total: Int = 0
    )

    fun <T> get(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        queryParams: Map<String, String>? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T> {
        return try {
            val url = buildUrl(apiUrl, endpoint, queryParams)
            val requestBuilder = Request.Builder().url(url).get()

            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            // Add workspace ID header if provided
            if (!workspaceId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Workspace-Id", workspaceId)
            }

            requestBuilder.addHeader("Accept", "application/json")
            requestBuilder.addHeader("Content-Type", "application/json")

            val request = requestBuilder.build()
            executeRequest(request, responseType)
        } catch (e: IllegalArgumentException) {
            LOG.error("Invalid URL parameters for GET $endpoint", e)
            ApiResponse(success = false, error = "Invalid request parameters: ${e.message}", statusCode = HTTP_BAD_REQUEST)
        } catch (e: Exception) {
            LOG.error("Unexpected error during GET $endpoint", e)
            ApiResponse(success = false, error = "Request failed: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    /**
     * GET request that handles paginated responses.
     * The API returns {data: [...], meta: {...}} format.
     */
    fun <T> getPaginated(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        queryParams: Map<String, String>? = null,
        itemClass: Class<T>,
        workspaceId: String? = null
    ): List<T> {
        var response: Response? = null
        return try {

            // Add workspace_id to query params if provided
            val finalQueryParams = if (!workspaceId.isNullOrEmpty()) {
                (queryParams ?: emptyMap()) + ("workspace_id" to workspaceId)
            } else {
                queryParams
            }

            val url = buildUrl(apiUrl, endpoint, finalQueryParams)

            val requestBuilder = Request.Builder().url(url).get()

            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            requestBuilder.addHeader("Accept", "application/json")
            requestBuilder.addHeader("Content-Type", "application/json")

            val request = requestBuilder.build()
            response = client.newCall(request).execute()


            if (response.isSuccessful) {
                val responseBody = response.body?.string() ?: ""

                // Parse as PaginatedResponse
                val paginatedType = com.google.gson.reflect.TypeToken.getParameterized(
                    PaginatedResponse::class.java, itemClass
                ).type
                val paginatedResponse = gson.fromJson<PaginatedResponse<T>>(responseBody, paginatedType)

                val itemCount = paginatedResponse?.data?.size ?: 0

                paginatedResponse?.data ?: emptyList()
            } else {
                val errorBody = response.body?.string() ?: ""
                emptyList()
            }
} catch (e: Exception) {
                LOG.error("Error fetching paginated data from $endpoint", e)
                emptyList()
            } finally {
            response?.body?.close()
        }
    }

    fun <T> post(
        apiUrl: String,
        endpoint: String,
        token: String? = null,
        body: Any? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T> {
        return try {
            
            val url = buildUrl(apiUrl, endpoint, null)
            
            val requestBody = createRequestBody(body) ?: "{}".toRequestBody(JSON_MEDIA_TYPE)

            val requestBuilder = Request.Builder().url(url).post(requestBody)

            if (!token.isNullOrEmpty()) {
                requestBuilder.addHeader("Authorization", "Bearer $token")
            }

            if (!workspaceId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Workspace-Id", workspaceId)
            }

            requestBuilder.addHeader("Accept", "application/json")
            requestBuilder.addHeader("Content-Type", "application/json")

            val request = requestBuilder.build()
            
            val response = executeRequest(request, responseType)
            
            response
        } catch (e: IllegalArgumentException) {
            ApiResponse(success = false, error = "Invalid request parameters: ${e.message}", statusCode = HTTP_BAD_REQUEST)
        } catch (e: Exception) {
            ApiResponse(success = false, error = "Request failed: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    fun <T> put(
        apiUrl: String,
        endpoint: String,
        token: String,
        body: Any? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T> {
        return try {
            val url = buildUrl(apiUrl, endpoint, null)
            val requestBody = createRequestBody(body) ?: "{}".toRequestBody(JSON_MEDIA_TYPE)
            val requestBuilder = Request.Builder().url(url).put(requestBody)

            requestBuilder.addHeader("Authorization", "Bearer $token")

            if (!workspaceId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Workspace-Id", workspaceId)
            }

            requestBuilder.addHeader("Accept", "application/json")
            requestBuilder.addHeader("Content-Type", "application/json")

            val request = requestBuilder.build()
            executeRequest(request, responseType)
        } catch (e: IllegalArgumentException) {
            LOG.error("Invalid URL parameters for PUT $endpoint", e)
            ApiResponse(success = false, error = "Invalid request parameters: ${e.message}", statusCode = HTTP_BAD_REQUEST)
        } catch (e: Exception) {
            LOG.error("Unexpected error during PUT $endpoint", e)
            ApiResponse(success = false, error = "Request failed: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    fun <T> patch(
        apiUrl: String,
        endpoint: String,
        token: String,
        body: Any? = null,
        responseType: Class<T>,
        workspaceId: String? = null
    ): ApiResponse<T> {
        return try {
            val url = buildUrl(apiUrl, endpoint, null)
            val requestBody = createRequestBody(body) ?: "{}".toRequestBody(JSON_MEDIA_TYPE)
            val requestBuilder = Request.Builder().url(url).patch(requestBody)

            requestBuilder.addHeader("Authorization", "Bearer $token")

            if (!workspaceId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Workspace-Id", workspaceId)
            }

            requestBuilder.addHeader("Accept", "application/json")
            requestBuilder.addHeader("Content-Type", "application/json")

            val request = requestBuilder.build()
            executeRequest(request, responseType)
        } catch (e: IllegalArgumentException) {
            LOG.error("Invalid URL parameters for PATCH $endpoint", e)
            ApiResponse(success = false, error = "Invalid request parameters: ${e.message}", statusCode = HTTP_BAD_REQUEST)
        } catch (e: Exception) {
            LOG.error("Unexpected error during PATCH $endpoint", e)
            ApiResponse(success = false, error = "Request failed: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    fun delete(apiUrl: String, endpoint: String, token: String, workspaceId: String? = null): ApiResponse<Unit> {
        return try {
            val url = buildUrl(apiUrl, endpoint, null)
            val requestBuilder = Request.Builder().url(url).delete()

            requestBuilder.addHeader("Authorization", "Bearer $token")

            if (!workspaceId.isNullOrEmpty()) {
                requestBuilder.addHeader("X-Workspace-Id", workspaceId)
            }

            requestBuilder.addHeader("Accept", "application/json")

            val request = requestBuilder.build()
            executeRequest(request, Unit::class.java)
        } catch (e: IllegalArgumentException) {
            LOG.error("Invalid URL parameters for DELETE $endpoint", e)
            ApiResponse(success = false, error = "Invalid request parameters: ${e.message}", statusCode = HTTP_BAD_REQUEST)
        } catch (e: Exception) {
            LOG.error("Unexpected error during DELETE $endpoint", e)
            ApiResponse(success = false, error = "Request failed: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    private fun buildUrl(apiUrl: String, endpoint: String, queryParams: Map<String, String>?): String {
        
        val baseUrl = apiUrl.trimEnd('/')
        val path = endpoint.trimStart('/')
        
        
        val urlBuilder = HttpUrl.Builder()
            .scheme("https")
            .host(apiUrl.removePrefix("https://").removePrefix("http://").removeSuffix("/"))
            .addPathSegments(path)

        queryParams?.forEach { (key, value) ->
            urlBuilder.addQueryParameter(key, value)
        }

        val result = urlBuilder.build().toString()
        return result
    }

    private fun createRequestBody(body: Any?): RequestBody? {
        return if (body == null) {
            null
        } else {
            val json = gson.toJson(body)
            json.toRequestBody(JSON_MEDIA_TYPE)
        }
    }

    /**
     * Unwrap Laravel's {"data": {...}} JSON resource wrapper.
     *
     * Laravel's JsonResource wraps single-object responses in a top-level "data" key.
     * Since AppServiceProvider does not call JsonResource::withoutWrapping(), the API
     * returns {"data": {"id": "...", "title": "..."}} instead of {"id": "...", "title": "..."}.
     *
     * This method extracts the inner object so Gson can deserialize directly into the
     * target model class. Paginated responses (where "data" is an array) are left untouched
     * since they are handled separately by [getPaginated].
     *
     * @param responseBody The raw JSON response body
     * @return The unwrapped JSON string, or the original if no wrapping is detected
     */
    private fun unwrapLaravelData(responseBody: String): String {
        return try {
            val jsonElement: JsonElement = gson.fromJson(responseBody, JsonElement::class.java)

            if (jsonElement.isJsonObject) {
                val jsonObj: JsonObject = jsonElement.asJsonObject

                // Only unwrap if "data" key exists and is a JsonObject (not an array).
                // Paginated responses have "data" as JsonArray — those are handled by getPaginated().
                if (jsonObj.has("data") && jsonObj.get("data").isJsonObject) {
                    LOG.debug("Unwrapped Laravel JsonResource {\"data\": {...}} wrapper")
                    return jsonObj.get("data").toString()
                }
            }

            // No wrapping detected — return as-is
            responseBody
        } catch (e: Exception) {
            // If parsing fails for any reason, return original body and let the
            // caller's Gson deserialization handle the error naturally
            LOG.debug("Could not inspect response for Laravel wrapping, using raw body", e)
            responseBody
        }
    }

    private fun <T> executeRequest(request: Request, responseType: Class<T>): ApiResponse<T> {
        return try {
            
            val response = client.newCall(request).execute()

            val statusCode = response.code
            val rawBody = response.body?.string() ?: ""
            val headers = response.headers.toMap()
            
            
            

            when {
                statusCode in HTTP_OK..HTTP_NO_CONTENT -> {
                    if (statusCode == HTTP_NO_CONTENT || rawBody.isEmpty()) {
                        ApiResponse(success = true, statusCode = statusCode, headers = headers)
                    } else {
                        try {
                            // Unwrap Laravel's {"data": {...}} wrapper before deserializing.
                            val unwrappedBody = unwrapLaravelData(rawBody)
                            
                            val data = gson.fromJson(unwrappedBody, responseType)
                            ApiResponse(success = true, data = data, statusCode = statusCode, headers = headers)
                        } catch (e: JsonSyntaxException) {
                            ApiResponse(success = false, error = "Invalid response format from server", statusCode = statusCode)
                        }
                    }
                }
                statusCode == HTTP_UNAUTHORIZED -> {
                    LOG.warn("Authentication failed for ${request.url}")
                    ApiResponse(success = false, error = "Authentication failed. Please log in again.", statusCode = statusCode)
                }
                statusCode == HTTP_UNPROCESSABLE_ENTITY -> {
                    val errorMessage = try {
                        val errorMap = gson.fromJson(rawBody, Map::class.java)
                        val message = errorMap["message"] as? String
                        val error = errorMap["error"] as? String
                        val errors = errorMap["errors"] as? Map<*, *>
                        if (errors != null && errors.isNotEmpty()) {
                            errors.values.joinToString(", ") { it.toString() }
                        } else if (error != null) {
                            error
                        } else {
                            message ?: "Validation failed"
                        }
                    } catch (e: Exception) {
                        rawBody.ifEmpty { "Validation failed" }
                    }
                    ApiResponse(success = false, error = errorMessage, statusCode = statusCode)
                }
                statusCode == HTTP_TOO_MANY_REQUESTS -> {
                    LOG.warn("Rate limited for ${request.url}")
                    ApiResponse(success = false, error = "Too many requests. Please wait before trying again.", statusCode = statusCode)
                }
                statusCode >= HTTP_SERVER_ERROR -> {
                    LOG.error("Server error for ${request.url}: $statusCode")
                    ApiResponse(success = false, error = "Server error occurred. Please try again later.", statusCode = statusCode)
                }
                else -> {
                    val errorMessage = try {
                        val errorMap = gson.fromJson(rawBody, Map::class.java)
                        val message = errorMap["message"] as? String
                        val error = errorMap["error"] as? String
                        val errors = errorMap["errors"] as? Map<*, *>
                        if (errors != null && errors.isNotEmpty()) {
                            errors.values.joinToString(", ") { it.toString() }
                        } else if (error != null) {
                            error
                        } else {
                            message ?: "Request failed"
                        }
                    } catch (e: Exception) {
                        rawBody.ifEmpty { "Request failed" }
                    }

                    LOG.warn("Client error for ${request.url}: $statusCode - $errorMessage")
                    ApiResponse(success = false, error = errorMessage, statusCode = statusCode)
                }
            }
        } catch (e: IOException) {
            LOG.error("Network error during request to ${request.url}", e)
            ApiResponse(success = false, error = "Network error: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        } catch (e: Exception) {
            LOG.error("Unexpected error during request to ${request.url}", e)
            ApiResponse(success = false, error = "Unexpected error: ${e.message}", statusCode = HTTP_SERVER_ERROR)
        }
    }

    fun getGson(): Gson = gson
    fun getOkHttpClient(): OkHttpClient = client
}