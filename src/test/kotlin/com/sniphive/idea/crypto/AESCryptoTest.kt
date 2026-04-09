package com.sniphive.idea.crypto

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import javax.crypto.SecretKey

/**
 * Unit tests for AES-256-GCM encryption.
 *
 * Tests cover:
 * - Key generation
 * - IV generation
 * - Encryption and decryption round-trip
 * - String encryption and decryption
 * - Key export and import
 * - Deterministic encryption with same IV
 * - Unique IV generation
 * - Wrong IV decryption fails
 * - Wrong key decryption fails
 * - Empty IV handling
 * - Empty data handling
 * - Invalid key length handling
 * - Authenticated encryption (tampering detection)
 */
class AESCryptoTest {

    private lateinit var testKey: SecretKey
    private lateinit var testPlaintext: ByteArray
    private lateinit var testPlaintextString: String

    @Before
    fun setUp() {
        testKey = AESCrypto.generateKey()
        testPlaintext = "This is a test message for AES encryption.".toByteArray()
        testPlaintextString = "This is a test string for AES encryption."
    }

    @Test
    fun `generateKey creates valid SecretKey`() {
        val key = AESCrypto.generateKey()

        assertNotNull("Generated key should not be null", key)
        assertEquals("Key algorithm should be AES", "AES", key.algorithm)
        assertEquals("Key format should be RAW", "RAW", key.format)
    }

    @Test
    fun `generateKey creates 256-bit key`() {
        val key = AESCrypto.generateKey()

        assertEquals("Generated key should be 256 bits (32 bytes)", 32, key.encoded.size)
    }

    @Test
    fun `generateKey produces unique keys`() {
        val key1 = AESCrypto.generateKey()
        val key2 = AESCrypto.generateKey()
        val key3 = AESCrypto.generateKey()

        assertNotEquals("Generated keys should be unique",
            key1.encoded.contentToString(), key2.encoded.contentToString())
        assertNotEquals("Generated keys should be unique",
            key2.encoded.contentToString(), key3.encoded.contentToString())
        assertNotEquals("Generated keys should be unique",
            key1.encoded.contentToString(), key3.encoded.contentToString())
    }

    @Test
    fun `generateIV produces 12-byte IV by default`() {
        val iv = AESCrypto.generateIV()

        assertEquals("Default IV length should be 12 bytes", 12, iv.size)
    }

    @Test
    fun `generateIV produces unique IVs`() {
        val iv1 = AESCrypto.generateIV()
        val iv2 = AESCrypto.generateIV()
        val iv3 = AESCrypto.generateIV()

        assertEquals("IV1 length should be 12 bytes", 12, iv1.size)
        assertEquals("IV2 length should be 12 bytes", 12, iv2.size)
        assertEquals("IV3 length should be 12 bytes", 12, iv3.size)

        assertNotEquals("IVs should be unique", iv1.contentToString(), iv2.contentToString())
        assertNotEquals("IVs should be unique", iv2.contentToString(), iv3.contentToString())
        assertNotEquals("IVs should be unique", iv1.contentToString(), iv3.contentToString())
    }

    @Test
    fun `generateIV with custom length produces correct length`() {
        val iv8 = AESCrypto.generateIV(8)
        val iv12 = AESCrypto.generateIV(12)
        val iv16 = AESCrypto.generateIV(16)

        assertEquals("Custom IV length should be 8 bytes", 8, iv8.size)
        assertEquals("Custom IV length should be 12 bytes", 12, iv12.size)
        assertEquals("Custom IV length should be 16 bytes", 16, iv16.size)
    }

    @Test
    fun `encrypt and decrypt produce original data`() {
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, iv)
        val decrypted = AESCrypto.decrypt(testKey, encrypted, iv)

        assertArrayEquals("Decrypted data should match original plaintext",
            testPlaintext, decrypted)
    }

    @Test
    fun `encrypt and decrypt produce original string`() {
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintextString, iv)
        val decrypted = AESCrypto.decryptToString(testKey, encrypted, iv)

        assertEquals("Decrypted string should match original plaintext",
            testPlaintextString, decrypted)
    }

    @Test
    fun `encrypt produces different ciphertext with different IVs`() {
        val iv1 = AESCrypto.generateIV()
        val iv2 = AESCrypto.generateIV()

        val encrypted1 = AESCrypto.encrypt(testKey, testPlaintext, iv1)
        val encrypted2 = AESCrypto.encrypt(testKey, testPlaintext, iv2)

        assertNotEquals("Ciphertext should differ with different IVs",
            encrypted1.contentToString(), encrypted2.contentToString())
    }

    @Test
    fun `encryption with same key and same IV produces same ciphertext`() {
        val iv = AESCrypto.generateIV()

        val encrypted1 = AESCrypto.encrypt(testKey, testPlaintext, iv)
        val encrypted2 = AESCrypto.encrypt(testKey, testPlaintext, iv)

        assertArrayEquals("Same key and IV should produce same ciphertext",
            encrypted1, encrypted2)
    }

    @Test
    fun `ciphertext includes authentication tag`() {
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, iv)

        // GCM adds a 128-bit (16-byte) authentication tag
        assertTrue("Ciphertext should be longer than plaintext (includes auth tag)",
            encrypted.size > testPlaintext.size)
    }

    @Test(expected = RuntimeException::class)
    fun `decrypt with wrong IV fails`() {
        val iv1 = AESCrypto.generateIV()
        val iv2 = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, iv1)

        // Try to decrypt with wrong IV - should fail
        AESCrypto.decrypt(testKey, encrypted, iv2)
    }

    @Test(expected = RuntimeException::class)
    fun `decrypt with wrong key fails`() {
        val key1 = AESCrypto.generateKey()
        val key2 = AESCrypto.generateKey()
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(key1, testPlaintext, iv)

        // Try to decrypt with wrong key - should fail
        AESCrypto.decrypt(key2, encrypted, iv)
    }

    @Test(expected = RuntimeException::class)
    fun `decrypt with tampered ciphertext fails authentication`() {
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, iv)

        // Tamper with the ciphertext
        val tampered = encrypted.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0xFF).toByte()

        // Decryption should fail due to authentication tag mismatch
        AESCrypto.decrypt(testKey, tampered, iv)
    }

    @Test(expected = RuntimeException::class)
    fun `decrypt with tampered IV fails authentication`() {
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, iv)

        // Tamper with the IV
        val tamperedIV = iv.copyOf()
        tamperedIV[0] = (tamperedIV[0].toInt() xor 0xFF).toByte()

        // Decryption should fail due to authentication tag mismatch
        AESCrypto.decrypt(testKey, encrypted, tamperedIV)
    }

    @Test
    fun `encryptDEK and decryptDEK are aliases for encrypt and decrypt`() {
        val iv = AESCrypto.generateIV()
        val dek = AESCrypto.generateKey().encoded

        val encrypted = AESCrypto.encryptDEK(dek, testKey, iv)
        val decrypted = AESCrypto.decryptDEK(encrypted, testKey, iv)

        assertArrayEquals("DEK round-trip should produce original data",
            dek, decrypted)
    }

    @Test
    fun `encryptDEK handles 32-byte AES-256 key`() {
        val iv = AESCrypto.generateIV()
        val aes256Key = ByteArray(32) { it.toByte() }

        val encrypted = AESCrypto.encryptDEK(aes256Key, testKey, iv)
        val decrypted = AESCrypto.decryptDEK(encrypted, testKey, iv)

        assertArrayEquals("AES-256 key round-trip should succeed",
            aes256Key, decrypted)
    }

    @Test
    fun `exportKey produces raw key bytes`() {
        val key = AESCrypto.generateKey()
        val exported = AESCrypto.exportKey(key)

        assertNotNull("Exported key should not be null", exported)
        assertEquals("Exported key should be 32 bytes (256 bits)", 32, exported.size)
    }

    @Test
    fun `importKey recreates usable key`() {
        val originalKey = AESCrypto.generateKey()
        val exported = AESCrypto.exportKey(originalKey)
        val importedKey = AESCrypto.importKey(exported)

        assertArrayEquals("Imported key should match original key",
            originalKey.encoded, importedKey.encoded)

        // Test that the imported key works for encryption/decryption
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(importedKey, testPlaintext, iv)
        val decrypted = AESCrypto.decrypt(importedKey, encrypted, iv)

        assertArrayEquals("Encryption with imported key should work",
            testPlaintext, decrypted)
    }

    @Test
    fun `importKey and exportKey round-trip produces working key`() {
        val originalKey = AESCrypto.generateKey()
        val exported = AESCrypto.exportKey(originalKey)
        val importedKey = AESCrypto.importKey(exported)

        // Use original key for encryption, imported key for decryption
        val iv = AESCrypto.generateIV()
        val encrypted = AESCrypto.encrypt(originalKey, testPlaintext, iv)
        val decrypted = AESCrypto.decrypt(importedKey, encrypted, iv)

        assertArrayEquals("Round-trip key should produce original data",
            testPlaintext, decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for empty plaintext`() {
        val iv = AESCrypto.generateIV()
        AESCrypto.encrypt(testKey, byteArrayOf(), iv)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for empty string`() {
        val iv = AESCrypto.generateIV()
        AESCrypto.encrypt(testKey, "", iv)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for empty IV`() {
        AESCrypto.encrypt(testKey, testPlaintext, byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for too short IV`() {
        AESCrypto.encrypt(testKey, testPlaintext, byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws exception for empty ciphertext`() {
        val iv = AESCrypto.generateIV()
        AESCrypto.decrypt(testKey, byteArrayOf(), iv)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws exception for empty IV`() {
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, AESCrypto.generateIV())
        AESCrypto.decrypt(testKey, encrypted, byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws exception for too short IV`() {
        val encrypted = AESCrypto.encrypt(testKey, testPlaintext, AESCrypto.generateIV())
        AESCrypto.decrypt(testKey, encrypted, byteArrayOf(1, 2, 3, 4))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `generateIV throws exception for length less than 8`() {
        AESCrypto.generateIV(4)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importKey throws exception for empty key bytes`() {
        AESCrypto.importKey(byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importKey throws exception for wrong key length`() {
        // AES-256 requires 32 bytes
        val wrongLength = ByteArray(24) { it.toByte() }
        AESCrypto.importKey(wrongLength)
    }

    @Test
    fun `AES key length constant is 256 bits`() {
        assertEquals("AES key length should be 256 bits",
            256, AESCrypto.AES_KEY_LENGTH)
    }

    @Test
    fun `AES IV length constant is 12 bytes`() {
        assertEquals("AES IV length should be 12 bytes (96 bits)",
            12, AESCrypto.AES_IV_LENGTH)
    }

    @Test
    fun `GCM tag length constant is 128 bits`() {
        assertEquals("GCM tag length should be 128 bits",
            128, AESCrypto.GCM_TAG_LENGTH)
    }

    @Test
    fun `key algorithm constant is AES`() {
        assertEquals("Key algorithm should be AES",
            "AES", AESCrypto.KEY_ALGORITHM)
    }

    @Test
    fun `transformation constant uses GCM with no padding`() {
        assertEquals("Transformation should use GCM with no padding",
            "AES/GCM/NoPadding", AESCrypto.TRANSFORMATION)
    }

    @Test
    fun `encrypt handles large data`() {
        val largeData = ByteArray(10000) { it.toByte() }
        val iv = AESCrypto.generateIV()

        val encrypted = AESCrypto.encrypt(testKey, largeData, iv)
        val decrypted = AESCrypto.decrypt(testKey, encrypted, iv)

        assertArrayEquals("Large data round-trip should succeed",
            largeData, decrypted)
    }

    @Test
    fun `encrypt handles empty string message is valid error`() {
        val iv = AESCrypto.generateIV()

        try {
            AESCrypto.encrypt(testKey, "", iv)
            fail("Empty string should throw exception")
        } catch (e: IllegalArgumentException) {
            assertEquals("Exception message should mention empty plaintext",
                "Plaintext cannot be empty", e.message)
        }
    }

    @Test
    fun `encrypt handles special characters`() {
        val specialChars = "Hello 世界 🌍\n\t\r特殊字符"
        val iv = AESCrypto.generateIV()

        val encrypted = AESCrypto.encrypt(testKey, specialChars, iv)
        val decrypted = AESCrypto.decryptToString(testKey, encrypted, iv)

        assertEquals("Special characters should survive encryption/decryption",
            specialChars, decrypted)
    }

    @Test
    fun `encrypt handles Unicode emojis`() {
        val emojis = "😀😃😄😁😆😅🤣😂🙂🙃😉😊😇"
        val iv = AESCrypto.generateIV()

        val encrypted = AESCrypto.encrypt(testKey, emojis, iv)
        val decrypted = AESCrypto.decryptToString(testKey, encrypted, iv)

        assertEquals("Unicode emojis should survive encryption/decryption",
            emojis, decrypted)
    }
}
