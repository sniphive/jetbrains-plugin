package com.sniphive.idea.crypto

import com.intellij.openapi.diagnostic.Logger
import java.security.PrivateKey
import java.security.PublicKey
import java.util.Base64

/**
 * Envelope Encryption - Hybrid Encryption Scheme (DEK + RSA)
 *
 * This class implements envelope encryption, also known as hybrid encryption.
 * It combines the efficiency of symmetric encryption with the security of asymmetric encryption.
 *
 * How it works:
 * 1. Generate a random Data Encryption Key (DEK) using AES-256-GCM
 * 2. Encrypt the content with the DEK (fast symmetric encryption)
 * 3. Encrypt the DEK with RSA-4096-OAEP using the recipient's public key
 * 4. Store both the encrypted content and the encrypted DEK
 * 5. To decrypt: decrypt the DEK with the private key, then decrypt the content
 *
 * Benefits:
 * - Performance: Content encryption with AES is much faster than RSA
 * - Scalability: Same content can be encrypted once with a DEK, then the DEK can be
 *   encrypted multiple times for different recipients
 * - Forward secrecy: Each encryption uses a unique DEK
 * - Zero-knowledge: The server never has access to the plaintext DEK or content
 *
 * @see AESCrypto
 * @see RSACrypto
 */
class EnvelopeEncryption {

    companion object {
        private val LOG = Logger.getInstance(EnvelopeEncryption::class.java)

        /**
         * Result of envelope encryption.
         *
         * @property content The encrypted content as a Base64 string in format: "iv.ciphertext"
         * @property encryptedDEK The encrypted DEK as a Base64 string
         */
        data class EncryptedContentResult(
            val content: String,
            val encryptedDEK: String
        )

        /**
         * Result of anonymous encryption (for URL hash sharing).
         *
         * @property content The encrypted content as a Base64 string in format: "iv.ciphertext"
         * @property dek The raw DEK bytes (to be included in URL hash)
         * @property iv The IV as a Base64 string
         */
        data class AnonymousEncryptionResult(
            val content: String,
            val dek: ByteArray,
            val iv: String
        )

        /**
         * Generate a new Data Encryption Key (DEK) for content encryption.
         *
         * The DEK is an AES-256-GCM key that will be used to encrypt the actual content.
         * It will later be encrypted with RSA and stored alongside the encrypted content.
         *
         * @return A SecretKey for AES-256-GCM encryption
         * @throws RuntimeException if key generation fails
         */
        @JvmStatic
        fun generateDEK(): javax.crypto.SecretKey {
            return AESCrypto.generateKey()
        }

        /**
         * Export a DEK to raw bytes for RSA encryption.
         *
         * This converts the SecretKey to its raw byte representation so it can be
         * encrypted using RSA-OAEP for secure storage/transmission.
         *
         * WARNING: Raw key material should never be logged or transmitted in plaintext.
         * Always encrypt with RSA before storage/transmission.
         *
         * @param dek The DEK to export
         * @return The raw key bytes (32 bytes for AES-256)
         * @throws IllegalArgumentException if dek is null
         * @throws RuntimeException if export fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun exportDEK(dek: javax.crypto.SecretKey): ByteArray {
            return AESCrypto.exportKey(dek)
        }

        /**
         * Import a DEK from raw bytes.
         *
         * This reconstructs a SecretKey from its raw byte representation after it has been
         * decrypted from RSA-encrypted storage/transmission.
         *
         * @param dekBytes The raw key bytes (32 bytes for AES-256)
         * @return A SecretKey for AES-256-GCM encryption
         * @throws IllegalArgumentException if dekBytes are invalid
         * @throws RuntimeException if import fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun importDEK(dekBytes: ByteArray): javax.crypto.SecretKey {
            return AESCrypto.importKey(dekBytes)
        }

        /**
         * Encrypt content using envelope encryption.
         *
         * Process:
         * 1. Generate a new DEK for this content
         * 2. Generate a random IV
         * 3. Encrypt the content with the DEK using AES-256-GCM
         * 4. Export the DEK to raw bytes
         * 5. Encrypt the DEK with RSA-OAEP using the recipient's public key
         * 6. Return the encrypted content and encrypted DEK
         *
         * The encrypted content format is: "base64(iv).base64(ciphertext)"
         *
         * @param content The plaintext content to encrypt
         * @param publicKey The recipient's RSA public key
         * @return EncryptedContentResult containing encrypted content and encrypted DEK
         * @throws IllegalArgumentException if content is empty or publicKey is null
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encryptContent(content: String, publicKey: PublicKey): EncryptedContentResult {
            // Validate inputs
            if (content.isEmpty()) {
                throw IllegalArgumentException("Content cannot be empty")
            }

            return try {
                // Step 1: Generate a new DEK for this content
                val dek = generateDEK()

                // Step 2: Generate IV
                val iv = AESCrypto.generateIV()

                // Step 3: Encrypt content with DEK
                val encryptedContent = AESCrypto.encrypt(dek, content, iv)

                // Step 4: Export DEK and encrypt with RSA public key
                val rawDEK = exportDEK(dek)
                val encryptedDEK = RSACrypto.encrypt(publicKey, rawDEK)

                // Format: base64(iv).base64(ciphertext)
                val ivBase64 = base64Encode(iv)
                val encryptedContentBase64 = base64Encode(encryptedContent)
                val encryptedDEKBase64 = base64Encode(encryptedDEK)

                EncryptedContentResult(
                    content = "$ivBase64.$encryptedContentBase64",
                    encryptedDEK = encryptedDEKBase64
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Envelope encryption failed: ${e.message}", e)
            }
        }

        /**
         * Decrypt content using envelope encryption.
         *
         * Process:
         * 1. Parse the encrypted content to extract IV and ciphertext
         * 2. Decode the encrypted DEK from Base64
         * 3. Decrypt the DEK with RSA-OAEP using the recipient's private key
         * 4. Import the decrypted DEK bytes as a SecretKey
         * 5. Decrypt the content with the DEK using AES-256-GCM
         *
         * @param encryptedContent The encrypted content in format: "base64(iv).base64(ciphertext)"
         * @param encryptedDEK The encrypted DEK as a Base64 string
         * @param privateKey The recipient's RSA private key
         * @return The decrypted plaintext content
         * @throws IllegalArgumentException if inputs are invalid
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptContent(encryptedContent: String, encryptedDEK: String, privateKey: PrivateKey): String {
            // Validate inputs
            if (encryptedContent.isEmpty()) {
                throw IllegalArgumentException("Encrypted content cannot be empty")
            }

            if (encryptedDEK.isEmpty()) {
                throw IllegalArgumentException("Encrypted DEK cannot be empty")
            }

            // Step 1: Parse IV and ciphertext
            val parts = encryptedContent.split('.')
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid encrypted content format. Expected 'iv.ciphertext'")
            }

            val ivBase64 = parts[0]
            val ciphertextBase64 = parts[1]

            if (ivBase64.isEmpty() || ciphertextBase64.isEmpty()) {
                throw IllegalArgumentException("Invalid encrypted content format. Missing IV or ciphertext")
            }

            return try {
                val iv = base64Decode(ivBase64)
                val ciphertext = base64Decode(ciphertextBase64)
                val encryptedDEKBytes = base64Decode(encryptedDEK)

                // Step 2: Decrypt DEK with RSA private key
                val rawDEK = RSACrypto.decrypt(privateKey, encryptedDEKBytes)

                // Step 3: Import DEK
                val dek = importDEK(rawDEK)

                // Step 4: Decrypt content with DEK
                AESCrypto.decryptToString(dek, ciphertext, iv)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Envelope decryption failed: ${e.message}", e)
            }
        }

        /**
         * Decrypt content using a DEK directly (for shared content via URL hash).
         *
         * This method is used when the DEK is provided directly (e.g., from a URL hash fragment)
         * instead of being encrypted with RSA. This is useful for anonymous sharing scenarios.
         *
         * @param encryptedContent The encrypted content in format: "base64(iv).base64(ciphertext)"
         * @param dekBase64 The DEK as a Base64 string (from URL hash)
         * @return The decrypted plaintext content
         * @throws IllegalArgumentException if inputs are invalid
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptContentWithDEK(encryptedContent: String, dekBase64: String): String {
            // Validate inputs
            if (encryptedContent.isEmpty()) {
                throw IllegalArgumentException("Encrypted content cannot be empty")
            }

            if (dekBase64.isEmpty()) {
                throw IllegalArgumentException("DEK cannot be empty")
            }

            // Parse IV and ciphertext
            val parts = encryptedContent.split('.')
            if (parts.size != 2) {
                throw IllegalArgumentException("Invalid encrypted content format. Expected 'iv.ciphertext'")
            }

            val ivBase64 = parts[0]
            val ciphertextBase64 = parts[1]

            if (ivBase64.isEmpty() || ciphertextBase64.isEmpty()) {
                throw IllegalArgumentException("Invalid encrypted content format. Missing IV or ciphertext")
            }

            return try {
                val iv = base64Decode(ivBase64)
                val ciphertext = base64Decode(ciphertextBase64)
                val rawDEK = base64Decode(dekBase64)

                // Import DEK
                val dek = importDEK(rawDEK)

                // Decrypt content
                AESCrypto.decryptToString(dek, ciphertext, iv)
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("DEK decryption failed: ${e.message}", e)
            }
        }

        /**
         * Encrypt content for anonymous users (URL hash DEK sharing).
         *
         * This method creates an encrypted content that can be decrypted by anyone who has
         * access to the DEK (e.g., via a URL hash fragment). The DEK is not encrypted with RSA,
         * but returned in raw form to be included in the shareable URL.
         *
         * Use case: Anonymous snippet sharing where the URL contains the DEK in the hash fragment.
         * This allows recipients to decrypt without having an account or private key.
         *
         * @param content The plaintext content to encrypt
         * @return AnonymousEncryptionResult containing encrypted content, raw DEK, and IV
         * @throws IllegalArgumentException if content is empty
         * @throws RuntimeException if encryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun encryptContentAnonymous(content: String): AnonymousEncryptionResult {
            // Validate inputs
            if (content.isEmpty()) {
                throw IllegalArgumentException("Content cannot be empty")
            }

            return try {
                // Step 1: Generate a new DEK for this content
                val dek = generateDEK()

                // Step 2: Generate IV
                val iv = AESCrypto.generateIV()

                // Step 3: Encrypt content with DEK
                val encryptedContent = AESCrypto.encrypt(dek, content, iv)

                // Step 4: Export DEK as raw bytes (to be included in URL hash)
                val rawDEK = exportDEK(dek)

                // Format: base64(iv).base64(ciphertext)
                val ivBase64 = base64Encode(iv)
                val encryptedContentBase64 = base64Encode(encryptedContent)

                AnonymousEncryptionResult(
                    content = "$ivBase64.$encryptedContentBase64",
                    dek = rawDEK,
                    iv = ivBase64
                )
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Anonymous encryption failed: ${e.message}", e)
            }
        }

        /**
         * Decrypt content using DEK from URL hash (for anonymous users).
         *
         * This is an alias for decryptContentWithDEK() for clarity in the anonymous use case.
         *
         * @param encryptedContent The encrypted content in format: "base64(iv).base64(ciphertext)"
         * @param dekBase64 The DEK as a Base64 string (from URL hash)
         * @return The decrypted plaintext content
         * @throws IllegalArgumentException if inputs are invalid
         * @throws RuntimeException if decryption fails
         */
        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun decryptContentAnonymous(encryptedContent: String, dekBase64: String): String {
            return decryptContentWithDEK(encryptedContent, dekBase64)
        }

        /**
         * Create a shareable URL with DEK in hash (for anonymous snippet sharing).
         *
         * @param baseUrl The base URL for the snippet (e.g., "/s/abc123")
         * @param rawDEK The raw DEK bytes
         * @return The full shareable URL with DEK in the hash fragment
         */
        @JvmStatic
        fun createAnonymousShareableUrl(baseUrl: String, rawDEK: ByteArray): String {
            val dekBase64 = base64Encode(rawDEK)
            return "$baseUrl#$dekBase64"
        }

        /**
         * Extract DEK from a URL hash fragment.
         *
         * @param url The full URL with hash fragment (e.g., "https://example.com/s/abc123#DEK")
         * @return The DEK as a Base64 string, or null if not present
         */
        @JvmStatic
        fun getDEKFromUrlHash(url: String): String? {
            val hashIndex = url.lastIndexOf('#')
            if (hashIndex == -1 || hashIndex == url.length - 1) {
                return null
            }

            val hash = url.substring(hashIndex + 1)
            if (hash.isEmpty()) {
                return null
            }

            // Validate it looks like a base64 string (basic validation)
            if (!hash.matches(Regex("^[A-Za-z0-9+/=_\\-]+$"))) {
                return null
            }

            return hash
        }

        /**
         * Clear DEK from URL hash (for security).
         *
         * @param url The URL with hash fragment
         * @return The URL without the hash fragment
         */
        @JvmStatic
        fun clearDEKFromUrlHash(url: String): String {
            val hashIndex = url.lastIndexOf('#')
            return if (hashIndex == -1) {
                url
            } else {
                url.substring(0, hashIndex)
            }
        }

        /**
         * Encode bytes to Base64 string.
         *
         * @param data The bytes to encode
         * @return Base64-encoded string
         */
        private fun base64Encode(data: ByteArray): String {
            return Base64.getEncoder().encodeToString(data)
        }

        /**
         * Decode Base64 string to bytes.
         *
         * @param data The Base64-encoded string
         * @return Decoded byte array
         */
        private fun base64Decode(data: String): ByteArray {
            return Base64.getDecoder().decode(data)
        }
    }
}
