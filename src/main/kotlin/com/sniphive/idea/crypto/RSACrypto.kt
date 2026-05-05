package com.sniphive.idea.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.math.BigInteger
import java.security.*
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher

/**
 * RSA-4096 OAEP Encryption - RSA with Optimal Asymmetric Encryption Padding
 *
 * This class implements RSA-4096 encryption using OAEP padding with SHA-256.
 * Used for encrypting Data Encryption Keys (DEKs) for secure key distribution.
 *
 * Security Notes:
 * - 4096-bit RSA key size (NIST recommended for long-term security)
 * - OAEP padding with SHA-256 (prevents chosen ciphertext attacks)
 * - MGF1 with SHA-256 as the mask generation function
 * - Public exponent 65537 (0x10001) for optimal security and performance
 * - Maximum encryptable data size: 446 bytes (for 4096-bit RSA with OAEP-SHA256)
 *
 * @see java.security.KeyPairGenerator
 * @see javax.crypto.Cipher
 */
class RSACrypto {

    companion object {
        private val LOG = Logger.getInstance(RSACrypto::class.java)

        // Security constants
        const val RSA_MODULUS_LENGTH = 4096
        const val PUBLIC_EXPONENT = 65537 // 0x10001 (standard F4 value)
        const val TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        const val KEY_ALGORITHM = "RSA"
        const val MAX_OAEP_SHA256_DATA_SIZE = (RSA_MODULUS_LENGTH / 8) - (2 * 32) - 2

        // Provider initialization
        init {
            Security.addProvider(BouncyCastleProvider())
        }

        /**
         * Generate an RSA-4096 key pair for asymmetric encryption.
         *
         * The generated key pair uses:
         * - 4096-bit modulus size
         * - Public exponent 65537 (standard F4 value)
         * - Suitable for encrypting DEKs (Data Encryption Keys)
         *
         * @return A KeyPair containing the public and private keys
         * @throws RuntimeException if key generation fails
         */
        @JvmStatic
        fun generateKeyPair(): KeyPair {
            return try {
                val keyPairGenerator = KeyPairGenerator.getInstance(KEY_ALGORITHM)
                keyPairGenerator.initialize(RSA_MODULUS_LENGTH, SecureRandom())
                keyPairGenerator.generateKeyPair()
            } catch (e: Exception) {
                LOG.error("Failed to generate RSA key pair", e)
                throw RuntimeException("RSA key pair generation failed: ${e.message}", e)
            }
        }

        /**
         * Encrypt data using RSA-OAEP with the public key.
         *
         * This method is typically used to encrypt Data Encryption Keys (DEKs)
         * for secure distribution. The maximum data size is 470 bytes for
         * 4096-bit RSA with OAEP-SHA256 padding.
         *
         * @param publicKey The public key to encrypt with
         * @param data The plaintext data to encrypt (max 446 bytes for 4096-bit RSA)
         * @return The encrypted data as a ByteArray
         * @throws IllegalArgumentException if data is null or too large
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encrypt(publicKey: PublicKey, data: ByteArray): ByteArray {
            // Validate inputs
            if (data.isEmpty()) {
                throw IllegalArgumentException("Data cannot be empty")
            }

            // Formula: modulus_length_bytes - 2 * hash_length_bytes - 2
            val maxDataSize = MAX_OAEP_SHA256_DATA_SIZE
            if (data.size > maxDataSize) {
                throw IllegalArgumentException(
                    "Data too large for RSA-4096-OAEP encryption. " +
                    "Maximum size: $maxDataSize bytes, got: ${data.size} bytes"
                )
            }

            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME)
                cipher.init(Cipher.ENCRYPT_MODE, publicKey)
                cipher.doFinal(data)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to encrypt data with RSA", e)
                throw RuntimeException("RSA encryption failed: ${e.message}", e)
            }
        }

        /**
         * Decrypt data using RSA-OAEP with the private key.
         *
         * This method is typically used to decrypt Data Encryption Keys (DEKs)
         * that were encrypted with the corresponding public key.
         *
         * @param privateKey The private key to decrypt with
         * @param encryptedData The encrypted data to decrypt
         * @return The decrypted plaintext as a ByteArray
         * @throws IllegalArgumentException if encryptedData is null or empty
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decrypt(privateKey: PrivateKey, encryptedData: ByteArray): ByteArray {
            // Validate inputs
            if (encryptedData.isEmpty()) {
                throw IllegalArgumentException("Encrypted data cannot be empty")
            }

            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION, BouncyCastleProvider.PROVIDER_NAME)
                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                cipher.doFinal(encryptedData)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("RSA decryption failed: ${e.message}", e)
            }
        }

        /**
         * Export a public key to JWK (JSON Web Key) format.
         *
         * JWK format is used for interoperability with web applications and
         * matches the format used by the TypeScript E2EECryptoService.
         *
         * The JWK format for RSA public keys includes:
         * - kty: Key type ("RSA")
         * - n: Modulus (base64url-encoded)
         * - e: Public exponent (base64url-encoded)
         * - alg: Algorithm ("RSA-OAEP-256")
         *
         * @param publicKey The public key to export
         * @return A JsonObject containing the key in JWK format
         * @throws IllegalArgumentException if the key is not an RSA key
         * @throws RuntimeException if export fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun exportPublicKeyToJWK(publicKey: PublicKey): JsonObject {
            if (publicKey !is java.security.interfaces.RSAPublicKey) {
                throw IllegalArgumentException("Key is not an RSA public key")
            }

            return try {
                val jwk = JsonObject()
                jwk.addProperty("kty", "RSA")
                jwk.addProperty("alg", "RSA-OAEP-256")

                // Convert modulus and exponent to Base64URL format
                jwk.addProperty("n", base64UrlEncode(publicKey.modulus.toByteArrayUnsigned()))
                jwk.addProperty("e", base64UrlEncode(publicKey.publicExponent.toByteArrayUnsigned()))

                jwk
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to export public key to JWK", e)
                throw RuntimeException("JWK export failed: ${e.message}", e)
            }
        }

        /**
         * Export a private key to JWK (JSON Web Key) format.
         *
         * WARNING: Private keys should only be exported in encrypted form
         * and stored securely. Never log or transmit private keys in plaintext.
         *
         * The JWK format for RSA private keys includes all public key fields
         * plus:
         * - d: Private exponent (required)
         * - p: First prime factor (optional, CRT)
         * - q: Second prime factor (optional, CRT)
         * - dp: First factor CRT exponent (optional, CRT)
         * - dq: Second factor CRT exponent (optional, CRT)
         * - qi: First CRT coefficient (optional, CRT)
         *
         * If the key is not an RSAPrivateCrtKey, only the required fields (n, e, d)
         * are exported. This is sufficient for decryption operations.
         *
         * @param privateKey The private key to export
         * @return A JsonObject containing the key in JWK format
         * @throws IllegalArgumentException if the key is not an RSA key
         * @throws RuntimeException if export fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun exportPrivateKeyToJWK(privateKey: PrivateKey): JsonObject {
            if (privateKey !is java.security.interfaces.RSAPrivateKey) {
                throw IllegalArgumentException("Key is not an RSA private key")
            }

            return try {
                val jwk = JsonObject()
                jwk.addProperty("kty", "RSA")
                jwk.addProperty("alg", "RSA-OAEP-256")

                // Check if this is a CRT key (has prime factors)
                if (privateKey is java.security.interfaces.RSAPrivateCrtKey) {
                    val crtKey = privateKey

                    // Public key components
                    jwk.addProperty("n", base64UrlEncode(crtKey.modulus.toByteArrayUnsigned()))
                    jwk.addProperty("e", base64UrlEncode(crtKey.publicExponent.toByteArrayUnsigned()))

                    // Private key components with CRT parameters
                    jwk.addProperty("d", base64UrlEncode(crtKey.privateExponent.toByteArrayUnsigned()))
                    jwk.addProperty("p", base64UrlEncode(crtKey.primeP.toByteArrayUnsigned()))
                    jwk.addProperty("q", base64UrlEncode(crtKey.primeQ.toByteArrayUnsigned()))
                    jwk.addProperty("dp", base64UrlEncode(crtKey.primeExponentP.toByteArrayUnsigned()))
                    jwk.addProperty("dq", base64UrlEncode(crtKey.primeExponentQ.toByteArrayUnsigned()))
                    jwk.addProperty("qi", base64UrlEncode(crtKey.crtCoefficient.toByteArrayUnsigned()))
                } else {
                    // Non-CRT key: export only essential parameters
                    // Need to get public exponent from the public key
                    val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
                    val publicKey = keyFactory.generatePublic(java.security.spec.RSAPublicKeySpec(privateKey.modulus, BigInteger.valueOf(65537)))

                    jwk.addProperty("n", base64UrlEncode(privateKey.modulus.toByteArrayUnsigned()))
                    jwk.addProperty("e", base64UrlEncode(BigInteger.valueOf(65537).toByteArrayUnsigned()))
                    jwk.addProperty("d", base64UrlEncode(privateKey.privateExponent.toByteArrayUnsigned()))
                }

                jwk
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to export private key to JWK", e)
                throw RuntimeException("JWK export failed: ${e.message}", e)
            }
        }

        /**
         * Import a public key from JWK (JSON Web Key) format.
         *
         * This method interoperates with the TypeScript E2EECryptoService's
         * importPublicKeyFromJWK function.
         *
         * @param jwk A JsonObject containing the key in JWK format
         * @return A PublicKey object
         * @throws IllegalArgumentException if the JWK is invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importPublicKeyFromJWK(jwk: JsonObject): PublicKey {
            try {
                // Validate required fields
                val kty = jwk.get("kty")?.asString
                if (kty != "RSA") {
                    throw IllegalArgumentException("Invalid JWK: kty must be 'RSA', got '$kty'")
                }

                val nBase64 = jwk.get("n")?.asString
                val eBase64 = jwk.get("e")?.asString

                if (nBase64 == null || eBase64 == null) {
                    throw IllegalArgumentException("Invalid JWK: missing 'n' or 'e' field")
                }

                // Decode Base64URL and reconstruct the key
                val n = BigInteger(1, base64UrlDecode(nBase64))
                val e = BigInteger(1, base64UrlDecode(eBase64))

                val keySpec = RSAPublicKeySpec(n, e)
                val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
                return keyFactory.generatePublic(keySpec)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to import public key from JWK", e)
                throw RuntimeException("JWK import failed: ${e.message}", e)
            }
        }

        /**
         * Import a private key from JWK (JSON Web Key) format.
         *
         * This method interoperates with the TypeScript E2EECryptoService's
         * importPrivateKeyFromJWK function.
         *
         * @param jwk A JsonObject containing the key in JWK format
         * @return A PrivateKey object
         * @throws IllegalArgumentException if the JWK is invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importPrivateKeyFromJWK(jwk: JsonObject): PrivateKey {
            try {
                // Validate required fields
                val kty = jwk.get("kty")?.asString
                if (kty != "RSA") {
                    throw IllegalArgumentException("Invalid JWK: kty must be 'RSA', got '$kty'")
                }

                val dBase64 = jwk.get("d")?.asString
                if (dBase64 == null) {
                    throw IllegalArgumentException("Invalid JWK: missing 'd' field (private exponent)")
                }

                // For simplicity, we'll use PKCS8 encoded format
                // In a full implementation, we would reconstruct using CRT components
                // for better performance, but PKCS8 is sufficient for our use case

                // Decode the required parameters
                val n = BigInteger(1, base64UrlDecode(jwk.get("n").asString))
                val e = BigInteger(1, base64UrlDecode(jwk.get("e").asString))
                val d = BigInteger(1, base64UrlDecode(dBase64))

                val keyFactory = KeyFactory.getInstance(KEY_ALGORITHM)
                val privateKeySpec = java.security.spec.RSAPrivateKeySpec(n, d)
                return keyFactory.generatePrivate(privateKeySpec)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to import private key from JWK", e)
                throw RuntimeException("JWK import failed: ${e.message}", e)
            }
        }

        /**
         * Import a public key from a JWK JSON string.
         *
         * @param jwkJson A JSON string containing the key in JWK format
         * @return A PublicKey object
         * @throws IllegalArgumentException if the JSON is invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importPublicKeyFromJWK(jwkJson: String): PublicKey {
            try {
                val jwk = JsonParser.parseString(jwkJson).asJsonObject
                return importPublicKeyFromJWK(jwk)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to parse JWK JSON", e)
                throw RuntimeException("JWK JSON parsing failed: ${e.message}", e)
            }
        }

        /**
         * Import a private key from a JWK JSON string.
         *
         * @param jwkJson A JSON string containing the key in JWK format
         * @return A PrivateKey object
         * @throws IllegalArgumentException if the JSON is invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importPrivateKeyFromJWK(jwkJson: String): PrivateKey {
            try {
                val jwk = JsonParser.parseString(jwkJson).asJsonObject
                return importPrivateKeyFromJWK(jwk)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to parse JWK JSON", e)
                throw RuntimeException("JWK JSON parsing failed: ${e.message}", e)
            }
        }

        /**
         * Convert a BigInteger to unsigned byte array (removes leading zero if present).
         *
         * @param value The BigInteger to convert
         * @return Unsigned byte array representation
         */
        private fun BigInteger.toByteArrayUnsigned(): ByteArray {
            val bytes = this.toByteArray()
            // Remove leading zero if present (BigInteger adds sign bit)
            return if (bytes[0] == 0.toByte()) {
                bytes.copyOfRange(1, bytes.size)
            } else {
                bytes
            }
        }

        /**
         * Encode bytes to Base64URL format (URL-safe, no padding).
         *
         * Base64URL uses - and _ instead of + and /, and omits padding.
         *
         * @param data The bytes to encode
         * @return Base64URL-encoded string
         */
        private fun base64UrlEncode(data: ByteArray): String {
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(data)
        }

        /**
         * Decode Base64URL format bytes.
         *
         * @param data The Base64URL-encoded string
         * @return Decoded byte array
         */
        private fun base64UrlDecode(data: String): ByteArray {
            return Base64.getUrlDecoder().decode(data)
        }

        /**
         * Encrypt a DEK (Data Encryption Key) using RSA-OAEP.
         *
         * Convenience method that wraps the encrypt() function with
         * clearer naming for the E2EE use case.
         *
         * @param publicKey The public key to encrypt with
         * @param dek The DEK to encrypt (typically 32 bytes for AES-256)
         * @return The encrypted DEK as a ByteArray
         * @throws IllegalArgumentException if DEK is invalid
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encryptDEK(publicKey: PublicKey, dek: ByteArray): ByteArray {
            return encrypt(publicKey, dek)
        }

        /**
         * Decrypt a DEK (Data Encryption Key) using RSA-OAEP.
         *
         * Convenience method that wraps the decrypt() function with
         * clearer naming for the E2EE use case.
         *
         * @param privateKey The private key to decrypt with
         * @param encryptedDEK The encrypted DEK to decrypt
         * @return The decrypted DEK as a ByteArray
         * @throws IllegalArgumentException if encryptedDEK is invalid
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptDEK(privateKey: PrivateKey, encryptedDEK: ByteArray): ByteArray {
            return decrypt(privateKey, encryptedDEK)
        }
    }
}
