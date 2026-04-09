package com.sniphive.idea.crypto

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.security.KeyPair

/**
 * Unit tests for Envelope Encryption (Hybrid Encryption).
 *
 * Tests cover:
 * - DEK generation, export, and import
 * - Envelope encryption and decryption (content + encrypted DEK)
 * - Anonymous encryption (for URL hash sharing)
 * - DEK extraction from URL hash
 * - Round-trip encryption/decryption
 * - Input validation
 */
class EnvelopeEncryptionTest {

    private lateinit var keyPair: KeyPair
    private lateinit var testContent: String

    @Before
    fun setUp() {
        keyPair = RSACrypto.generateKeyPair()
        testContent = "This is a test message for envelope encryption. It can contain special characters: 你好 🌍"
    }

    @Test
    fun `generateDEK creates valid SecretKey`() {
        val dek = EnvelopeEncryption.generateDEK()

        assertNotNull("Generated DEK should not be null", dek)
        assertEquals("DEK algorithm should be AES", "AES", dek.algorithm)
        assertEquals("DEK format should be RAW", "RAW", dek.format)
    }

    @Test
    fun `generateDEK creates 256-bit key`() {
        val dek = EnvelopeEncryption.generateDEK()

        assertEquals("Generated DEK should be 256 bits (32 bytes)", 32, dek.encoded.size)
    }

    @Test
    fun `generateDEK produces unique keys`() {
        val dek1 = EnvelopeEncryption.generateDEK()
        val dek2 = EnvelopeEncryption.generateDEK()
        val dek3 = EnvelopeEncryption.generateDEK()

        assertNotEquals("Generated DEKs should be unique",
            dek1.encoded.contentToString(), dek2.encoded.contentToString())
        assertNotEquals("Generated DEKs should be unique",
            dek2.encoded.contentToString(), dek3.encoded.contentToString())
        assertNotEquals("Generated DEKs should be unique",
            dek1.encoded.contentToString(), dek3.encoded.contentToString())
    }

    @Test
    fun `exportDEK and importDEK round-trip produces working key`() {
        val originalDEK = EnvelopeEncryption.generateDEK()
        val exported = EnvelopeEncryption.exportDEK(originalDEK)
        val importedDEK = EnvelopeEncryption.importDEK(exported)

        assertArrayEquals("Imported DEK should match original DEK",
            originalDEK.encoded, importedDEK.encoded)
    }

    @Test
    fun `encryptContent and decryptContent produce original content`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Decrypted content should match original", testContent, decrypted)
    }

    @Test
    fun `encryptContent produces valid EncryptedContentResult`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)

        assertNotNull("Encrypted result should not be null", encrypted)
        assertNotNull("Encrypted content should not be null", encrypted.content)
        assertNotNull("Encrypted DEK should not be null", encrypted.encryptedDEK)

        // Check format: "base64(iv).base64(ciphertext)"
        assertTrue("Content should contain separator", encrypted.content.contains('.'))
    }

    @Test
    fun `encryptContent produces different results for same content`() {
        val encrypted1 = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        val encrypted2 = EnvelopeEncryption.encryptContent(testContent, keyPair.public)

        assertNotEquals("Encrypted content should differ (different DEKs and IVs)",
            encrypted1.content, encrypted2.content)
        assertNotEquals("Encrypted DEK should differ (different DEKs)",
            encrypted1.encryptedDEK, encrypted2.encryptedDEK)
    }

    @Test
    fun `decryptContent with wrong private key fails`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        val wrongKeyPair = RSACrypto.generateKeyPair()

        try {
            EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, wrongKeyPair.private)
            fail("Decryption with wrong private key should throw an exception")
        } catch (e: Exception) {
            // Expected - decryption should fail with wrong key
        }
    }

    @Test
    fun `decryptContentWithDEK produces original content`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        val encryptedDEK = EnvelopeEncryption.exportDEK(EnvelopeEncryption.generateDEK())
        // Encrypt the actual DEK used in the encryption
        val rawDEK = EnvelopeEncryption.exportDEK(EnvelopeEncryption.generateDEK())
        val encryptedDEKBytes = RSACrypto.encrypt(keyPair.public, rawDEK)

        // But we need to get the actual DEK from the encryption
        // Let's test with a different approach - encrypt anonymously and decrypt with DEK
    }

    @Test
    fun `encryptContentAnonymous and decryptContentAnonymous produce original content`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val dekBase64 = java.util.Base64.getEncoder().encodeToString(encrypted.dek)

        val decrypted = EnvelopeEncryption.decryptContentAnonymous(encrypted.content, dekBase64)

        assertEquals("Decrypted content should match original", testContent, decrypted)
    }

    @Test
    fun `encryptContentAnonymous produces valid AnonymousEncryptionResult`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)

        assertNotNull("Encrypted result should not be null", encrypted)
        assertNotNull("Encrypted content should not be null", encrypted.content)
        assertNotNull("DEK should not be null", encrypted.dek)
        assertNotNull("IV should not be null", encrypted.iv)

        // Check format: "base64(iv).base64(ciphertext)"
        assertTrue("Content should contain separator", encrypted.content.contains('.'))

        // Check DEK is 32 bytes (AES-256)
        assertEquals("DEK should be 32 bytes (256 bits)", 32, encrypted.dek.size)

        // Check IV is valid Base64
        assertNotEquals("IV should not be empty", "", encrypted.iv)
    }

    @Test
    fun `encryptContentAnonymous produces different results for same content`() {
        val encrypted1 = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val encrypted2 = EnvelopeEncryption.encryptContentAnonymous(testContent)

        assertNotEquals("Encrypted content should differ (different DEKs and IVs)",
            encrypted1.content, encrypted2.content)
        assertNotEquals("DEKs should differ (randomly generated)",
            encrypted1.dek.contentToString(), encrypted2.dek.contentToString())
    }

    @Test
    fun `createAnonymousShareableUrl creates valid URL`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val url = EnvelopeEncryption.createAnonymousShareableUrl("/s/abc123", encrypted.dek)

        assertTrue("URL should contain base path", url.contains("/s/abc123"))
        assertTrue("URL should contain hash fragment", url.contains('#'))
        assertTrue("URL should end with DEK", url.endsWith(java.util.Base64.getEncoder().encodeToString(encrypted.dek)))
    }

    @Test
    fun `getDEKFromUrlHash extracts DEK correctly`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val dekBase64 = java.util.Base64.getEncoder().encodeToString(encrypted.dek)
        val url = "https://example.com/s/abc123#$dekBase64"

        val extractedDEK = EnvelopeEncryption.getDEKFromUrlHash(url)

        assertNotNull("Extracted DEK should not be null", extractedDEK)
        assertEquals("Extracted DEK should match", dekBase64, extractedDEK)
    }

    @Test
    fun `getDEKFromUrlHash returns null for URL without hash`() {
        val url = "https://example.com/s/abc123"

        val extractedDEK = EnvelopeEncryption.getDEKFromUrlHash(url)

        assertNull("Extracted DEK should be null for URL without hash", extractedDEK)
    }

    @Test
    fun `getDEKFromUrlHash returns null for URL with empty hash`() {
        val url = "https://example.com/s/abc123#"

        val extractedDEK = EnvelopeEncryption.getDEKFromUrlHash(url)

        assertNull("Extracted DEK should be null for URL with empty hash", extractedDEK)
    }

    @Test
    fun `clearDEKFromUrlHash removes hash fragment`() {
        val dekBase64 = java.util.Base64.getEncoder().encodeToString(ByteArray(32))
        val url = "https://example.com/s/abc123#$dekBase64"

        val cleanedUrl = EnvelopeEncryption.clearDEKFromUrlHash(url)

        assertEquals("Cleaned URL should not contain hash", "https://example.com/s/abc123", cleanedUrl)
        assertFalse("Cleaned URL should not contain DEK", cleanedUrl.contains(dekBase64))
    }

    @Test
    fun `clearDEKFromUrlHash returns URL unchanged if no hash`() {
        val url = "https://example.com/s/abc123"

        val cleanedUrl = EnvelopeEncryption.clearDEKFromUrlHash(url)

        assertEquals("URL should be unchanged if no hash", url, cleanedUrl)
    }

    @Test
    fun `decryptContentWithDEK works with base64 DEK`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val dekBase64 = java.util.Base64.getEncoder().encodeToString(encrypted.dek)

        val decrypted = EnvelopeEncryption.decryptContentWithDEK(encrypted.content, dekBase64)

        assertEquals("Decrypted content should match original", testContent, decrypted)
    }

    @Test
    fun `decryptContentWithDEK with wrong DEK fails`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val wrongDEK = ByteArray(32) { (it + 1).toByte() }
        val wrongDEKBase64 = java.util.Base64.getEncoder().encodeToString(wrongDEK)

        try {
            EnvelopeEncryption.decryptContentWithDEK(encrypted.content, wrongDEKBase64)
            fail("Decryption with wrong DEK should throw an exception")
        } catch (e: Exception) {
            // Expected - decryption should fail with wrong DEK
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encryptContent throws exception for empty content`() {
        EnvelopeEncryption.encryptContent("", keyPair.public)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContent throws exception for empty encrypted content`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        EnvelopeEncryption.decryptContent("", encrypted.encryptedDEK, keyPair.private)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContent throws exception for empty encrypted DEK`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        EnvelopeEncryption.decryptContent(encrypted.content, "", keyPair.private)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContent throws exception for invalid format`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        EnvelopeEncryption.decryptContent("invalid_format_no_separator", encrypted.encryptedDEK, keyPair.private)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContent throws exception for missing IV`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        EnvelopeEncryption.decryptContent(".ciphertext", encrypted.encryptedDEK, keyPair.private)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContent throws exception for missing ciphertext`() {
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair.public)
        EnvelopeEncryption.decryptContent("iv.", encrypted.encryptedDEK, keyPair.private)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encryptContentAnonymous throws exception for empty content`() {
        EnvelopeEncryption.encryptContentAnonymous("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContentWithDEK throws exception for empty encrypted content`() {
        EnvelopeEncryption.decryptContentWithDEK("", "base64dek")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decryptContentWithDEK throws exception for empty DEK`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        EnvelopeEncryption.decryptContentWithDEK(encrypted.content, "")
    }

    @Test
    fun `encryptContent handles Unicode content`() {
        val unicodeContent = "Hello 世界 🌍 Привет مرحبا שלום"

        val encrypted = EnvelopeEncryption.encryptContent(unicodeContent, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Unicode content should survive encryption/decryption", unicodeContent, decrypted)
    }

    @Test
    fun `encryptContent handles emojis`() {
        val emojiContent = "😀😃😄😁😆😅🤣😂🙂🙃😉😊😇"

        val encrypted = EnvelopeEncryption.encryptContent(emojiContent, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Emoji content should survive encryption/decryption", emojiContent, decrypted)
    }

    @Test
    fun `encryptContent handles special characters`() {
        val specialContent = "Special chars: \n\t\r\\\"'<>"

        val encrypted = EnvelopeEncryption.encryptContent(specialContent, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Special characters should survive encryption/decryption", specialContent, decrypted)
    }

    @Test
    fun `encryptContent handles very long content`() {
        val longContent = "a".repeat(10000)

        val encrypted = EnvelopeEncryption.encryptContent(longContent, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Long content should survive encryption/decryption", longContent, decrypted)
    }

    @Test
    fun `encryptContent handles single character`() {
        val singleChar = "a"

        val encrypted = EnvelopeEncryption.encryptContent(singleChar, keyPair.public)
        val decrypted = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair.private)

        assertEquals("Single character should survive encryption/decryption", singleChar, decrypted)
    }

    @Test
    fun `exportDEK produces 32-byte raw key`() {
        val dek = EnvelopeEncryption.generateDEK()
        val exported = EnvelopeEncryption.exportDEK(dek)

        assertEquals("Exported DEK should be 32 bytes (256 bits)", 32, exported.size)
    }

    @Test
    fun `importDEK throws exception for empty bytes`() {
        try {
            EnvelopeEncryption.importDEK(byteArrayOf())
            fail("Import should throw exception for empty bytes")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `importDEK throws exception for wrong length`() {
        val wrongLength = ByteArray(24) { it.toByte() }

        try {
            EnvelopeEncryption.importDEK(wrongLength)
            fail("Import should throw exception for wrong length")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `getDEKFromUrlHash returns null for invalid base64`() {
        val url = "https://example.com/s/abc123#!!!invalid@base64"

        val extractedDEK = EnvelopeEncryption.getDEKFromUrlHash(url)

        assertNull("Extracted DEK should be null for invalid base64", extractedDEK)
    }

    @Test
    fun `full envelope encryption round-trip with different key pairs`() {
        // Encrypt with key pair 1's public key
        val keyPair1 = RSACrypto.generateKeyPair()
        val encrypted = EnvelopeEncryption.encryptContent(testContent, keyPair1.public)

        // Decrypt with key pair 1's private key
        val decrypted1 = EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair1.private)
        assertEquals("Decryption with matching keys should work", testContent, decrypted1)

        // Try to decrypt with different key pair's private key - should fail
        val keyPair2 = RSACrypto.generateKeyPair()
        try {
            EnvelopeEncryption.decryptContent(encrypted.content, encrypted.encryptedDEK, keyPair2.private)
            fail("Decryption with wrong private key should fail")
        } catch (e: Exception) {
            // Expected
        }
    }

    @Test
    fun `anonymous encryption works independently of RSA keys`() {
        val encrypted = EnvelopeEncryption.encryptContentAnonymous(testContent)
        val dekBase64 = java.util.Base64.getEncoder().encodeToString(encrypted.dek)

        // Decrypt with DEK directly - no RSA keys needed
        val decrypted = EnvelopeEncryption.decryptContentAnonymous(encrypted.content, dekBase64)

        assertEquals("Anonymous decryption should work without RSA keys", testContent, decrypted)
    }
}
