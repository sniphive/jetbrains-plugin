package com.sniphive.idea.services

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SnipHiveAuthService.
 *
 * Tests cover:
 * - Token validation (verifyToken)
 * - Authentication state checks
 */
class SnipHiveAuthServiceTest {

    // ==========================================
    // VERIFY TOKEN TESTS
    // ==========================================

    @Test
    fun `verifyToken method should exist with correct signature`() {
        val method = try {
            SnipHiveAuthService::class.java.getDeclaredMethod(
                "verifyToken",
                com.intellij.openapi.project.Project::class.java,
                String::class.java,
                String::class.java
            )
        } catch (e: NoSuchMethodException) {
            fail("verifyToken method should exist with signature (Project?, String, String): ${e.message}")
            return
        }

        assertNotNull("verifyToken method should exist", method)
        assertEquals("verifyToken should return Boolean", Boolean::class.java, method.returnType)
        assertTrue("verifyToken should be public", java.lang.reflect.Modifier.isPublic(method.modifiers))
    }

    @Test
    fun `verifyToken returns false when no token is stored`() {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (application == null) {
            // Skip when not running in IDE environment
            return
        }
        val authService = SnipHiveAuthService.getInstance()
        // No token stored for this email
        val result = authService.verifyToken(null, "https://api.sniphive.net", "nonexistent@example.com")
        assertFalse("verifyToken should return false when no token exists", result)
    }

    @Test
    fun `isAuthenticated returns false when no token is stored`() {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (application == null) {
            // Skip when not running in IDE environment
            return
        }
        val authService = SnipHiveAuthService.getInstance()
        val result = authService.isAuthenticated(null, "nonexistent@example.com")
        assertFalse("isAuthenticated should return false when no token exists", result)
    }

    @Test
    fun `getAuthToken returns null when no token is stored`() {
        val application = com.intellij.openapi.application.ApplicationManager.getApplication()
        if (application == null) {
            // Skip when not running in IDE environment
            return
        }
        val authService = SnipHiveAuthService.getInstance()
        val result = authService.getAuthToken(null, "nonexistent@example.com")
        assertNull("getAuthToken should return null when no token exists", result)
    }
}
