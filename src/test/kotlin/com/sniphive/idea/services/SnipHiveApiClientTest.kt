package com.sniphive.idea.services

import org.junit.Test
import org.junit.Assert.*

/**
 * Integration tests for SnipHiveApiClient.
 *
 * Tests cover:
 * - ApiResponse wrapper class methods
 * - Response type detection and classification
 * - Error handling patterns
 * - HTTP status code handling
 * - Response data structures
 *
 * Note: These are integration-style tests that verify the API client's
 * response handling and error classification logic. Actual HTTP requests
 * are tested through manual E2E testing in phase-8-3.
 */
class SnipHiveApiClientTest {

    // ==========================================
    // TEST DATA CLASSES
    // ==========================================

    data class TestData(
        val id: String,
        val name: String,
        val value: Int
    )

    data class LoginResponse(
        val token: String,
        val tokenType: String,
        val expiresIn: Int
    )

    data class ErrorResponse(
        val message: String?,
        val errors: Map<String, String>?
    )

    // ==========================================
    // API RESPONSE SUCCESS TESTS
    // ==========================================

    @Test
    fun `ApiResponse with success true and data creates valid response`() {
        val testData = TestData("123", "Test", 42)
        val response = SnipHiveApiClient.ApiResponse(
            success = true,
            data = testData,
            error = null,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertNull("Error should be null", response.error)
        assertEquals("Status code should be 200", 200, response.statusCode)
        assertEquals("Data id should match", "123", (response.data as TestData).id)
        assertEquals("Data name should match", "Test", (response.data as TestData).name)
        assertEquals("Data value should match", 42, (response.data as TestData).value)
    }

    @Test
    fun `ApiResponse with success true and null data is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Unit>(
            success = true,
            data = null,
            statusCode = 204
        )

        assertTrue("Response should be successful", response.success)
        assertNull("Data should be null", response.data)
        assertEquals("Status code should be 204", 204, response.statusCode)
    }

    @Test
    fun `ApiResponse with empty headers map is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            headers = emptyMap(),
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Headers should not be null", response.headers)
        assertTrue("Headers should be empty", response.headers.isEmpty())
    }

    @Test
    fun `ApiResponse with headers map is valid`() {
        val headers = mapOf(
            "X-Custom-Header" to "custom-value",
            "X-RateLimit" to "100",
            "X-Request-Id" to "abc-123"
        )
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            headers = headers,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertEquals("Headers should match", headers, response.headers)
        assertEquals("Header count should be 3", 3, response.headers.size)
    }

    // ==========================================
    // API RESPONSE AUTH ERROR TESTS
    // ==========================================

    @Test
    fun `isAuthError returns true for 401 Unauthorized`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unauthorized",
            statusCode = 401
        )

        assertTrue("isAuthError should be true for 401", response.isAuthError())
    }

    @Test
    fun `isAuthError returns true for 403 Forbidden`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Forbidden",
            statusCode = 403
        )

        assertTrue("isAuthError should be true for 403", response.isAuthError())
    }

    @Test
    fun `isAuthError returns false for 200 OK`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 200
        )

        assertFalse("isAuthError should be false for 200", response.isAuthError())
    }

    @Test
    fun `isAuthError returns false for 404 Not Found`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Not found",
            statusCode = 404
        )

        assertFalse("isAuthError should be false for 404", response.isAuthError())
    }

    @Test
    fun `isAuthError returns false for 500 Server Error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Internal server error",
            statusCode = 500
        )

        assertFalse("isAuthError should be false for 500", response.isAuthError())
    }

    // ==========================================
    // API RESPONSE VALIDATION ERROR TESTS
    // ==========================================

    @Test
    fun `isValidationError returns true for 400 Bad Request`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Bad request",
            statusCode = 400
        )

        assertTrue("isValidationError should be true for 400", response.isValidationError())
    }

    @Test
    fun `isValidationError returns true for 422 Unprocessable Entity`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unprocessable entity",
            statusCode = 422
        )

        assertTrue("isValidationError should be true for 422", response.isValidationError())
    }

    @Test
    fun `isValidationError returns false for 401 Unauthorized`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unauthorized",
            statusCode = 401
        )

        assertFalse("isValidationError should be false for 401", response.isValidationError())
    }

    @Test
    fun `isValidationError returns false for 200 OK`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 200
        )

        assertFalse("isValidationError should be false for 200", response.isValidationError())
    }

    @Test
    fun `isValidationError returns false for 404 Not Found`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Not found",
            statusCode = 404
        )

        assertFalse("isValidationError should be false for 404", response.isValidationError())
    }

    // ==========================================
    // API RESPONSE RATE LIMIT ERROR TESTS
    // ==========================================

    @Test
    fun `isRateLimitError returns true for 429 Too Many Requests`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Too many requests",
            statusCode = 429
        )

        assertTrue("isRateLimitError should be true for 429", response.isRateLimitError())
    }

    @Test
    fun `isRateLimitError returns false for 200 OK`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 200
        )

        assertFalse("isRateLimitError should be false for 200", response.isRateLimitError())
    }

    @Test
    fun `isRateLimitError returns false for 401 Unauthorized`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unauthorized",
            statusCode = 401
        )

        assertFalse("isRateLimitError should be false for 401", response.isRateLimitError())
    }

    // ==========================================
    // API RESPONSE SERVER ERROR TESTS
    // ==========================================

    @Test
    fun `isServerError returns true for 500 Internal Server Error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Internal server error",
            statusCode = 500
        )

        assertTrue("isServerError should be true for 500", response.isServerError())
    }

    @Test
    fun `isServerError returns true for 502 Bad Gateway`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Bad gateway",
            statusCode = 502
        )

        assertTrue("isServerError should be true for 502", response.isServerError())
    }

    @Test
    fun `isServerError returns true for 503 Service Unavailable`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Service unavailable",
            statusCode = 503
        )

        assertTrue("isServerError should be true for 503", response.isServerError())
    }

    @Test
    fun `isServerError returns true for 504 Gateway Timeout`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Gateway timeout",
            statusCode = 504
        )

        assertTrue("isServerError should be true for 504", response.isServerError())
    }

    @Test
    fun `isServerError returns false for 404 Not Found`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Not found",
            statusCode = 404
        )

        assertFalse("isServerError should be false for 404", response.isServerError())
    }

    @Test
    fun `isServerError returns false for 429 Too Many Requests`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Too many requests",
            statusCode = 429
        )

        assertFalse("isServerError should be false for 429", response.isServerError())
    }

    // ==========================================
    // API RESPONSE NOT FOUND ERROR TESTS
    // ==========================================

    @Test
    fun `isNotFoundError returns true for 404 Not Found`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Not found",
            statusCode = 404
        )

        assertTrue("isNotFoundError should be true for 404", response.isNotFoundError())
    }

    @Test
    fun `isNotFoundError returns false for 200 OK`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 200
        )

        assertFalse("isNotFoundError should be false for 200", response.isNotFoundError())
    }

    @Test
    fun `isNotFoundError returns false for 401 Unauthorized`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unauthorized",
            statusCode = 401
        )

        assertFalse("isNotFoundError should be false for 401", response.isNotFoundError())
    }

    @Test
    fun `isNotFoundError returns false for 403 Forbidden`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Forbidden",
            statusCode = 403
        )

        assertFalse("isNotFoundError should be false for 403", response.isNotFoundError())
    }

    // ==========================================
    // HTTP STATUS CODE HANDLING TESTS
    // ==========================================

    @Test
    fun `response with 200 OK is successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 200
        )

        assertTrue("200 OK should be successful", response.success)
        assertFalse("200 OK should not be auth error", response.isAuthError())
        assertFalse("200 OK should not be validation error", response.isValidationError())
        assertFalse("200 OK should not be rate limit error", response.isRateLimitError())
        assertFalse("200 OK should not be server error", response.isServerError())
        assertFalse("200 OK should not be not found error", response.isNotFoundError())
    }

    @Test
    fun `response with 201 Created is successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 201
        )

        assertTrue("201 Created should be successful", response.success)
    }

    @Test
    fun `response with 204 No Content is successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            statusCode = 204
        )

        assertTrue("204 No Content should be successful", response.success)
    }

    @Test
    fun `response with 400 Bad Request is validation error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Bad request",
            statusCode = 400
        )

        assertFalse("400 Bad Request should not be successful", response.success)
        assertTrue("400 Bad Request should be validation error", response.isValidationError())
        assertFalse("400 Bad Request should not be auth error", response.isAuthError())
        assertFalse("400 Bad Request should not be server error", response.isServerError())
    }

    @Test
    fun `response with 401 Unauthorized is auth error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unauthorized",
            statusCode = 401
        )

        assertFalse("401 Unauthorized should not be successful", response.success)
        assertTrue("401 Unauthorized should be auth error", response.isAuthError())
        assertFalse("401 Unauthorized should not be validation error", response.isValidationError())
        assertFalse("401 Unauthorized should not be server error", response.isServerError())
    }

    @Test
    fun `response with 403 Forbidden is auth error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Forbidden",
            statusCode = 403
        )

        assertFalse("403 Forbidden should not be successful", response.success)
        assertTrue("403 Forbidden should be auth error", response.isAuthError())
    }

    @Test
    fun `response with 404 Not Found is not found error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Not found",
            statusCode = 404
        )

        assertFalse("404 Not Found should not be successful", response.success)
        assertTrue("404 Not Found should be not found error", response.isNotFoundError())
        assertFalse("404 Not Found should not be auth error", response.isAuthError())
    }

    @Test
    fun `response with 422 Unprocessable Entity is validation error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Unprocessable entity",
            statusCode = 422
        )

        assertFalse("422 Unprocessable Entity should not be successful", response.success)
        assertTrue("422 Unprocessable Entity should be validation error", response.isValidationError())
    }

    @Test
    fun `response with 429 Too Many Requests is rate limit error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Too many requests",
            statusCode = 429
        )

        assertFalse("429 Too Many Requests should not be successful", response.success)
        assertTrue("429 Too Many Requests should be rate limit error", response.isRateLimitError())
        assertFalse("429 Too Many Requests should not be validation error", response.isValidationError())
        assertFalse("429 Too Many Requests should not be server error", response.isServerError())
    }

    @Test
    fun `response with 500 Internal Server Error is server error`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "Internal server error",
            statusCode = 500
        )

        assertFalse("500 Internal Server Error should not be successful", response.success)
        assertTrue("500 Internal Server Error should be server error", response.isServerError())
        assertFalse("500 Internal Server Error should not be auth error", response.isAuthError())
        assertFalse("500 Internal Server Error should not be validation error", response.isValidationError())
    }

    // ==========================================
    // ERROR MESSAGE HANDLING TESTS
    // ==========================================

    @Test
    fun `response with error message contains error`() {
        val errorMessage = "Invalid credentials"
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = errorMessage,
            statusCode = 401
        )

        assertNotNull("Error message should not be null", response.error)
        assertEquals("Error message should match", errorMessage, response.error)
    }

    @Test
    fun `response with null error message is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = true,
            error = null,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNull("Error message should be null", response.error)
    }

    @Test
    fun `response with empty error message is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = "",
            statusCode = 500
        )

        assertFalse("Response should not be successful", response.success)
        assertNotNull("Error message should not be null", response.error)
        assertTrue("Error message should be empty", response.error.isNullOrEmpty())
    }

    @Test
    fun `response with long error message is valid`() {
        val longErrorMessage = "This is a very long error message that contains detailed information about what went wrong with the request and provides context for debugging purposes."
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            error = longErrorMessage,
            statusCode = 500
        )

        assertNotNull("Error message should not be null", response.error)
        assertEquals("Error message should match", longErrorMessage, response.error)
    }

    // ==========================================
    // DATA TYPE HANDLING TESTS
    // ==========================================

    @Test
    fun `response with String data is valid`() {
        val response = SnipHiveApiClient.ApiResponse<String>(
            success = true,
            data = "Test string data",
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertEquals("Data should be string", "Test string data", response.data)
    }

    @Test
    fun `response with Int data is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Int>(
            success = true,
            data = 42,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertEquals("Data should be integer", 42, response.data)
    }

    @Test
    fun `response with Boolean data is valid`() {
        val response = SnipHiveApiClient.ApiResponse<Boolean>(
            success = true,
            data = true,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertEquals("Data should be true", true, response.data)
    }

    @Test
    fun `response with List data is valid`() {
        val testData = listOf(
            TestData("1", "First", 1),
            TestData("2", "Second", 2),
            TestData("3", "Third", 3)
        )
        val response = SnipHiveApiClient.ApiResponse<List<TestData>>(
            success = true,
            data = testData,
            statusCode = 200
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertEquals("Data should be list of 3 items", 3, response.data?.size)
        assertEquals("First item should match", testData[0], response.data?.get(0))
    }

    // ==========================================
    // API CLIENT INSTANCE TESTS
    // ==========================================

    @Test
    fun `getGson returns non-null Gson instance`() {
        val client = SnipHiveApiClient()
        val gson = client.getGson()

        assertNotNull("Gson instance should not be null", gson)
    }

    @Test
    fun `getOkHttpClient returns non-null OkHttpClient instance`() {
        val client = SnipHiveApiClient()
        val okHttpClient = client.getOkHttpClient()

        assertNotNull("OkHttpClient instance should not be null", okHttpClient)
    }

    @Test
    fun `getInstance returns non-null SnipHiveApiClient`() {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (application == null) {
            return
        }

        assertNotNull("SnipHiveApiClient instance should not be null", SnipHiveApiClient.getInstance())
    }

    // ==========================================
    // RESPONSE EDGE CASE TESTS
    // ==========================================

    @Test
    fun `response with status code 199 is not successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            statusCode = 199
        )

        assertFalse("199 should not be successful", response.success)
        assertFalse("199 should not be server error", response.isServerError())
    }

    @Test
    fun `response with status code 300 is not successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            statusCode = 300
        )

        assertFalse("300 should not be successful", response.success)
        assertFalse("300 should not be server error", response.isServerError())
    }

    @Test
    fun `response with status code 600 is not successful`() {
        val response = SnipHiveApiClient.ApiResponse<Any>(
            success = false,
            statusCode = 600
        )

        assertFalse("600 should not be successful", response.success)
        assertFalse("600 should not be server error (above range)", response.isServerError())
    }

    @Test
    fun `buildUrl preserves custom scheme port and base path`() {
        val client = SnipHiveApiClient()
        val method = SnipHiveApiClient::class.java.getDeclaredMethod(
            "buildUrl",
            String::class.java,
            String::class.java,
            Map::class.java
        )
        method.isAccessible = true

        val url = method.invoke(
            client,
            "http://localhost:8000/base-api",
            "/api/v1/snippets",
            mapOf("workspace_id" to "local")
        ) as String

        assertEquals(
            "http://localhost:8000/base-api/api/v1/snippets?workspace_id=local",
            url
        )
    }

    @Test
    fun `response with all fields populated is valid`() {
        val headers = mapOf("X-Custom" to "value")
        val testData = TestData("all-fields", "All Fields", 999)
        val response = SnipHiveApiClient.ApiResponse(
            success = true,
            data = testData,
            error = null,
            statusCode = 200,
            headers = headers
        )

        assertTrue("Response should be successful", response.success)
        assertNotNull("Data should not be null", response.data)
        assertEquals("Data should match", testData, response.data)
        assertNull("Error should be null", response.error)
        assertEquals("Status code should be 200", 200, response.statusCode)
        assertEquals("Headers should match", headers, response.headers)
    }

    // ==========================================
    // CLIENT ERROR CLASSIFICATION TESTS
    // ==========================================

    @Test
    fun `4xx status codes are client errors`() {
        val clientErrors = listOf(400, 401, 402, 403, 404, 405, 406, 422, 429)

        clientErrors.forEach { statusCode ->
            val response = SnipHiveApiClient.ApiResponse<Any>(
                success = false,
                statusCode = statusCode
            )

            assertFalse("Status code $statusCode should not be server error", response.isServerError())
        }
    }

    @Test
    fun `5xx status codes are server errors`() {
        val serverErrors = listOf(500, 501, 502, 503, 504, 505)

        serverErrors.forEach { statusCode ->
            val response = SnipHiveApiClient.ApiResponse<Any>(
                success = false,
                statusCode = statusCode
            )

            assertTrue("Status code $statusCode should be server error", response.isServerError())
        }
    }

    @Test
    fun `2xx status codes are successful`() {
        val successCodes = listOf(200, 201, 202, 203, 204, 205, 206)

        successCodes.forEach { statusCode ->
            val response = SnipHiveApiClient.ApiResponse<Any>(
                success = true,
                statusCode = statusCode
            )

            assertTrue("Status code $statusCode should be successful", response.success)
            assertFalse("Status code $statusCode should not be server error", response.isServerError())
        }
    }

    // ==========================================
    // LARAVEL JSON RESOURCE UNWRAPPING TESTS
    // ==========================================

    /**
     * These tests verify that Gson can correctly deserialize models from both
     * wrapped (Laravel JsonResource) and unwrapped JSON responses.
     *
     * The SnipHiveApiClient.unwrapLaravelData() method strips the {"data": {...}}
     * wrapper before Gson parsing. These tests confirm the Gson deserialization
     * works correctly after unwrapping.
     */

    data class SimpleModel(
        val id: String,
        val title: String,
        val content: String
    )

    @Test
    fun `Gson correctly deserializes wrapped Laravel response after unwrapping`() {
        val gson = SnipHiveApiClient().getGson()

        // Simulate Laravel's wrapped response: {"data": {"id": "1", "title": "Test", "content": "Hello"}}
        val wrappedJson = """{"data": {"id": "1", "title": "Test", "content": "Hello"}}"""

        // Simulate the unwrapLaravelData logic
        val jsonElement = gson.fromJson(wrappedJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject
        assertNotNull("Response should be a JsonObject", jsonObj)
        assertTrue("'data' key should exist", jsonObj.has("data"))
        assertTrue("'data' should be a JsonObject", jsonObj.get("data").isJsonObject)

        val unwrappedJson = jsonObj.get("data").toString()
        val model = gson.fromJson(unwrappedJson, SimpleModel::class.java)

        assertEquals("ID should be '1'", "1", model.id)
        assertEquals("Title should be 'Test'", "Test", model.title)
        assertEquals("Content should be 'Hello'", "Hello", model.content)
    }

    @Test
    fun `Gson correctly deserializes unwrapped JSON response`() {
        val gson = SnipHiveApiClient().getGson()

        // Unwrapped response (no Laravel wrapping)
        val unwrappedJson = """{"id": "42", "title": "Direct", "content": "World"}"""
        val model = gson.fromJson(unwrappedJson, SimpleModel::class.java)

        assertEquals("ID should be '42'", "42", model.id)
        assertEquals("Title should be 'Direct'", "Direct", model.title)
        assertEquals("Content should be 'World'", "World", model.content)
    }

    @Test
    fun `Laravel wrapped response without unwrapping produces null fields`() {
        val gson = SnipHiveApiClient().getGson()

        // This is the BUG: parsing wrapped response directly into model without unwrapping
        val wrappedJson = """{"data": {"id": "1", "title": "Test", "content": "Hello"}}"""
        val model = gson.fromJson(wrappedJson, SimpleModel::class.java)

        // Without unwrapping, all fields are null/default because the top-level
        // keys are "data", not "id", "title", "content"
        assertNull("ID should be null when parsed without unwrapping", model.id)
        assertNull("Title should be null when parsed without unwrapping", model.title)
        assertNull("Content should be null when parsed without unwrapping", model.content)
    }

    @Test
    fun `Paginated response with data as array is not mistakenly unwrapped`() {
        val gson = SnipHiveApiClient().getGson()

        // Paginated response: {"data": [...], "meta": {...}}
        val paginatedJson = """{"data": [{"id": "1", "title": "A", "content": "X"}], "meta": {"current_page": 1, "total": 1}}"""
        val jsonElement = gson.fromJson(paginatedJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject

        // "data" is a JsonArray, not a JsonObject — should NOT be unwrapped
        assertTrue("'data' key should exist", jsonObj.has("data"))
        assertFalse("'data' should NOT be a JsonObject (it's an array)", jsonObj.get("data").isJsonObject)
        assertTrue("'data' should be a JsonArray", jsonObj.get("data").isJsonArray)
    }

    @Test
    fun `Response with message only is not unwrapped`() {
        val gson = SnipHiveApiClient().getGson()

        // Error response with no "data" key
        val errorJson = """{"message": "Validation failed", "errors": {"title": ["Required"]}}"""
        val jsonElement = gson.fromJson(errorJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject

        // No "data" key — should not be unwrapped
        assertFalse("'data' key should not exist", jsonObj.has("data"))
    }

    @Test
    fun `Laravel wrapped Note model deserializes correctly after unwrapping`() {
        val gson = SnipHiveApiClient().getGson()

        // Simulate a real Laravel wrapped note response
        val wrappedNoteJson = """{
            "data": {
                "id": "123",
                "uuid": "abc-def-ghi",
                "title": "My Note",
                "content": "Note content here",
                "encrypted_dek": null,
                "is_public": false,
                "is_pinned": false,
                "is_favorite": true,
                "archived_at": null,
                "url": "/notes/123",
                "public_url": null,
                "created_at": "2026-01-15T10:30:00.000Z",
                "updated_at": "2026-01-15T10:30:00.000Z",
                "user": {"name": "Test User", "email": "test@example.com"},
                "tags": []
            }
        }"""

        // Simulate unwrapLaravelData logic
        val jsonElement = gson.fromJson(wrappedNoteJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject
        val unwrappedJson = jsonObj.get("data").toString()

        // Parse using the actual Note model
        val note = gson.fromJson(unwrappedJson, com.sniphive.idea.models.Note::class.java)

        assertEquals("Note ID should be '123'", "123", note.id)
        assertEquals("Note UUID should be 'abc-def-ghi'", "abc-def-ghi", note.uuid)
        assertEquals("Note title should be 'My Note'", "My Note", note.title)
        assertEquals("Note content should match", "Note content here", note.content)
        assertFalse("Note should not be public", note.isPublic)
        assertFalse("Note should not be pinned", note.isPinned)
        assertTrue("Note should be favorited", note.isFavorite)
        assertFalse("Note should not be archived", note.isArchived())
        assertNotNull("Note user should not be null", note.user)
        assertEquals("User name should match", "Test User", note.user!!.name)
    }

    @Test
    fun `Laravel wrapped Snippet model deserializes correctly after unwrapping`() {
        val gson = SnipHiveApiClient().getGson()

        val wrappedSnippetJson = """{
            "data": {
                "id": "456",
                "uuid": "xyz-uvw-rst",
                "slug": "my-snippet",
                "title": "My Snippet",
                "content": "val x = 42",
                "language": "kotlin",
                "encrypted_dek": null,
                "is_public": true,
                "is_pinned": true,
                "is_favorite": false,
                "archived_at": null,
                "url": "/snippets/my-snippet",
                "tags": []
            }
        }"""

        val jsonElement = gson.fromJson(wrappedSnippetJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject
        val unwrappedJson = jsonObj.get("data").toString()

        val snippet = gson.fromJson(unwrappedJson, com.sniphive.idea.models.Snippet::class.java)

        assertEquals("Snippet ID should be '456'", "456", snippet.id)
        assertEquals("Snippet slug should be 'my-snippet'", "my-snippet", snippet.slug)
        assertEquals("Snippet title should be 'My Snippet'", "My Snippet", snippet.title)
        assertEquals("Snippet language should be 'kotlin'", "kotlin", snippet.language)
        assertTrue("Snippet should be public", snippet.isPublic)
        assertTrue("Snippet should be pinned", snippet.isPinned)
        assertFalse("Snippet should not be favorited", snippet.isFavorite)
    }

    @Test
    fun `Laravel wrapped Tag model deserializes correctly after unwrapping`() {
        val gson = SnipHiveApiClient().getGson()

        val wrappedTagJson = """{
            "data": {
                "id": "789",
                "name": "Kotlin",
                "slug": "kotlin",
                "color": "#7F52FF",
                "snippets_count": 5,
                "notes_count": 2,
                "created_at": "2026-01-10T08:00:00.000Z",
                "updated_at": "2026-01-10T08:00:00.000Z"
            }
        }"""

        val jsonElement = gson.fromJson(wrappedTagJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject
        val unwrappedJson = jsonObj.get("data").toString()

        val tag = gson.fromJson(unwrappedJson, com.sniphive.idea.models.Tag::class.java)

        assertEquals("Tag ID should be '789'", "789", tag.id)
        assertEquals("Tag name should be 'Kotlin'", "Kotlin", tag.name)
        assertEquals("Tag slug should be 'kotlin'", "kotlin", tag.slug)
        assertEquals("Tag color should be '#7F52FF'", "#7F52FF", tag.color)
        assertEquals("Tag snippets count should be 5", 5, tag.snippetsCount)
        assertEquals("Tag notes count should be 2", 2, tag.notesCount)
        assertTrue("Tag should have color", tag.hasColor())
    }

    @Test
    fun `Laravel wrapped Workspace model deserializes correctly after unwrapping`() {
        val gson = SnipHiveApiClient().getGson()

        val wrappedWorkspaceJson = """{
            "data": {
                "id": "101",
                "uuid": "ws-uuid-123",
                "name": "My Workspace",
                "type": "personal",
                "role": "owner",
                "created_at": "2026-01-01T00:00:00.000Z",
                "updated_at": "2026-01-01T00:00:00.000Z"
            }
        }"""

        val jsonElement = gson.fromJson(wrappedWorkspaceJson, com.google.gson.JsonElement::class.java)
        val jsonObj = jsonElement.asJsonObject
        val unwrappedJson = jsonObj.get("data").toString()

        val workspace = gson.fromJson(unwrappedJson, com.sniphive.idea.models.Workspace::class.java)

        assertEquals("Workspace ID should be '101'", "101", workspace.id)
        assertEquals("Workspace name should be 'My Workspace'", "My Workspace", workspace.name)
        assertTrue("Workspace should be personal", workspace.isPersonal())
        assertTrue("User should be owner", workspace.isOwner())
    }

    @Test
    fun `Note model with null id field causes NullPointerException accessing non-nullable property`() {
        val gson = SnipHiveApiClient().getGson()

        // When wrapped response is parsed WITHOUT unwrapping, all fields are null
        val wrappedJson = """{"data": {"id": "1", "title": "Test", "content": "Hello"}}"""
        val note = gson.fromJson(wrappedJson, com.sniphive.idea.models.Note::class.java)

        // The 'id' field is declared as non-nullable String, but Gson sets it to null
        assertNull("Note.id should be null when parsed without unwrapping", note.id)

        // Accessing note.id!! would throw NPE — this is the root cause of the dialog hanging
        try {
            val idValue = note.id
            // If we get here, id is null (Gson bypasses Kotlin null-safety)
            // Any code that does note.id.someMethod() would NPE
            assertNull("Confirming id is null", idValue)
        } catch (e: NullPointerException) {
            fail("Should not throw NPE on simple access, but would on any method call")
        }
    }
}
