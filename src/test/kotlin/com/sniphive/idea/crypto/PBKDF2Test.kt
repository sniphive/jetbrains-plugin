package com.sniphive.idea.crypto

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import javax.crypto.SecretKey

/**
 * Unit tests for PBKDF2 key derivation.
 *
 * Tests cover:
 * - Key derivation with valid inputs
 * - Salt generation
 * - Deterministic key derivation (same password + salt = same key)
 * - Different salts produce different keys
 * - Different passwords produce different keys
 * - Key format and length validation
 * - Input validation (empty password, salt, invalid iterations)
 */
class PBKDF2Test {

    private lateinit var testPassword: String
    private lateinit var testSalt: ByteArray

    @Before
    fun setUp() {
        testPassword = "TestPassword123!"
        testSalt = PBKDF2.generateSalt()
    }

    @Test
    fun `deriveKey generates valid SecretKey`() {
        val key = PBKDF2.deriveKey(testPassword, testSalt)

        assertNotNull("Derived key should not be null", key)
        assertEquals("Key algorithm should be AES", "AES", key.algorithm)
        assertEquals("Key format should be RAW", "RAW", key.format)
    }

    @Test
    fun `deriveKey generates correct key length`() {
        val key = PBKDF2.deriveKey(testPassword, testSalt)

        assertEquals("Derived key should be 256 bits (32 bytes)", 32, key.encoded.size)
    }

    @Test
    fun `deriveKey with custom iterations uses specified count`() {
        val key1 = PBKDF2.deriveKey(testPassword, testSalt, 100000)
        val key2 = PBKDF2.deriveKey(testPassword, testSalt, 200000)

        assertNotNull("Key should be derived with custom iterations", key1)
        assertNotNull("Key should be derived with different iterations", key2)

        // Keys should differ due to different iteration counts
        assertNotEquals("Different iterations should produce different keys",
            key1.encoded.contentToString(),
            key2.encoded.contentToString()
        )
    }

    @Test
    fun `deriveKey is deterministic - same password and salt produce same key`() {
        val key1 = PBKDF2.deriveKey(testPassword, testSalt)
        val key2 = PBKDF2.deriveKey(testPassword, testSalt)

        assertArrayEquals("Same password and salt should produce same key",
            key1.encoded, key2.encoded)
    }

    @Test
    fun `deriveKey with different salts produces different keys`() {
        val salt1 = PBKDF2.generateSalt()
        val salt2 = PBKDF2.generateSalt()

        val key1 = PBKDF2.deriveKey(testPassword, salt1)
        val key2 = PBKDF2.deriveKey(testPassword, salt2)

        assertNotEquals("Different salts should produce different keys",
            key1.encoded.contentToString(),
            key2.encoded.contentToString()
        )
    }

    @Test
    fun `deriveKey with different passwords produces different keys`() {
        val password1 = "Password1"
        val password2 = "Password2"

        val key1 = PBKDF2.deriveKey(password1, testSalt)
        val key2 = PBKDF2.deriveKey(password2, testSalt)

        assertNotEquals("Different passwords should produce different keys",
            key1.encoded.contentToString(),
            key2.encoded.contentToString()
        )
    }

    @Test
    fun `generateSalt produces unique salts`() {
        val salt1 = PBKDF2.generateSalt()
        val salt2 = PBKDF2.generateSalt()
        val salt3 = PBKDF2.generateSalt()

        assertEquals("Default salt length should be 16 bytes", 16, salt1.size)
        assertEquals("Default salt length should be 16 bytes", 16, salt2.size)
        assertEquals("Default salt length should be 16 bytes", 16, salt3.size)

        assertNotEquals("Salts should be unique",
            salt1.contentToString(), salt2.contentToString())
        assertNotEquals("Salts should be unique",
            salt2.contentToString(), salt3.contentToString())
        assertNotEquals("Salts should be unique",
            salt1.contentToString(), salt3.contentToString())
    }

    @Test
    fun `generateSalt with custom length produces correct length`() {
        val salt8 = PBKDF2.generateSalt(8)
        val salt16 = PBKDF2.generateSalt(16)
        val salt32 = PBKDF2.generateSalt(32)

        assertEquals("Custom salt length should be 8 bytes", 8, salt8.size)
        assertEquals("Custom salt length should be 16 bytes", 16, salt16.size)
        assertEquals("Custom salt length should be 32 bytes", 32, salt32.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriveKey throws exception for empty password`() {
        PBKDF2.deriveKey("", testSalt)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriveKey throws exception for empty salt`() {
        PBKDF2.deriveKey(testPassword, byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriveKey throws exception for too short salt`() {
        PBKDF2.deriveKey(testPassword, byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `deriveKey throws exception for too few iterations`() {
        PBKDF2.deriveKey(testPassword, testSalt, 1000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateSalt throws exception for length less than 8`() {
        PBKDF2.generateSalt(4)
    }

    @Test
    fun `default iterations constant matches OWASP recommendation`() {
        assertEquals("Default iterations should be 600,000 (OWASP minimum)",
            600000, PBKDF2.PBKDF2_ITERATIONS)
    }

    @Test
    fun `derived key length constant matches AES-256`() {
        assertEquals("Derived key length should be 32 bytes (256 bits for AES-256)",
            32, PBKDF2.DERIVED_KEY_LENGTH)
    }

    @Test
    fun `salt length constant is 16 bytes (128 bits)`() {
        assertEquals("Salt length should be 16 bytes (128 bits)",
            16, PBKDF2.SALT_LENGTH)
    }

    @Test
    fun `key algorithm constant is AES`() {
        assertEquals("Key algorithm should be AES",
            "AES", PBKDF2.KEY_ALGORITHM)
    }
}
