package com.sniphive.idea.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sniphive.idea.crypto.EnvelopeEncryption
import com.sniphive.idea.services.SecureCredentialStorage
import java.security.PrivateKey

/**
 * E2EE Content Service - Middleware for encrypting/decrypting snippet and note content.
 *
 * This service acts as a middleware between the editor and API, handling:
 * - Decryption when loading encrypted content
 * - Encryption when saving content
 */
object E2EEContentService {

    private val LOG = Logger.getInstance(E2EEContentService::class.java)

    /**
     * Decrypt encrypted content for editing.
     *
     * @param project The current project
     * @param email The user's email
     * @param encryptedContent The encrypted content (format: "iv.ciphertext")
     * @param encryptedDek The encrypted DEK
     * @return The decrypted plaintext content, or the original content if not encrypted
     */
    fun decryptContentForEdit(
        project: Project,
        email: String,
        encryptedContent: String?,
        encryptedDek: String?
    ): String {
        // If no encrypted DEK, content is not encrypted
        if (encryptedDek.isNullOrEmpty() || encryptedContent.isNullOrEmpty()) {
            return encryptedContent ?: ""
        }

        return try {
            // Get the decrypted private key from secure storage
            val secureStorage = SecureCredentialStorage.getInstance()
            val privateKeyJwk = secureStorage.getPrivateKey(project, email)

            if (privateKeyJwk.isNullOrEmpty()) {
                LOG.warn("No private key available for decryption - user may need to unlock E2EE")
                return "[Encrypted - Unlock E2EE to view]"
            }

            // Parse private key from JWK
            val privateKey = parsePrivateKeyFromJWK(privateKeyJwk)

            // Decrypt content using envelope encryption
            EnvelopeEncryption.decryptContent(encryptedContent, encryptedDek, privateKey)
        } catch (e: Exception) {
            LOG.error("Failed to decrypt content", e)
            "[Decryption failed: ${e.message}]"
        }
    }

    /**
     * Encrypt content for saving.
     *
     * @param project The current project
     * @param email The user's email
     * @param content The plaintext content to encrypt
     * @return EncryptedContentResult with encrypted content and encrypted DEK, or null if encryption fails
     */
    fun encryptContentForSave(
        project: Project,
        email: String,
        content: String
    ): EncryptedContentResult? {
        return try {
            // Get the public key from secure storage
            val secureStorage = SecureCredentialStorage.getInstance()
            val publicKeyJwk = secureStorage.getPublicKey(project, email)

            if (publicKeyJwk.isNullOrEmpty()) {
                LOG.warn("No public key available for encryption")
                return null
            }

            // Parse public key from JWK
            val publicKey = parsePublicKeyFromJWK(publicKeyJwk)

            // Encrypt content using envelope encryption
            val result = EnvelopeEncryption.encryptContent(content, publicKey)

            EncryptedContentResult(
                encryptedContent = result.content,
                encryptedDek = result.encryptedDEK
            )
        } catch (e: Exception) {
            LOG.error("Failed to encrypt content", e)
            null
        }
    }

    /**
     * Check if content is encrypted.
     */
    fun isEncrypted(encryptedDek: String?): Boolean {
        return !encryptedDek.isNullOrEmpty()
    }

    /**
     * Check if E2EE is unlocked (private key available).
     */
    fun isE2EEUnlocked(project: Project, email: String): Boolean {
        val secureStorage = SecureCredentialStorage.getInstance()
        return secureStorage.getPrivateKey(project, email) != null
    }

    private fun parsePrivateKeyFromJWK(jwk: String): PrivateKey {
        val jsonObject = com.google.gson.JsonParser.parseString(jwk).asJsonObject
        return com.sniphive.idea.crypto.RSACrypto.importPrivateKeyFromJWK(jsonObject)
    }

    private fun parsePublicKeyFromJWK(jwk: String): java.security.PublicKey {
        val jsonObject = com.google.gson.JsonParser.parseString(jwk).asJsonObject
        return com.sniphive.idea.crypto.RSACrypto.importPublicKeyFromJWK(jsonObject)
    }

    /**
     * Result of content encryption.
     */
    data class EncryptedContentResult(
        val encryptedContent: String,
        val encryptedDek: String
    )
}