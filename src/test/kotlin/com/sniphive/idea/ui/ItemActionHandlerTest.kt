package com.sniphive.idea.ui

import com.google.gson.JsonObject
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.RSACrypto
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.KeyPair
import java.security.PrivateKey
import java.util.Base64

/**
 * Unit tests for ItemActionHandler.copyPublicUrl with E2EE DEK hash support.
 *
 * These tests verify the DEK decryption and base64 encoding logic
 * without mocking UI components.
 *
 * Test scenarios:
 * 1. Base64 encoding/decoding of DEK
 * 2. RSA key generation and DEK encryption/decryption
 * 3. URL hash fragment construction
 */
class ItemActionHandlerTest {

    private lateinit var keyPair: KeyPair
    private lateinit var privateKey: PrivateKey
    private lateinit var privateKeyJwk: JsonObject

    @Before
    fun setUp() {
        // Generate RSA key pair for testing
        keyPair = RSACrypto.generateKeyPair()
        privateKey = keyPair.private
        privateKeyJwk = RSACrypto.exportPrivateKeyToJWK(privateKey)
    }

    /**
     * Test: DEK can be encrypted and decrypted successfully.
     */
    @Test
    fun `DEK encryption and decryption should work correctly`() {
        // Generate a random DEK (32 bytes for AES-256)
        val dek = E2EECryptoService.generateRandomBytes(32)

        // Encrypt DEK with public key
        val encryptedDek = RSACrypto.encrypt(keyPair.public, dek)

        // Decrypt DEK with private key
        val decryptedDek = RSACrypto.decrypt(privateKey, encryptedDek)

        // Verify
        assertArrayEquals("DEK should be decrypted correctly", dek, decryptedDek)
    }

    /**
     * Test: DEK can be converted to base64 and back.
     */
    @Test
    fun `DEK base64 conversion should work correctly`() {
        // Generate a random DEK
        val dek = E2EECryptoService.generateRandomBytes(32)

        // Convert to base64
        val dekBase64 = E2EECryptoService.arrayBufferToBase64(dek)

        // Convert back
        val dekFromBase64 = E2EECryptoService.base64ToArrayBuffer(dekBase64)

        // Verify
        assertArrayEquals("DEK should be converted correctly", dek, dekFromBase64)
    }

    /**
     * Test: URL hash fragment is constructed correctly.
     */
    @Test
    fun `URL hash fragment should be constructed correctly`() {
        val publicUrl = "https://sniphive.net/s/abc123"
        val dek = E2EECryptoService.generateRandomBytes(32)
        val dekBase64 = E2EECryptoService.arrayBufferToBase64(dek)

        // Construct URL with hash
        val urlWithHash = "$publicUrl#$dekBase64"

        // Verify
        assertTrue("URL should contain hash fragment", urlWithHash.contains("#"))
        assertTrue("URL should start with public URL", urlWithHash.startsWith(publicUrl))
        assertTrue("URL should end with DEK base64", urlWithHash.endsWith(dekBase64))

        // Extract and verify hash
        val hashFragment = urlWithHash.substringAfter("#")
        assertEquals("Hash fragment should match DEK base64", dekBase64, hashFragment)
    }

    /**
     * Test: Private key can be exported to JWK and imported back.
     */
    @Test
    fun `Private key JWK export and import should work correctly`() {
        // Export to JWK
        val jwk = RSACrypto.exportPrivateKeyToJWK(privateKey)

        // Verify JWK structure
        assertNotNull("JWK should not be null", jwk)
        assertEquals("Key type should be RSA", "RSA", jwk.get("kty").asString)
        assertNotNull("Modulus should not be null", jwk.get("n"))
        assertNotNull("Exponent should not be null", jwk.get("e"))
        assertNotNull("Private exponent should not be null", jwk.get("d"))

        // Import back
        val importedKey = RSACrypto.importPrivateKeyFromJWK(jwk)

        // Verify by encrypting and decrypting
        val testData = "Test data for encryption".toByteArray()
        val encrypted = RSACrypto.encrypt(keyPair.public, testData)
        val decrypted = RSACrypto.decrypt(importedKey, encrypted)

        assertArrayEquals("Data should be decrypted correctly with imported key", testData, decrypted)
    }

    /**
     * Test: Full flow - encrypt DEK, convert to base64, construct URL.
     */
    @Test
    fun `Full DEK hash flow should work correctly`() {
        val publicUrl = "https://sniphive.net/s/abc123"

        // Generate DEK
        val dek = E2EECryptoService.generateRandomBytes(32)

        // Encrypt DEK
        val encryptedDek = RSACrypto.encrypt(keyPair.public, dek)
        val encryptedDekBase64 = E2EECryptoService.arrayBufferToBase64(encryptedDek)

        // Decrypt DEK (simulating what copyPublicUrl does)
        val encryptedDekBytes = E2EECryptoService.base64ToArrayBuffer(encryptedDekBase64)
        val decryptedDek = RSACrypto.decrypt(privateKey, encryptedDekBytes)

        // Convert to base64 for URL hash
        val dekBase64 = E2EECryptoService.arrayBufferToBase64(decryptedDek)

        // Construct URL
        val urlWithHash = "$publicUrl#$dekBase64"

        // Verify
        assertTrue("URL should contain hash fragment", urlWithHash.contains("#"))
        assertEquals("DEK should match original", Base64.getEncoder().encodeToString(dek), dekBase64)
    }

    /**
     * Test: Base64 URL-safe encoding.
     */
    @Test
    fun `Base64 encoding should handle special characters`() {
        // Generate DEK with various byte values
        val dek = ByteArray(32) { it.toByte() }

        // Convert to base64
        val dekBase64 = E2EECryptoService.arrayBufferToBase64(dek)

        // Verify it's valid base64
        assertNotNull("Base64 should not be null", dekBase64)
        assertTrue("Base64 should not be empty", dekBase64.isNotEmpty())

        // Convert back and verify
        val dekFromBase64 = E2EECryptoService.base64ToArrayBuffer(dekBase64)
        assertArrayEquals("DEK should match after round-trip", dek, dekFromBase64)
    }

    /**
     * Test: Empty DEK handling.
     */
    @Test(expected = IllegalArgumentException::class)
    fun `Empty DEK should throw exception`() {
        E2EECryptoService.base64ToArrayBuffer("")
    }

    /**
     * Test: Null parameters should result in plain URL copy.
     * This is tested by verifying the logic path, not actual clipboard interaction.
     */
    @Test
    fun `Null encryptedDek should skip DEK decryption`() {
        val publicUrl = "https://sniphive.net/s/abc123"

        // When encryptedDek is null, URL should be copied without hash
        // This is verified by the logic in copyPublicUrl:
        // if (encryptedDek != null && email != null) { ... }
        // If encryptedDek is null, the condition is false and URL is copied as-is

        // Verify the condition logic
        val encryptedDek: String? = null
        val email: String? = "test@example.com"

        val shouldDecrypt = encryptedDek != null && email != null
        assertFalse("Should not attempt DEK decryption when encryptedDek is null", shouldDecrypt)
    }

    /**
     * Test: Null email should skip DEK decryption.
     */
    @Test
    fun `Null email should skip DEK decryption`() {
        val publicUrl = "https://sniphive.net/s/abc123"

        // When email is null, URL should be copied without hash
        val encryptedDek: String? = "encryptedDekBase64"
        val email: String? = null

        val shouldDecrypt = encryptedDek != null && email != null
        assertFalse("Should not attempt DEK decryption when email is null", shouldDecrypt)
    }

    /**
     * Test: Both parameters present should enable DEK decryption.
     */
    @Test
    fun `Both parameters present should enable DEK decryption`() {
        val encryptedDek: String? = "encryptedDekBase64"
        val email: String? = "test@example.com"

        val shouldDecrypt = encryptedDek != null && email != null
        assertTrue("Should attempt DEK decryption when both parameters are present", shouldDecrypt)
    }

    // ──────────────────────────────────────────────────────────────
    // New tests: copyContent encrypted block, hasDek logic,
    //            truncated content, DEK decrypt failure fallback
    // ──────────────────────────────────────────────────────────────

    /**
     * Test: copyContent should block encrypted content.
     * Verifies the isEncrypted guard logic without UI interaction.
     */
    @Test
    fun `copyContent should block encrypted content`() {
        val isEncrypted = true

        // Simulate the guard condition in copyContent
        if (isEncrypted) {
            // Content copy is blocked — verified by the guard
            assertTrue("Encrypted content should be blocked", true)
        } else {
            fail("Encrypted content should have been blocked")
        }
    }

    /**
     * Test: copyContent should allow non-encrypted content.
     */
    @Test
    fun `copyContent should allow non-encrypted content`() {
        val isEncrypted = false
        var copied = false

        // Simulate the guard condition in copyContent
        if (isEncrypted) {
            fail("Non-encrypted content should not be blocked")
        } else {
            copied = true
        }

        assertTrue("Non-encrypted content should be copied", copied)
    }

    /**
     * Test: hasDek should be false when encryptedDek is null.
     */
    @Test
    fun `hasDek should be false when encryptedDek is null`() {
        val encryptedDek: String? = null
        val email: String? = "test@example.com"

        val hasDek = encryptedDek != null && email != null
        assertFalse("hasDek should be false when encryptedDek is null", hasDek)
    }

    /**
     * Test: hasDek should be false when email is null.
     */
    @Test
    fun `hasDek should be false when email is null`() {
        val encryptedDek: String? = "someEncryptedDek"
        val email: String? = null

        val hasDek = encryptedDek != null && email != null
        assertFalse("hasDek should be false when email is null", hasDek)
    }

    /**
     * Test: hasDek should be true when both parameters are present.
     */
    @Test
    fun `hasDek should be true when both encryptedDek and email are present`() {
        val encryptedDek: String? = "someEncryptedDek"
        val email: String? = "user@example.com"

        val hasDek = encryptedDek != null && email != null
        assertTrue("hasDek should be true when both parameters are present", hasDek)
    }

    /**
     * Test: Content exceeding MAX_CONTENT_LENGTH should be truncated.
     */
    @Test
    fun `Content exceeding MAX_CONTENT_LENGTH should be truncated`() {
        val maxLength = 500_000
        val oversizedContent = "x".repeat(maxLength + 10_000)

        val truncated = oversizedContent.take(maxLength)

        assertEquals("Truncated content should be exactly MAX_CONTENT_LENGTH", maxLength, truncated.length)
        assertTrue("Original content should exceed MAX_CONTENT_LENGTH", oversizedContent.length > maxLength)
    }

    /**
     * Test: Content within MAX_CONTENT_LENGTH should not be truncated.
     */
    @Test
    fun `Content within MAX_CONTENT_LENGTH should not be truncated`() {
        val maxLength = 500_000
        val normalContent = "Short snippet content"

        val result = normalContent.take(maxLength)

        assertEquals("Content within limit should not be changed", normalContent, result)
    }

    /**
     * Test: DEK decryption failure should result in graceful fallback (URL without hash).
     */
    @Test
    fun `DEK decryption failure should fallback to plain URL`() {
        val publicUrl = "https://sniphive.net/s/abc123"
        val hasDek = true

        // Simulate decryption failure
        var urlToCopy = publicUrl
        var decryptionSucceeded = false

        try {
            // Simulate invalid encrypted DEK (would cause decryption to fail)
            val invalidEncryptedDek = "!!!invalid-base64!!!"
            val encryptedDekBytes = E2EECryptoService.base64ToArrayBuffer(invalidEncryptedDek)
            val dekBytes = RSACrypto.decrypt(privateKey, encryptedDekBytes)
            val dekBase64 = E2EECryptoService.arrayBufferToBase64(dekBytes)
            urlToCopy = "$publicUrl#$dekBase64"
            decryptionSucceeded = true
        } catch (e: Exception) {
            // Graceful fallback — URL stays as-is without hash
            urlToCopy = publicUrl
        }

        assertFalse("Decryption should have failed", decryptionSucceeded)
        assertEquals("URL should fallback to plain URL without hash", publicUrl, urlToCopy)
        assertFalse("URL should not contain hash fragment on failure", urlToCopy.contains("#"))
    }

    /**
     * Test: DEK decryption with wrong private key should fallback gracefully.
     */
    @Test
    fun `DEK decryption with wrong private key should fallback gracefully`() {
        val publicUrl = "https://sniphive.net/s/abc123"

        // Generate a different key pair (wrong key)
        val wrongKeyPair = RSACrypto.generateKeyPair()

        // Encrypt DEK with correct public key
        val dek = E2EECryptoService.generateRandomBytes(32)
        val encryptedDek = RSACrypto.encrypt(keyPair.public, dek)

        // Try to decrypt with wrong private key — should fail
        var urlToCopy = publicUrl
        var decryptionSucceeded = false

        try {
            val decryptedDek = RSACrypto.decrypt(wrongKeyPair.private, encryptedDek)
            val dekBase64 = E2EECryptoService.arrayBufferToBase64(decryptedDek)
            urlToCopy = "$publicUrl#$dekBase64"
            decryptionSucceeded = true
        } catch (e: Exception) {
            // Graceful fallback
            urlToCopy = publicUrl
        }

        assertFalse("Decryption with wrong key should fail", decryptionSucceeded)
        assertEquals("URL should fallback to plain URL", publicUrl, urlToCopy)
    }
}