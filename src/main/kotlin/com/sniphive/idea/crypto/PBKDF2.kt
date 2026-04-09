package com.sniphive.idea.crypto

import org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.params.KeyParameter
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import com.intellij.openapi.diagnostic.Logger

/**
 * PBKDF2 Key Derivation - Password-Based Key Derivation Function 2
 *
 * This class implements PBKDF2 with SHA-256 for secure key derivation from passwords.
 * Following OWASP recommendations with 600,000 iterations.
 *
 * Security Notes:
 * - PBKDF2 with 600,000 iterations (OWASP recommended minimum)
 * - SHA-256 hash algorithm
 * - 128-bit salt (16 bytes)
 * - 256-bit derived key (32 bytes) for AES-256-GCM
 * - Uses cryptographically secure random for salt generation
 *
 * @see org.bouncycastle.crypto.generators.PKCS5S2ParametersGenerator
 */
class PBKDF2 {

    companion object {
        private val LOG = Logger.getInstance(PBKDF2::class.java)

        // Security constants following OWASP recommendations
        const val PBKDF2_ITERATIONS = 600000
        const val SALT_LENGTH = 16 // 128 bits
        const val DERIVED_KEY_LENGTH = 32 // 256 bits for AES-256
        const val KEY_ALGORITHM = "AES"

        /**
         * Derive an AES-256-GCM key from a password using PBKDF2.
         *
         * This method:
         * 1. Uses PBKDF2 with HMAC-SHA256
         * 2. Performs 600,000 iterations (OWASP recommended minimum)
         * 3. Generates a 256-bit key suitable for AES-256-GCM
         * 4. Returns the key as a SecretKey object
         *
         * @param password The password to derive the key from
         * @param salt The salt bytes (128 bits recommended)
         * @param iterations The number of PBKDF2 iterations (default: 600,000)
         * @return A SecretKey suitable for AES-256-GCM encryption
         * @throws IllegalArgumentException if password, salt, or iterations are invalid
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun deriveKey(
            password: String,
            salt: ByteArray,
            iterations: Int = PBKDF2_ITERATIONS
        ): SecretKey {
            // Validate inputs
            if (password.isEmpty()) {
                throw IllegalArgumentException("Password cannot be empty")
            }

            if (salt.isEmpty()) {
                throw IllegalArgumentException("Salt cannot be empty")
            }

            if (salt.size < 8) {
                throw IllegalArgumentException("Salt must be at least 8 bytes for security")
            }

            if (iterations < 100000) {
                throw IllegalArgumentException("Iterations must be at least 100,000 for security")
            }

            return try {
                // Create PBKDF2 parameters generator with SHA-256
                val generator = PKCS5S2ParametersGenerator(SHA256Digest())

                // Initialize generator with password bytes and salt
                generator.init(
                    password.toByteArray(Charsets.UTF_8),
                    salt,
                    iterations
                )

                // Derive the key parameters
                val keyParameter = generator.generateDerivedParameters(
                    DERIVED_KEY_LENGTH * 8 // Convert bytes to bits
                ) as KeyParameter

                // Extract the key bytes
                val keyBytes = keyParameter.key

                // Create SecretKey for AES
                SecretKeySpec(keyBytes, KEY_ALGORITHM)

            } catch (e: Exception) {
                LOG.error("Failed to derive key using PBKDF2", e)
                throw IllegalArgumentException("Key derivation failed: ${e.message}", e)
            }
        }

        /**
         * Generate a cryptographically secure random salt for key derivation.
         *
         * The salt should be:
         * - Unique for each key derivation
         * - Stored alongside the encrypted data
         * - 16 bytes (128 bits) for optimal security
         *
         * @return A 16-byte random salt
         */
        @JvmStatic
        fun generateSalt(): ByteArray {
            val salt = ByteArray(SALT_LENGTH)
            SecureRandom().nextBytes(salt)
            return salt
        }

        /**
         * Generate a cryptographically secure random salt with custom length.
         *
         * @param length The length of the salt in bytes (minimum 8)
         * @return A random salt of the specified length
         * @throws IllegalArgumentException if length is less than 8
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun generateSalt(length: Int): ByteArray {
            if (length < 8) {
                throw IllegalArgumentException("Salt length must be at least 8 bytes for security")
            }
            val salt = ByteArray(length)
            SecureRandom().nextBytes(salt)
            return salt
        }
    }
}
