package com.sniphive.idea.crypto

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.intellij.openapi.diagnostic.Logger
import java.security.PrivateKey
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKey

/**
 * Result of E2EE setup operation.
 */
data class E2EESetupResult(
    val publicKeyJWK: JsonObject,
    val encryptedPrivateKey: String,
    val recoveryEncryptedPrivateKey: String,
    val privateKeyIV: String,
    val recoveryIV: String,
    val kdfSalt: String,
    val recoverySalt: String,
    val kdfIterations: Int,
    val recoveryCode: String
)

/**
 * E2EE Profile data stored on the server.
 */
data class E2EEProfile(
    @SerializedName("public_key_jwk")
    val publicKeyJWK: JsonObject,

    @SerializedName("encrypted_private_key")
    val encryptedPrivateKey: String? = null,

    @SerializedName("recovery_encrypted_private_key")
    val recoveryEncryptedPrivateKey: String? = null,

    @SerializedName("private_key_iv")
    val privateKeyIV: String? = null,

    @SerializedName("recovery_iv")
    val recoveryIV: String? = null,

    @SerializedName("kdf_salt")
    val kdfSalt: String? = null,

    @SerializedName("recovery_salt")
    val recoverySalt: String? = null,

    @SerializedName("kdf_iterations")
    val kdfIterations: Int? = null
)

/**
 * E2EE Crypto Service - Zero-Knowledge End-to-End Encryption
 */
class E2EECryptoService {

    companion object {
        private val LOG = Logger.getInstance(E2EECryptoService::class.java)

        // Security constants
        const val RECOVERY_CODE_LENGTH = 24
        const val RECOVERY_CODE_CHARACTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // Excludes I, O, 0, 1

        // ==========================================
        // UTILITY FUNCTIONS
        // ==========================================

        @JvmStatic
        fun arrayBufferToBase64(bytes: ByteArray): String {
            return Base64.getEncoder().encodeToString(bytes)
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun base64ToArrayBuffer(base64: String): ByteArray {
            if (base64.isEmpty()) {
                throw IllegalArgumentException("Base64 input cannot be empty")
            }
            val normalized = base64.replace("-", "+").replace("_", "/")
            return try {
                Base64.getDecoder().decode(normalized)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid base64 input: ${e.message}", e)
            }
        }

        @JvmStatic
        fun generateRandomBytes(length: Int): ByteArray {
            val bytes = ByteArray(length)
            SecureRandom().nextBytes(bytes)
            return bytes
        }

        @JvmStatic
        fun generateRecoveryCode(): String {
            val random = SecureRandom()
            val code = StringBuilder(RECOVERY_CODE_LENGTH)
            for (i in 0 until RECOVERY_CODE_LENGTH) {
                val randomIndex = random.nextInt(RECOVERY_CODE_CHARACTERS.length)
                code.append(RECOVERY_CODE_CHARACTERS[randomIndex])
            }
            return code.toString().chunked(4).joinToString("-")
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class)
        fun parseRecoveryCode(code: String): String {
            val cleaned = code.replace("-", "").uppercase()
            if (cleaned.length != RECOVERY_CODE_LENGTH) {
                throw IllegalArgumentException("Invalid recovery code length")
            }
            for (char in cleaned) {
                if (char !in RECOVERY_CODE_CHARACTERS) {
                    throw IllegalArgumentException("Invalid recovery code character: '$char'")
                }
            }
            return cleaned
        }

        // ==========================================
        // E2EE SETUP (ONBOARDING)
        // ==========================================

        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun setupE2EE(masterPassword: String): E2EESetupResult {
            if (masterPassword.isEmpty()) throw IllegalArgumentException("Master password cannot be empty")
            return try {
                val keyPair = RSACrypto.generateKeyPair()
                val salt = PBKDF2.generateSalt()
                val recoveryCode = generateRecoveryCode()
                val parsedRecoveryCode = parseRecoveryCode(recoveryCode)
                val privateKeyJWK = RSACrypto.exportPrivateKeyToJWK(keyPair.private)
                val privateKeyBytes = privateKeyJWK.toString().toByteArray(Charsets.UTF_8)
                val masterKey = PBKDF2.deriveKey(masterPassword, salt)
                val recoverySalt = PBKDF2.generateSalt()
                val recoveryKey = PBKDF2.deriveKey(parsedRecoveryCode, recoverySalt)
                val masterIV = AESCrypto.generateIV()
                val encryptedPrivateKey = AESCrypto.encrypt(masterKey, privateKeyBytes, masterIV)
                val recoveryIV = AESCrypto.generateIV()
                val recoveryEncryptedPrivateKey = AESCrypto.encrypt(recoveryKey, privateKeyBytes, recoveryIV)
                val publicKeyJWK = RSACrypto.exportPublicKeyToJWK(keyPair.public)

                E2EESetupResult(
                    publicKeyJWK = publicKeyJWK,
                    encryptedPrivateKey = arrayBufferToBase64(encryptedPrivateKey),
                    recoveryEncryptedPrivateKey = arrayBufferToBase64(recoveryEncryptedPrivateKey),
                    privateKeyIV = arrayBufferToBase64(masterIV),
                    recoveryIV = arrayBufferToBase64(recoveryIV),
                    kdfSalt = arrayBufferToBase64(salt),
                    recoverySalt = arrayBufferToBase64(recoverySalt),
                    kdfIterations = PBKDF2.PBKDF2_ITERATIONS,
                    recoveryCode = recoveryCode
                )
            } catch (e: Exception) {
                LOG.error("Failed to setup E2EE", e)
                throw RuntimeException("E2EE setup failed: ${e.message}", e)
            }
        }

        // ==========================================
        // UNLOCK (DECRYPT PRIVATE KEY)
        // ==========================================

        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun unlockWithMasterPassword(masterPassword: String, profile: E2EEProfile): PrivateKey {
            if (profile.kdfSalt == null || profile.privateKeyIV == null || 
                profile.encryptedPrivateKey == null || profile.kdfIterations == null) {
                throw IllegalArgumentException("E2EE profile incomplete")
            }

            return try {
                val salt = base64ToArrayBuffer(profile.kdfSalt)
                val iv = base64ToArrayBuffer(profile.privateKeyIV)
                val encryptedPrivateKey = base64ToArrayBuffer(profile.encryptedPrivateKey)
                val masterKey = PBKDF2.deriveKey(masterPassword, salt, profile.kdfIterations)
                val decryptedPrivateKeyJWK = AESCrypto.decryptToString(masterKey, encryptedPrivateKey, iv)
                val privateKeyJWK = JsonParser.parseString(decryptedPrivateKeyJWK).asJsonObject
                RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Failed to unlock. Invalid master password.", e)
            }
        }

        @JvmStatic
        @Throws(IllegalArgumentException::class, RuntimeException::class)
        fun unlockWithRecoveryCode(recoveryCode: String, profile: E2EEProfile): PrivateKey {
            if (profile.kdfSalt == null || profile.kdfIterations == null || 
                profile.recoveryIV == null || profile.recoveryEncryptedPrivateKey == null) {
                throw IllegalArgumentException("E2EE profile incomplete")
            }

            return try {
                val parsedCode = parseRecoveryCode(recoveryCode)
                val recoverySaltSource = profile.recoverySalt ?: profile.kdfSalt
                val recoverySalt = base64ToArrayBuffer(recoverySaltSource)
                val iv = base64ToArrayBuffer(profile.recoveryIV)
                val encryptedPrivateKey = base64ToArrayBuffer(profile.recoveryEncryptedPrivateKey)
                val recoveryKey = PBKDF2.deriveKey(parsedCode, recoverySalt, profile.kdfIterations)
                val decryptedPrivateKeyJWK = AESCrypto.decryptToString(recoveryKey, encryptedPrivateKey, iv)
                val privateKeyJWK = JsonParser.parseString(decryptedPrivateKeyJWK).asJsonObject
                RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)
            } catch (e: IllegalArgumentException) {
                // Re-throw validation errors directly (invalid recovery code format, etc.)
                throw e
            } catch (e: Exception) {
                // Note: Don't log before throwing - IntelliJ test framework rethrows logged errors
                throw RuntimeException("Failed to recover. Invalid recovery code.", e)
            }
        }
    }
}
