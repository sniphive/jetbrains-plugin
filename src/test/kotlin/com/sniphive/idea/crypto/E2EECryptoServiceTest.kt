package com.sniphive.idea.crypto

import com.google.gson.JsonObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.security.PrivateKey
import java.util.Base64

/**
 * Unit tests for E2EE Crypto Service.
 *
 * Tests cover:
 * - E2EE setup with master password and recovery code
 * - Unlocking private key with master password
 * - Recovering access with recovery code
 * - Recovery code generation and parsing
 * - Base64 conversion utilities
 * - Random bytes generation
 * - Input validation
 * - Error handling
 */
class E2EECryptoServiceTest {

    private lateinit var masterPassword: String

    @Before
    fun setUp() {
        masterPassword = "SecureMasterPassword123!"
    }

    // ==========================================
    // E2EE SETUP TESTS
    // ==========================================

    @Test
    fun `setupE2EE creates valid E2EESetupResult`() {
        val result = E2EECryptoService.setupE2EE(masterPassword)

        assertNotNull("Setup result should not be null", result)
        assertNotNull("Public key JWK should not be null", result.publicKeyJWK)
        assertNotNull("Encrypted private key should not be null", result.encryptedPrivateKey)
        assertNotNull("Recovery encrypted private key should not be null", result.recoveryEncryptedPrivateKey)
        assertNotNull("Private key IV should not be null", result.privateKeyIV)
        assertNotNull("Recovery IV should not be null", result.recoveryIV)
        assertNotNull("KDF salt should not be null", result.kdfSalt)
        assertNotNull("Recovery salt should not be null", result.recoverySalt)
        assertNotNull("KDF iterations should not be null", result.kdfIterations)
        assertNotNull("Recovery code should not be null", result.recoveryCode)
    }

    @Test
    fun `setupE2EE generates valid RSA-4096 public key JWK`() {
        val result = E2EECryptoService.setupE2EE(masterPassword)

        assertEquals("JWK kty should be RSA", "RSA", result.publicKeyJWK.get("kty").asString)
        assertEquals("JWK alg should be RSA-OAEP-256", "RSA-OAEP-256", result.publicKeyJWK.get("alg").asString)
        assertNotNull("JWK should have modulus (n)", result.publicKeyJWK.get("n"))
        assertNotNull("JWK should have public exponent (e)", result.publicKeyJWK.get("e"))
    }

    @Test
    fun `setupE2EE uses OWASP-recommended PBKDF2 iterations`() {
        val result = E2EECryptoService.setupE2EE(masterPassword)

        assertEquals("KDF iterations should be 600,000 (OWASP minimum)",
            600000, result.kdfIterations)
    }

    @Test
    fun `setupE2EE generates valid recovery code format`() {
        val result = E2EECryptoService.setupE2EE(masterPassword)

        // Format: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX (24 characters with 5 dashes)
        assertTrue("Recovery code should contain dashes", result.recoveryCode.contains("-"))
        val parts = result.recoveryCode.split("-")
        assertEquals("Recovery code should have 6 parts", 6, parts.size)
        parts.forEach { assertEquals("Each part should be 4 characters", 4, it.length) }
    }

    @Test
    fun `setupE2EE generates unique recovery codes`() {
        val code1 = E2EECryptoService.setupE2EE(masterPassword).recoveryCode
        val code2 = E2EECryptoService.setupE2EE(masterPassword).recoveryCode
        val code3 = E2EECryptoService.setupE2EE(masterPassword).recoveryCode

        assertNotEquals("Recovery codes should be unique", code1, code2)
        assertNotEquals("Recovery codes should be unique", code2, code3)
        assertNotEquals("Recovery codes should be unique", code1, code3)
    }

    @Test
    fun `setupE2EE encodes encrypted data in Base64`() {
        val result = E2EECryptoService.setupE2EE(masterPassword)

        // All these should be valid Base64 strings
        val encryptedPrivateKeyBytes = Base64.getDecoder().decode(result.encryptedPrivateKey)
        val recoveryEncryptedPrivateKeyBytes = Base64.getDecoder().decode(result.recoveryEncryptedPrivateKey)
        val privateKeyIVBytes = Base64.getDecoder().decode(result.privateKeyIV)
        val recoveryIVBytes = Base64.getDecoder().decode(result.recoveryIV)
        val kdfSaltBytes = Base64.getDecoder().decode(result.kdfSalt)
        val recoverySaltBytes = Base64.getDecoder().decode(result.recoverySalt)

        assertTrue("Encrypted private key should have data", encryptedPrivateKeyBytes.isNotEmpty())
        assertTrue("Recovery encrypted private key should have data", recoveryEncryptedPrivateKeyBytes.isNotEmpty())
        assertTrue("Private key IV should be 12 bytes", privateKeyIVBytes.size == 12)
        assertTrue("Recovery IV should be 12 bytes", recoveryIVBytes.size == 12)
        assertTrue("KDF salt should be 16 bytes", kdfSaltBytes.size == 16)
        assertTrue("Recovery salt should be 16 bytes", recoverySaltBytes.size == 16)
    }

    @Test
    fun `setupE2EE generates different results for same password`() {
        val result1 = E2EECryptoService.setupE2EE(masterPassword)
        val result2 = E2EECryptoService.setupE2EE(masterPassword)

        assertNotEquals("Public keys should differ (different key pairs)",
            result1.publicKeyJWK.toString(), result2.publicKeyJWK.toString())
        assertNotEquals("Encrypted private keys should differ",
            result1.encryptedPrivateKey, result2.encryptedPrivateKey)
        assertNotEquals("Recovery encrypted private keys should differ",
            result1.recoveryEncryptedPrivateKey, result2.recoveryEncryptedPrivateKey)
        assertNotEquals("IVs should differ (randomly generated)",
            result1.privateKeyIV, result2.privateKeyIV)
        assertNotEquals("Salts should differ (randomly generated)",
            result1.kdfSalt, result2.kdfSalt)
        assertNotEquals("Recovery codes should differ",
            result1.recoveryCode, result2.recoveryCode)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `setupE2EE throws exception for empty master password`() {
        E2EECryptoService.setupE2EE("")
    }

    @Test
    fun `setupE2EE works with unicode password`() {
        val unicodePassword = "パスワード🔐Парольكلمةالمرور"

        val result = E2EECryptoService.setupE2EE(unicodePassword)

        assertNotNull("Setup should work with unicode password", result)
        assertNotNull("Recovery code should be generated", result.recoveryCode)
    }

    @Test
    fun `setupE2EE works with very long password`() {
        val longPassword = "a".repeat(1000)

        val result = E2EECryptoService.setupE2EE(longPassword)

        assertNotNull("Setup should work with long password", result)
        assertNotNull("Recovery code should be generated", result.recoveryCode)
    }

    // ==========================================
    // UNLOCK WITH MASTER PASSWORD TESTS
    // ==========================================

    @Test
    fun `unlockWithMasterPassword decrypts private key successfully`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        val privateKey = E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)

        assertNotNull("Private key should be decrypted", privateKey)
        assertEquals("Private key algorithm should be RSA", "RSA", privateKey.algorithm)
    }

    @Test
    fun `unlockWithMasterPassword round-trip works correctly`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        val privateKey = E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
        val publicKey = RSACrypto.importPublicKeyFromJWK(setupResult.publicKeyJWK)

        // Test that the private key works with the public key
        val testMessage = "Test message for E2EE round-trip"
        val encrypted = RSACrypto.encrypt(publicKey, testMessage.toByteArray())
        val decrypted = RSACrypto.decrypt(privateKey, encrypted)

        assertArrayEquals("Round-trip encryption/decryption should work",
            testMessage.toByteArray(), decrypted)
    }

    @Test(expected = RuntimeException::class)
    fun `unlockWithMasterPassword fails with wrong password`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Try to unlock with wrong password - should fail
        E2EECryptoService.unlockWithMasterPassword("WrongPassword123!", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithMasterPassword throws exception for missing kdf_salt`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            encryptedPrivateKey = "encrypted",
            privateKeyIV = "iv",
            kdfSalt = null,
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithMasterPassword throws exception for missing private_key_iv`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            encryptedPrivateKey = "encrypted",
            kdfSalt = "salt",
            privateKeyIV = null,
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithMasterPassword throws exception for missing encrypted_private_key`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            kdfSalt = "salt",
            privateKeyIV = "iv",
            encryptedPrivateKey = null,
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithMasterPassword throws exception for missing kdf_iterations`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            encryptedPrivateKey = "encrypted",
            kdfSalt = "salt",
            privateKeyIV = "iv",
            kdfIterations = null
        )

        E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
    }

    // ==========================================
    // UNLOCK WITH RECOVERY CODE TESTS
    // ==========================================

    @Test
    fun `unlockWithRecoveryCode decrypts private key successfully`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        val privateKey = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)

        assertNotNull("Private key should be decrypted", privateKey)
        assertEquals("Private key algorithm should be RSA", "RSA", privateKey.algorithm)
    }

    @Test
    fun `unlockWithRecoveryCode round-trip works correctly`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        val privateKey = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)
        val publicKey = RSACrypto.importPublicKeyFromJWK(setupResult.publicKeyJWK)

        // Test that the private key works with the public key
        val testMessage = "Test message for recovery round-trip"
        val encrypted = RSACrypto.encrypt(publicKey, testMessage.toByteArray())
        val decrypted = RSACrypto.decrypt(privateKey, encrypted)

        assertArrayEquals("Round-trip encryption/decryption should work",
            testMessage.toByteArray(), decrypted)
    }

    @Test(expected = RuntimeException::class)
    fun `unlockWithRecoveryCode fails with wrong code`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Generate a different recovery code
        val wrongCode = E2EECryptoService.setupE2EE(masterPassword).recoveryCode

        // Try to unlock with wrong code - should fail
        E2EECryptoService.unlockWithRecoveryCode(wrongCode, profile)
    }

    @Test
    fun `unlockWithRecoveryCode works with formatted code`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Test with formatted code (XXXX-XXXX-XXXX-XXXX-XXXX-XXXX)
        val privateKey = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)

        assertNotNull("Private key should be decrypted with formatted code", privateKey)
    }

    @Test
    fun `unlockWithRecoveryCode works with unformatted code`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Test with unformatted code (no dashes)
        val unformattedCode = setupResult.recoveryCode.replace("-", "")
        val privateKey = E2EECryptoService.unlockWithRecoveryCode(unformattedCode, profile)

        assertNotNull("Private key should be decrypted with unformatted code", privateKey)
    }

    @Test
    fun `unlockWithRecoveryCode falls back to kdf_salt when recovery_salt is missing`() {
        // This test verifies the fallback mechanism for legacy profiles that don't have recovery_salt.
        // For this to work, we need to simulate a legacy profile where recovery was done with kdf_salt.
        // Since setupE2EE always creates recovery_salt, we manually create a profile with recovery_salt=null
        // and derive the recovery key with kdf_salt.
        
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        
        // Manually derive recovery key with kdf_salt (simulating legacy behavior)
        val parsedRecoveryCode = E2EECryptoService.parseRecoveryCode(setupResult.recoveryCode)
        val kdfSalt = E2EECryptoService.base64ToArrayBuffer(setupResult.kdfSalt)
        val recoveryKey = PBKDF2.deriveKey(parsedRecoveryCode, kdfSalt, setupResult.kdfIterations)
        
        // Re-encrypt the private key with the recovery key derived from kdf_salt
        val privateKeyJWK = RSACrypto.exportPrivateKeyToJWK(
            RSACrypto.importPrivateKeyFromJWK(
                com.google.gson.JsonParser.parseString(
                    String(AESCrypto.decrypt(
                        PBKDF2.deriveKey(masterPassword, kdfSalt, setupResult.kdfIterations),
                        E2EECryptoService.base64ToArrayBuffer(setupResult.encryptedPrivateKey),
                        E2EECryptoService.base64ToArrayBuffer(setupResult.privateKeyIV)
                    ))
                ).asJsonObject
            )
        )
        val privateKeyBytes = privateKeyJWK.toString().toByteArray(Charsets.UTF_8)
        val recoveryIV = AESCrypto.generateIV()
        val recoveryEncryptedPrivateKey = AESCrypto.encrypt(recoveryKey, privateKeyBytes, recoveryIV)
        
        // Create a legacy profile with recovery_salt = null
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = E2EECryptoService.arrayBufferToBase64(recoveryEncryptedPrivateKey),
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = E2EECryptoService.arrayBufferToBase64(recoveryIV),
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = null, // Missing recovery salt (legacy profile)
            kdfIterations = setupResult.kdfIterations
        )

        val privateKey = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)

        assertNotNull("Private key should be decrypted with fallback to kdf_salt", privateKey)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for missing kdf_salt`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = "encrypted",
            recoveryIV = "iv",
            kdfSalt = null,
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234-EFGH-5678", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for missing kdf_iterations`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = "encrypted",
            recoveryIV = "iv",
            kdfSalt = "salt",
            kdfIterations = null
        )

        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234-EFGH-5678", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for missing recovery_iv`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = "encrypted",
            recoveryIV = null,
            kdfSalt = "salt",
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234-EFGH-5678", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for missing recovery_encrypted_private_key`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = null,
            recoveryIV = "iv",
            kdfSalt = "salt",
            kdfIterations = 600000
        )

        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234-EFGH-5678", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for invalid recovery code length`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = "encrypted",
            recoveryIV = "iv",
            kdfSalt = "salt",
            kdfIterations = 600000
        )

        // Invalid code length
        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234", profile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unlockWithRecoveryCode throws exception for invalid recovery code characters`() {
        val profile = E2EEProfile(
            publicKeyJWK = JsonObject(),
            recoveryEncryptedPrivateKey = "encrypted",
            recoveryIV = "iv",
            kdfSalt = "salt",
            kdfIterations = 600000
        )

        // Invalid characters (includes I, O, 0, 1 which are excluded)
        E2EECryptoService.unlockWithRecoveryCode("ABCD-1234-EFGH-5678-IJKL-9012", profile)
    }

    // ==========================================
    // RECOVERY CODE GENERATION TESTS
    // ==========================================

    @Test
    fun `generateRecoveryCode produces correct format`() {
        val code = E2EECryptoService.generateRecoveryCode()

        // Format: XXXX-XXXX-XXXX-XXXX-XXXX-XXXX
        assertEquals("Code should have 5 dashes", 5, code.count { it == '-' })
        val parts = code.split("-")
        assertEquals("Code should have 6 parts", 6, parts.size)
        parts.forEach { assertEquals("Each part should be 4 characters", 4, it.length) }
    }

    @Test
    fun `generateRecoveryCode excludes confusing characters`() {
        val code = E2EECryptoService.generateRecoveryCode()
        val cleaned = code.replace("-", "")

        // Should not contain I, O, 0, 1
        assertFalse("Code should not contain 'I'", cleaned.contains('I'))
        assertFalse("Code should not contain 'O'", cleaned.contains('O'))
        assertFalse("Code should not contain '0'", cleaned.contains('0'))
        assertFalse("Code should not contain '1'", cleaned.contains('1'))

        // All characters should be from the allowed set
        val allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        cleaned.forEach { char ->
            assertTrue("Character '$char' should be in allowed set", allowedChars.contains(char))
        }
    }

    @Test
    fun `generateRecoveryCode produces unique codes`() {
        val codes = (1..100).map { E2EECryptoService.generateRecoveryCode() }
        val uniqueCodes = codes.toSet()

        assertEquals("100 generated codes should be unique", 100, uniqueCodes.size)
    }

    // ==========================================
    // RECOVERY CODE PARSING TESTS
    // ==========================================

    @Test
    fun `parseRecoveryCode removes dashes and converts to uppercase`() {
        // Use valid characters only (ABCDEFGHJKLMNPQRSTUVWXYZ23456789 - excludes I, O, 0, 1)
        val formatted = "ABCD-EFGH-2345-6789-WXYZ-MNPQ"
        val parsed = E2EECryptoService.parseRecoveryCode(formatted)

        assertEquals("Dashes should be removed", false, parsed.contains("-"))
        assertEquals("Should be uppercase", true, parsed == parsed.uppercase())
        assertEquals("Should have correct length without dashes", 24, parsed.length)
    }

    @Test
    fun `parseRecoveryCode handles lowercase input`() {
        // Use valid characters only (ABCDEFGHJKLMNPQRSTUVWXYZ23456789 - excludes I, O, 0, 1)
        val lowercase = "abcd-efgh-2345-6789-wxyz-mnpq"
        val parsed = E2EECryptoService.parseRecoveryCode(lowercase)

        assertEquals("Should be uppercase", true, parsed == parsed.uppercase())
        assertEquals("Should have correct length without dashes", 24, parsed.length)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRecoveryCode throws exception for wrong length`() {
        val wrongLength = "ABCD-EFGH-1234"
        E2EECryptoService.parseRecoveryCode(wrongLength)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRecoveryCode throws exception for excluded characters`() {
        val invalidChars = "ABCD-EFGH-1234-IJKL-MNOP-9012" // Contains I, O, 0, 1
        E2EECryptoService.parseRecoveryCode(invalidChars)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseRecoveryCode throws exception for invalid characters`() {
        val invalidChars = "ABCD-EFGH-1234-!@#$-WXYZ-9876" // Contains special chars
        E2EECryptoService.parseRecoveryCode(invalidChars)
    }

    // ==========================================
    // BASE64 CONVERSION TESTS
    // ==========================================

    @Test
    fun `arrayBufferToBase64 encodes bytes correctly`() {
        val bytes = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte())
        val base64 = E2EECryptoService.arrayBufferToBase64(bytes)

        val decoded = Base64.getDecoder().decode(base64)
        assertArrayEquals("Decoded bytes should match original", bytes, decoded)
    }

    @Test
    fun `arrayBufferToBase64 handles empty bytes`() {
        val bytes = byteArrayOf()
        val base64 = E2EECryptoService.arrayBufferToBase64(bytes)

        assertEquals("Empty bytes should encode to empty string", "", base64)
    }

    @Test
    fun `arrayBufferToBase64 handles random bytes`() {
        val bytes = E2EECryptoService.generateRandomBytes(32)
        val base64 = E2EECryptoService.arrayBufferToBase64(bytes)

        val decoded = Base64.getDecoder().decode(base64)
        assertArrayEquals("Random bytes should round-trip", bytes, decoded)
    }

    @Test
    fun `base64ToArrayBuffer decodes standard Base64 correctly`() {
        val base64 = "SGVsbG8gV29ybGQ=" // "Hello World"
        val decoded = E2EECryptoService.base64ToArrayBuffer(base64)

        val expected = "Hello World".toByteArray(Charsets.UTF_8)
        assertArrayEquals("Decoded bytes should match expected", expected, decoded)
    }

    @Test
    fun `base64ToArrayBuffer handles URL-safe Base64`() {
        // Use bytes that produce + in standard Base64 (which becomes - in URL-safe)
        // Bytes [0xFB] encode to "+" in standard Base64, "-" in URL-safe
        val testBytes = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        val standardBase64 = Base64.getEncoder().encodeToString(testBytes) // "+/"
        val urlSafeBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(testBytes) // "-_"
        
        val decoded = E2EECryptoService.base64ToArrayBuffer(urlSafeBase64)
        
        assertArrayEquals("URL-safe Base64 should be decoded correctly", testBytes, decoded)
    }

    @Test
    fun `base64ToArrayBuffer handles underscores`() {
        // Use bytes that produce / in standard Base64 (which becomes _ in URL-safe)
        val testBytes = byteArrayOf(0xFF.toByte())
        val urlSafeBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(testBytes) // "_"
        
        val decoded = E2EECryptoService.base64ToArrayBuffer(urlSafeBase64)
        
        assertArrayEquals("URL-safe Base64 with underscores should be decoded", testBytes, decoded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `base64ToArrayBuffer throws exception for empty input`() {
        E2EECryptoService.base64ToArrayBuffer("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `base64ToArrayBuffer throws exception for invalid Base64`() {
        E2EECryptoService.base64ToArrayBuffer("!!!Invalid!!!Base64!!!")
    }

    // ==========================================
    // RANDOM BYTES GENERATION TESTS
    // ==========================================

    @Test
    fun `generateRandomBytes produces correct length`() {
        val bytes16 = E2EECryptoService.generateRandomBytes(16)
        val bytes32 = E2EECryptoService.generateRandomBytes(32)
        val bytes64 = E2EECryptoService.generateRandomBytes(64)

        assertEquals("Should generate 16 bytes", 16, bytes16.size)
        assertEquals("Should generate 32 bytes", 32, bytes32.size)
        assertEquals("Should generate 64 bytes", 64, bytes64.size)
    }

    @Test
    fun `generateRandomBytes produces unique values`() {
        val bytes1 = E2EECryptoService.generateRandomBytes(32)
        val bytes2 = E2EECryptoService.generateRandomBytes(32)
        val bytes3 = E2EECryptoService.generateRandomBytes(32)

        assertNotEquals("Random bytes should be unique",
            bytes1.contentToString(), bytes2.contentToString())
        assertNotEquals("Random bytes should be unique",
            bytes2.contentToString(), bytes3.contentToString())
        assertNotEquals("Random bytes should be unique",
            bytes1.contentToString(), bytes3.contentToString())
    }

    @Test
    fun `generateRandomBytes produces non-zero bytes`() {
        val bytes = E2EECryptoService.generateRandomBytes(1000)

        // With 1000 random bytes, at least one should be non-zero
        val hasNonZero = bytes.any { it != 0.toByte() }
        assertTrue("Random bytes should contain non-zero values", hasNonZero)
    }

    // ==========================================
    // INTEGRATION TESTS
    // ==========================================

    @Test
    fun `full E2EE setup and unlock with master password integration`() {
        // Setup
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Unlock with master password
        val privateKey = E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)
        val publicKey = RSACrypto.importPublicKeyFromJWK(setupResult.publicKeyJWK)

        // Test encryption/decryption with the key pair
        val testMessage = "Integration test message"
        val encrypted = RSACrypto.encrypt(publicKey, testMessage.toByteArray())
        val decrypted = RSACrypto.decrypt(privateKey, encrypted)

        assertArrayEquals("Full integration should work",
            testMessage.toByteArray(), decrypted)
    }

    @Test
    fun `full E2EE setup and unlock with recovery code integration`() {
        // Setup
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Unlock with recovery code
        val privateKey = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)
        val publicKey = RSACrypto.importPublicKeyFromJWK(setupResult.publicKeyJWK)

        // Test encryption/decryption with the key pair
        val testMessage = "Integration test with recovery code"
        val encrypted = RSACrypto.encrypt(publicKey, testMessage.toByteArray())
        val decrypted = RSACrypto.decrypt(privateKey, encrypted)

        assertArrayEquals("Full integration with recovery should work",
            testMessage.toByteArray(), decrypted)
    }

    @Test
    fun `master password and recovery code unlock produce same private key`() {
        val setupResult = E2EECryptoService.setupE2EE(masterPassword)
        val profile = E2EEProfile(
            publicKeyJWK = setupResult.publicKeyJWK,
            encryptedPrivateKey = setupResult.encryptedPrivateKey,
            recoveryEncryptedPrivateKey = setupResult.recoveryEncryptedPrivateKey,
            privateKeyIV = setupResult.privateKeyIV,
            recoveryIV = setupResult.recoveryIV,
            kdfSalt = setupResult.kdfSalt,
            recoverySalt = setupResult.recoverySalt,
            kdfIterations = setupResult.kdfIterations
        )

        // Unlock with master password
        val privateKeyFromMaster = E2EECryptoService.unlockWithMasterPassword(masterPassword, profile)

        // Unlock with recovery code
        val privateKeyFromRecovery = E2EECryptoService.unlockWithRecoveryCode(setupResult.recoveryCode, profile)

        // Both should produce the same private key
        assertEquals("Both unlock methods should produce same key algorithm",
            privateKeyFromMaster.algorithm, privateKeyFromRecovery.algorithm)

        val rsaMaster = privateKeyFromMaster as java.security.interfaces.RSAPrivateKey
        val rsaRecovery = privateKeyFromRecovery as java.security.interfaces.RSAPrivateKey

        assertEquals("Both unlock methods should produce same private exponent",
            rsaMaster.privateExponent, rsaRecovery.privateExponent)
    }

    // ==========================================
    // CONSTANTS TESTS
    // ==========================================

    @Test
    fun `recovery code length constant is 24 characters`() {
        assertEquals("Recovery code length should be 24 characters",
            24, E2EECryptoService.RECOVERY_CODE_LENGTH)
    }

    @Test
    fun `recovery code characters exclude confusing ones`() {
        val chars = E2EECryptoService.RECOVERY_CODE_CHARACTERS

        assertFalse("Should not contain 'I'", chars.contains('I'))
        assertFalse("Should not contain 'O'", chars.contains('O'))
        assertFalse("Should not contain '0'", chars.contains('0'))
        assertFalse("Should not contain '1'", chars.contains('1'))

        // Should contain 32 characters (26 uppercase letters - 2 confusing + 8 digits - 2 confusing)
        assertEquals("Should have 32 allowed characters", 32, chars.length)
    }
}
