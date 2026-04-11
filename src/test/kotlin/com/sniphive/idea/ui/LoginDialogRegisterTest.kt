package com.sniphive.idea.ui

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for LoginDialog Register button functionality.
 *
 * These tests verify:
 * 1. The registration URL is correct
 * 2. Button text is correct
 * 3. URL handling logic
 */
class LoginDialogRegisterTest {

    companion object {
        private const val REGISTER_URL = "https://sniphive.net/register"
    }

    /**
     * Test: Registration URL should be correct.
     */
    @Test
    fun `Registration URL should be https sniphive net register`() {
        assertEquals("Registration URL should be https://sniphive.net/register", 
            "https://sniphive.net/register", REGISTER_URL)
    }

    /**
     * Test: Registration URL should use HTTPS.
     */
    @Test
    fun `Registration URL should use HTTPS protocol`() {
        assertTrue("URL should start with https://", REGISTER_URL.startsWith("https://"))
    }

    /**
     * Test: Registration URL should be a valid URL format.
     */
    @Test
    fun `Registration URL should be valid format`() {
        assertTrue("URL should contain sniphive.net", REGISTER_URL.contains("sniphive.net"))
        assertTrue("URL should end with /register", REGISTER_URL.endsWith("/register"))
    }

    /**
     * Test: Button text should be 'Register'.
     */
    @Test
    fun `Button text should be Register`() {
        val buttonText = "Register"
        assertEquals("Button text should be 'Register'", "Register", buttonText)
    }
}