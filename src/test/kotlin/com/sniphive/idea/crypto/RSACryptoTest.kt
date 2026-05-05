package com.sniphive.idea.crypto

import com.google.gson.JsonObject
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import java.security.KeyPair

/**
 * Unit tests for RSA-4096 OAEP encryption.
 *
 * Tests cover:
 * - Key pair generation
 * - RSA-OAEP encryption and decryption
 * - JWK import/export for public and private keys
 * - JWK JSON string import/export
 * - Round-trip encryption/decryption
 * - Maximum data size validation
 * - Input validation (empty data, null inputs)
 */
class RSACryptoTest {

    private lateinit var keyPair: KeyPair
    private lateinit var testPlaintext: ByteArray

    @Before
    fun setUp() {
        keyPair = RSACrypto.generateKeyPair()
        testPlaintext = "This is a test message for RSA encryption.".toByteArray()
    }

    @Test
    fun `generateKeyPair creates valid RSA key pair`() {
        assertNotNull("Key pair should not be null", keyPair)
        assertNotNull("Public key should not be null", keyPair.public)
        assertNotNull("Private key should not be null", keyPair.private)
        assertEquals("Key algorithm should be RSA", "RSA", keyPair.public.algorithm)
        assertEquals("Key algorithm should be RSA", "RSA", keyPair.private.algorithm)
    }

    @Test
    fun `generateKeyPair creates 4096-bit keys`() {
        val rsaPublicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val rsaPrivateKey = keyPair.private as java.security.interfaces.RSAPrivateKey

        assertEquals("Public key modulus should be 4096 bits",
            RSACrypto.RSA_MODULUS_LENGTH, rsaPublicKey.modulus.bitLength())
        assertEquals("Private key modulus should be 4096 bits",
            RSACrypto.RSA_MODULUS_LENGTH, rsaPrivateKey.modulus.bitLength())
    }

    @Test
    fun `generateKeyPair uses standard public exponent`() {
        val rsaPublicKey = keyPair.public as java.security.interfaces.RSAPublicKey
        assertEquals("Public exponent should be 65537 (F4)",
            RSACrypto.PUBLIC_EXPONENT, rsaPublicKey.publicExponent.toInt())
    }

    @Test
    fun `encrypt and decrypt produce original data`() {
        val encrypted = RSACrypto.encrypt(keyPair.public, testPlaintext)
        val decrypted = RSACrypto.decrypt(keyPair.private, encrypted)

        assertArrayEquals("Decrypted data should match original plaintext",
            testPlaintext, decrypted)
    }

    @Test
    fun `encryptDEK and decryptDEK are aliases for encrypt and decrypt`() {
        val encryptedDEK = RSACrypto.encryptDEK(keyPair.public, testPlaintext)
        val decryptedDEK = RSACrypto.decryptDEK(keyPair.private, encryptedDEK)

        assertArrayEquals("DEK round-trip should produce original data",
            testPlaintext, decryptedDEK)
    }

    @Test
    fun `encrypt produces different ciphertext for same plaintext`() {
        val encrypted1 = RSACrypto.encrypt(keyPair.public, testPlaintext)
        val encrypted2 = RSACrypto.encrypt(keyPair.public, testPlaintext)

        assertNotEquals("Ciphertext should differ due to random padding",
            encrypted1.contentToString(), encrypted2.contentToString())
    }

    @Test
    fun `encrypt can handle maximum data size`() {
        val maxSize = RSACrypto.MAX_OAEP_SHA256_DATA_SIZE
        val largeData = ByteArray(maxSize) { it.toByte() }

        val encrypted = RSACrypto.encrypt(keyPair.public, largeData)
        val decrypted = RSACrypto.decrypt(keyPair.private, encrypted)

        assertArrayEquals("Large data round-trip should succeed",
            largeData, decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for empty data`() {
        RSACrypto.encrypt(keyPair.public, byteArrayOf())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encrypt throws exception for data exceeding maximum size`() {
        val tooLargeData = ByteArray(RSACrypto.MAX_OAEP_SHA256_DATA_SIZE + 1) { it.toByte() }
        RSACrypto.encrypt(keyPair.public, tooLargeData)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decrypt throws exception for empty encrypted data`() {
        RSACrypto.decrypt(keyPair.private, byteArrayOf())
    }

    @Test
    fun `encryptDEK handles 32-byte AES-256 key`() {
        val aes256Key = ByteArray(32) { it.toByte() }

        val encrypted = RSACrypto.encryptDEK(keyPair.public, aes256Key)
        val decrypted = RSACrypto.decryptDEK(keyPair.private, encrypted)

        assertArrayEquals("AES-256 key round-trip should succeed",
            aes256Key, decrypted)
    }

    @Test
    fun `exportPublicKeyToJWK produces valid JWK structure`() {
        val jwk = RSACrypto.exportPublicKeyToJWK(keyPair.public)

        assertEquals("JWK kty should be RSA", "RSA", jwk.get("kty").asString)
        assertEquals("JWK alg should be RSA-OAEP-256", "RSA-OAEP-256", jwk.get("alg").asString)
        assertNotNull("JWK should have modulus (n)", jwk.get("n"))
        assertNotNull("JWK should have public exponent (e)", jwk.get("e"))
    }

    @Test
    fun `exportPrivateKeyToJWK produces valid JWK structure`() {
        val jwk = RSACrypto.exportPrivateKeyToJWK(keyPair.private)

        assertEquals("JWK kty should be RSA", "RSA", jwk.get("kty").asString)
        assertEquals("JWK alg should be RSA-OAEP-256", "RSA-OAEP-256", jwk.get("alg").asString)
        assertNotNull("JWK should have modulus (n)", jwk.get("n"))
        assertNotNull("JWK should have public exponent (e)", jwk.get("e"))
        assertNotNull("JWK should have private exponent (d)", jwk.get("d"))
        assertNotNull("JWK should have prime p", jwk.get("p"))
        assertNotNull("JWK should have prime q", jwk.get("q"))
        assertNotNull("JWK should have dp", jwk.get("dp"))
        assertNotNull("JWK should have dq", jwk.get("dq"))
        assertNotNull("JWK should have qi", jwk.get("qi"))
    }

    @Test
    fun `importPublicKeyFromJWK recreates usable key`() {
        val originalJwk = RSACrypto.exportPublicKeyToJWK(keyPair.public)
        val importedPublicKey = RSACrypto.importPublicKeyFromJWK(originalJwk)

        val encrypted = RSACrypto.encrypt(importedPublicKey, testPlaintext)
        val decrypted = RSACrypto.decrypt(keyPair.private, encrypted)

        assertArrayEquals("Encrypted with imported key should decrypt with original private key",
            testPlaintext, decrypted)
    }

    @Test
    fun `importPrivateKeyFromJWK recreates usable key`() {
        val originalJwk = RSACrypto.exportPrivateKeyToJWK(keyPair.private)
        val importedPrivateKey = RSACrypto.importPrivateKeyFromJWK(originalJwk)

        val encrypted = RSACrypto.encrypt(keyPair.public, testPlaintext)
        val decrypted = RSACrypto.decrypt(importedPrivateKey, encrypted)

        assertArrayEquals("Encrypted with original key should decrypt with imported private key",
            testPlaintext, decrypted)
    }

    @Test
    fun `importPublicKeyFromJWK with JSON string works`() {
        val originalJwk = RSACrypto.exportPublicKeyToJWK(keyPair.public)
        val jwkJson = originalJwk.toString()

        val importedPublicKey = RSACrypto.importPublicKeyFromJWK(jwkJson)

        val encrypted = RSACrypto.encrypt(importedPublicKey, testPlaintext)
        val decrypted = RSACrypto.decrypt(keyPair.private, encrypted)

        assertArrayEquals("Import from JSON string should work",
            testPlaintext, decrypted)
    }

    @Test
    fun `importPrivateKeyFromJWK with JSON string works`() {
        val originalJwk = RSACrypto.exportPrivateKeyToJWK(keyPair.private)
        val jwkJson = originalJwk.toString()

        val importedPrivateKey = RSACrypto.importPrivateKeyFromJWK(jwkJson)

        val encrypted = RSACrypto.encrypt(keyPair.public, testPlaintext)
        val decrypted = RSACrypto.decrypt(importedPrivateKey, encrypted)

        assertArrayEquals("Import from JSON string should work",
            testPlaintext, decrypted)
    }

    @Test
    fun `JWK export and import round-trip produces working keys`() {
        val publicKeyJwk = RSACrypto.exportPublicKeyToJWK(keyPair.public)
        val privateKeyJwk = RSACrypto.exportPrivateKeyToJWK(keyPair.private)

        val importedPublicKey = RSACrypto.importPublicKeyFromJWK(publicKeyJwk)
        val importedPrivateKey = RSACrypto.importPrivateKeyFromJWK(privateKeyJwk)

        val encrypted = RSACrypto.encrypt(importedPublicKey, testPlaintext)
        val decrypted = RSACrypto.decrypt(importedPrivateKey, encrypted)

        assertArrayEquals("Full round-trip should produce original data",
            testPlaintext, decrypted)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importPublicKeyFromJWK throws exception for missing n field`() {
        val invalidJwk = JsonObject()
        invalidJwk.addProperty("kty", "RSA")
        invalidJwk.addProperty("alg", "RSA-OAEP-256")
        invalidJwk.addProperty("e", "AQAB")

        RSACrypto.importPublicKeyFromJWK(invalidJwk)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importPublicKeyFromJWK throws exception for missing e field`() {
        val invalidJwk = JsonObject()
        invalidJwk.addProperty("kty", "RSA")
        invalidJwk.addProperty("alg", "RSA-OAEP-256")
        invalidJwk.addProperty("n", "base64urlencodedn")

        RSACrypto.importPublicKeyFromJWK(invalidJwk)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importPublicKeyFromJWK throws exception for wrong key type`() {
        val invalidJwk = JsonObject()
        invalidJwk.addProperty("kty", "EC")
        invalidJwk.addProperty("alg", "RSA-OAEP-256")
        invalidJwk.addProperty("n", "base64urlencodedn")
        invalidJwk.addProperty("e", "AQAB")

        RSACrypto.importPublicKeyFromJWK(invalidJwk)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `importPrivateKeyFromJWK throws exception for missing d field`() {
        val invalidJwk = JsonObject()
        invalidJwk.addProperty("kty", "RSA")
        invalidJwk.addProperty("alg", "RSA-OAEP-256")
        invalidJwk.addProperty("n", "base64urlencodedn")
        invalidJwk.addProperty("e", "AQAB")

        RSACrypto.importPrivateKeyFromJWK(invalidJwk)
    }

    @Test
    fun `RSA modulus length constant is 4096`() {
        assertEquals("RSA modulus length should be 4096 bits",
            4096, RSACrypto.RSA_MODULUS_LENGTH)
    }

    @Test
    fun `RSA public exponent constant is 65537`() {
        assertEquals("RSA public exponent should be 65537 (F4)",
            65537, RSACrypto.PUBLIC_EXPONENT)
    }

    @Test
    fun `RSA transformation constant uses OAEP with SHA-256`() {
        assertEquals("RSA transformation should use OAEP with SHA-256",
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding", RSACrypto.TRANSFORMATION)
    }

    @Test
    fun `decryption with wrong key throws exception`() {
        val keyPair2 = RSACrypto.generateKeyPair()
        val encrypted = RSACrypto.encrypt(keyPair.public, testPlaintext)

        try {
            RSACrypto.decrypt(keyPair2.private, encrypted)
            fail("Decryption with wrong key should throw an exception")
        } catch (e: Exception) {
            // Expected - decryption should fail with wrong key
        }
    }
}
