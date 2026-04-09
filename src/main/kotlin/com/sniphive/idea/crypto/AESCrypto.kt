package com.sniphive.idea.crypto

import com.intellij.openapi.diagnostic.Logger
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM Encryption - Advanced Encryption Standard with Galois/Counter Mode
 *
 * This class implements AES-256-GCM symmetric encryption for content encryption.
 * Used for encrypting data with Data Encryption Keys (DEKs).
 *
 * Security Notes:
 * - 256-bit key length (AES-256, NIST approved for top secret data)
 * - GCM mode provides authenticated encryption (confidentiality + integrity)
 * - 12-byte IV (96 bits) as recommended for GCM
 * - No padding needed (GCM is a stream cipher mode)
 * - Authentication tag is 16 bytes (128 bits)
 *
 * GCM mode advantages:
 * - Authenticated encryption: any modification of ciphertext is detected
 * - Parallelizable: encryption/decryption can be parallelized
 * - No padding: no padding oracle attacks
 *
 * @see javax.crypto.Cipher
 * @see javax.crypto.spec.GCMParameterSpec
 */
class AESCrypto {

    companion object {
        private val LOG = Logger.getInstance(AESCrypto::class.java)

        // Security constants
        const val AES_KEY_LENGTH = 256 // 256 bits for AES-256
        const val AES_IV_LENGTH = 12 // 96 bits for GCM (recommended)
        const val GCM_TAG_LENGTH = 128 // 128-bit authentication tag
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /**
         * Generate a new AES-256-GCM key for content encryption.
         *
         * The generated key is suitable for:
         * - Encrypting snippet content
         * - Encrypting user data
         * - Use as a DEK (Data Encryption Key) in envelope encryption
         *
         * @return A SecretKey for AES-256-GCM encryption
         * @throws RuntimeException if key generation fails
         */
        @JvmStatic
        fun generateKey(): SecretKey {
            return try {
                val keyGenerator = KeyGenerator.getInstance(KEY_ALGORITHM)
                keyGenerator.init(AES_KEY_LENGTH, SecureRandom())
                keyGenerator.generateKey()
            } catch (e: Exception) {
                LOG.error("Failed to generate AES key", e)
                throw RuntimeException("AES key generation failed: ${e.message}", e)
            }
        }

        /**
         * Generate a random IV for AES-GCM (12 bytes / 96 bits).
         *
         * The IV (Initialization Vector) must be:
         * - Unique for each encryption operation with the same key
         * - Never reused with the same key (security critical)
         * - Stored alongside the encrypted data for decryption
         * - 12 bytes (96 bits) as recommended for GCM mode
         *
         * Note: IV doesn't need to be secret, only unique and unpredictable.
         *
         * @return A 12-byte random IV
         */
        @JvmStatic
        fun generateIV(): ByteArray {
            val iv = ByteArray(AES_IV_LENGTH)
            SecureRandom().nextBytes(iv)
            return iv
        }

        /**
         * Generate a random IV with custom length.
         *
         * For GCM mode, 12 bytes is recommended but other sizes are supported.
         * Note: Only 12-byte IVs guarantee no nonce reuse issues.
         *
         * @param length The length of the IV in bytes (minimum 8 for security)
         * @return A random IV of the specified length
         * @throws IllegalArgumentException if length is less than 8
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun generateIV(length: Int): ByteArray {
            if (length < 8) {
                throw IllegalArgumentException("IV length must be at least 8 bytes for security")
            }
            val iv = ByteArray(length)
            SecureRandom().nextBytes(iv)
            return iv
        }

        /**
         * Encrypt data using AES-256-GCM.
         *
         * This method:
         * 1. Uses the provided IV (must be unique for each encryption)
         * 2. Encrypts the plaintext
         * 3. Generates a 128-bit authentication tag
         * 4. Returns ciphertext with the authentication tag appended
         *
         * The IV must be:
         * - 12 bytes (96 bits) recommended for GCM
         * - Unique for each encryption with the same key
         * - Never reused (security critical)
         *
         * @param key The AES-256 key to encrypt with
         * @param plaintext The plaintext data to encrypt
         * @param iv The initialization vector (12 bytes recommended)
         * @return The encrypted data with authentication tag
         * @throws IllegalArgumentException if key, plaintext, or iv are invalid
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encrypt(key: SecretKey, plaintext: ByteArray, iv: ByteArray): ByteArray {
            // Validate inputs
            if (plaintext.isEmpty()) {
                throw IllegalArgumentException("Plaintext cannot be empty")
            }

            if (iv.isEmpty()) {
                throw IllegalArgumentException("IV cannot be empty")
            }

            if (iv.size < 8) {
                throw IllegalArgumentException("IV must be at least 8 bytes for security")
            }

            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.ENCRYPT_MODE, key, spec)
                cipher.doFinal(plaintext)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                LOG.error("Failed to encrypt data with AES", e)
                throw RuntimeException("AES encryption failed: ${e.message}", e)
            }
        }

        /**
         * Encrypt a string using AES-256-GCM.
         *
         * Convenience method that handles string encoding automatically.
         *
         * @param key The AES-256 key to encrypt with
         * @param plaintext The plaintext string to encrypt
         * @param iv The initialization vector (12 bytes recommended)
         * @return The encrypted data with authentication tag
         * @throws IllegalArgumentException if key, plaintext, or iv are invalid
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encrypt(key: SecretKey, plaintext: String, iv: ByteArray): ByteArray {
            if (plaintext.isEmpty()) {
                throw IllegalArgumentException("Plaintext cannot be empty")
            }
            return encrypt(key, plaintext.toByteArray(Charsets.UTF_8), iv)
        }

        /**
         * Decrypt data using AES-256-GCM.
         *
         * This method:
         * 1. Uses the provided IV (must match the encryption IV)
         * 2. Verifies the authentication tag (throws if tampered)
         * 3. Decrypts the ciphertext
         *
         * GCM provides authenticated encryption - any modification of the
         * ciphertext or IV will be detected and the decryption will fail.
         *
         * @param key The AES-256 key to decrypt with
         * @param ciphertext The encrypted data with authentication tag
         * @param iv The initialization vector (must match encryption IV)
         * @return The decrypted plaintext
         * @throws IllegalArgumentException if key, ciphertext, or iv are invalid
         * @throws RuntimeException if decryption fails or authentication fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decrypt(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): ByteArray {
            // Validate inputs
            if (ciphertext.isEmpty()) {
                throw IllegalArgumentException("Ciphertext cannot be empty")
            }

            if (iv.isEmpty()) {
                throw IllegalArgumentException("IV cannot be empty")
            }

            if (iv.size < 8) {
                throw IllegalArgumentException("IV must be at least 8 bytes for security")
            }

            return try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, key, spec)
                cipher.doFinal(ciphertext)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: javax.crypto.AEADBadTagException) {
                // Authentication tag verification failed - data was tampered with
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Decryption failed: authentication tag verification failed. Data may have been tampered with.", e)
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("AES decryption failed: ${e.message}", e)
            }
        }

        /**
         * Decrypt data to a string using AES-256-GCM.
         *
         * Convenience method that handles string decoding automatically.
         *
         * @param key The AES-256 key to decrypt with
         * @param ciphertext The encrypted data with authentication tag
         * @param iv The initialization vector (must match encryption IV)
         * @return The decrypted plaintext as a String
         * @throws IllegalArgumentException if key, ciphertext, or iv are invalid
         * @throws RuntimeException if decryption fails or authentication fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptToString(key: SecretKey, ciphertext: ByteArray, iv: ByteArray): String {
            val decryptedBytes = decrypt(key, ciphertext, iv)
            return String(decryptedBytes, Charsets.UTF_8)
        }

        /**
         * Export an AES key to raw bytes.
         *
         * This is used to export the key so it can be:
         * - Encrypted with RSA (for envelope encryption)
         * - Stored securely
         * - Transmitted encrypted to recipients
         *
         * WARNING: Raw key material should never be logged or transmitted
         * in plaintext. Always encrypt with RSA before storage/transmission.
         *
         * @param key The AES key to export
         * @return The raw key bytes (32 bytes for AES-256)
         * @throws IllegalArgumentException if key is null
         * @throws RuntimeException if export fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun exportKey(key: SecretKey): ByteArray {
            return try {
                key.encoded
            } catch (e: Exception) {
                LOG.error("Failed to export AES key", e)
                throw RuntimeException("AES key export failed: ${e.message}", e)
            }
        }

        /**
         * Import an AES key from raw bytes.
         *
         * This is used to import a key that was:
         * - Decrypted from RSA-encrypted data
         * - Retrieved from secure storage
         * - Received from a trusted source
         *
         * @param keyBytes The raw key bytes (32 bytes for AES-256)
         * @return A SecretKey for AES-256-GCM encryption
         * @throws IllegalArgumentException if keyBytes are invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importKey(keyBytes: ByteArray): SecretKey {
            if (keyBytes.isEmpty()) {
                throw IllegalArgumentException("Key bytes cannot be empty")
            }

            if (keyBytes.size != (AES_KEY_LENGTH / 8)) {
                throw IllegalArgumentException(
                    "Invalid key length. Expected ${AES_KEY_LENGTH / 8} bytes for AES-${AES_KEY_LENGTH}, got ${keyBytes.size} bytes"
                )
            }

            return try {
                SecretKeySpec(keyBytes, KEY_ALGORITHM)
            } catch (e: Exception) {
                LOG.error("Failed to import AES key", e)
                throw RuntimeException("AES key import failed: ${e.message}", e)
            }
        }

        /**
         * Encrypt a DEK (Data Encryption Key) using AES-GCM.
         *
         * Convenience method that wraps the encrypt() function with
         * clearer naming for the E2EE use case.
         *
         * @param dek The DEK to encrypt (typically 32 bytes for AES-256)
         * @param key The AES key to encrypt with
         * @param iv The initialization vector
         * @return The encrypted DEK
         * @throws IllegalArgumentException if inputs are invalid
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encryptDEK(dek: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
            return encrypt(key, dek, iv)
        }

        /**
         * Decrypt a DEK (Data Encryption Key) using AES-GCM.
         *
         * Convenience method that wraps the decrypt() function with
         * clearer naming for the E2EE use case.
         *
         * @param encryptedDEK The encrypted DEK to decrypt
         * @param key The AES key to decrypt with
         * @param iv The initialization vector
         * @return The decrypted DEK
         * @throws IllegalArgumentException if inputs are invalid
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptDEK(encryptedDEK: ByteArray, key: SecretKey, iv: ByteArray): ByteArray {
            return decrypt(key, encryptedDEK, iv)
        }
    }
}
