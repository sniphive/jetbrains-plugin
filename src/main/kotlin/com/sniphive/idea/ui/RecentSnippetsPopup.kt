package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.google.gson.JsonParser
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.AESCrypto
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.RSACrypto
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.SecureCredentialStorage
import com.sniphive.idea.services.SnippetLookupService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.crypto.spec.SecretKeySpec
import javax.swing.*

/**
 * Popup for selecting and inserting recent snippets.
 *
 * Features:
 * - Shows recently used/created snippets
 * - Quick search/filter
 * - Enter to insert
 */
class RecentSnippetsPopup(private val project: Project, private val editor: Editor) {

    companion object {
        private val LOG = Logger.getInstance(RecentSnippetsPopup::class.java)

        // In-memory recent snippets (could be persisted)
        private val recentSnippetIds = mutableListOf<String>()
        private const val MAX_RECENT = 20

        /**
         * Add a snippet to recent list.
         */
        fun addToRecent(snippetId: String) {
            // Remove if exists
            recentSnippetIds.remove(snippetId)
            // Add to front
            recentSnippetIds.add(0, snippetId)
            // Trim to max size
            while (recentSnippetIds.size > MAX_RECENT) {
                recentSnippetIds.removeAt(recentSnippetIds.size - 1)
            }
        }

        /**
         * Get recent snippet IDs.
         */
        fun getRecentIds(): List<String> = recentSnippetIds.toList()
    }

    fun showPopup() {
        val lookupService = SnippetLookupService.getInstance(project)
        val allSnippets = lookupService.getAllSnippets()

        // Get recent snippets (by ID)
        val recentSnippets = recentSnippetIds
            .mapNotNull { id -> allSnippets.find { it.id == id } }
            .filter { !it.isArchived() }
            .take(10)

        // If no recent, show all snippets
        val snippetsToShow = if (recentSnippets.isNotEmpty()) {
            recentSnippets
        } else {
            allSnippets.filter { !it.isArchived() }.take(20)
        }

        if (snippetsToShow.isEmpty()) {
            showEmptyPopup()
            return
        }

        val popupStep = object : BaseListPopupStep<Snippet>("Recent Snippets", snippetsToShow) {

            override fun getTextFor(value: Snippet): String {
                val language = value.language ?: ""
                val preview = value.content.lines().firstOrNull()?.take(50) ?: ""
                return "${value.title} ($language)"
            }

            override fun getIconFor(value: Snippet): Icon? {
                return null // Could add language-specific icons
            }

            override fun onChosen(value: Snippet?, finalChoice: Boolean): PopupStep<*>? {
                if (value != null) {
                    insertSnippet(value)
                    addToRecent(value.id)
                }
                return PopupStep.FINAL_CHOICE
            }

            override fun hasSubstep(selectedValue: Snippet?): Boolean = false

            override fun isMnemonicsNavigationEnabled(): Boolean = true
        }

        val popup = JBPopupFactory.getInstance().createListPopup(popupStep)
        popup.showInBestPositionFor(editor)
    }

    private fun showEmptyPopup() {
        val popup = JBPopupFactory.getInstance().createMessage("No snippets available")
        popup.showInBestPositionFor(editor)
    }

    private fun insertSnippet(snippet: Snippet) {
        // Get snippet content (decrypt if needed)
        val content = getSnippetContent(snippet)

        if (content == null) {
            LOG.warn("Failed to get content for snippet: ${snippet.id}")
            return
        }

        ApplicationManager.getApplication().runWriteAction {
            val document = editor.document
            val caretOffset = editor.caretModel.offset

            document.insertString(caretOffset, content)
            editor.caretModel.moveToOffset(caretOffset + content.length)
        }

        LOG.debug("Inserted snippet: ${snippet.id}")
    }

    /**
     * Get the decrypted content of a snippet.
     *
     * If E2EE is enabled and the snippet is encrypted, this method:
     * 1. Retrieves the user's private key from secure storage
     * 2. Decrypts the data encryption key (DEK) with the private key
     * 3. Decrypts the snippet content with the DEK
     *
     * If the snippet is not encrypted, returns the content as-is.
     *
     * @param snippet The snippet to get content from
     * @return The decrypted content, or null if decryption failed
     */
    private fun getSnippetContent(snippet: Snippet): String? {
        return if (snippet.isEncrypted()) {
            decryptSnippetContent(snippet)
        } else {
            snippet.content
        }
    }

    /**
     * Decrypt encrypted snippet content using E2EE.
     *
     * This method:
     * 1. Retrieves the E2EE profile from settings
     * 2. Gets the private key from secure storage
     * 3. Parses the encrypted envelope
     * 4. Decrypts the DEK with the private key
     * 5. Decrypts the content with the DEK
     *
     * @param snippet The encrypted snippet
     * @return The decrypted content, or null if decryption failed
     */
    private fun decryptSnippetContent(snippet: Snippet): String? {
        return try {
            val settings = SnipHiveSettings.getInstance()
            val secureStorage = SecureCredentialStorage.getInstance()
            val email = settings.getUserEmail()

            if (email.isEmpty()) {
                LOG.warn("User email not available for snippet decryption")
                return null
            }

            // Get private key from secure storage
            val privateKeyJwkStr = secureStorage.getPrivateKey(project, email)

            if (privateKeyJwkStr == null) {
                LOG.warn("Private key not available for snippet decryption - user may need to unlock E2EE")
                return snippet.content // Return encrypted content as fallback
            }

            // Parse private key JWK
            val privateKeyJWK = JsonParser.parseString(privateKeyJwkStr).asJsonObject
            val privateKey = RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)

            // Decrypt content using envelope encryption
            val encryptedDek = E2EECryptoService.base64ToArrayBuffer(snippet.encryptedDek ?: "")
            if (encryptedDek.isEmpty()) {
                LOG.warn("No encrypted DEK for snippet: ${snippet.id}")
                return snippet.content
            }

            val dekBytes = RSACrypto.decrypt(privateKey, encryptedDek)
            val dek = SecretKeySpec(dekBytes, "AES")

            // Parse encrypted content as envelope (format: base64(iv).base64(ciphertext))
            val parts = snippet.content.split('.')
            if (parts.size != 2) {
                LOG.warn("Invalid encrypted content format for snippet: ${snippet.id}")
                return snippet.content
            }

            val contentIv = E2EECryptoService.base64ToArrayBuffer(parts[0])
            val ciphertext = E2EECryptoService.base64ToArrayBuffer(parts[1])

            // Decrypt content
            val decryptedBytes = AESCrypto.decrypt(dek, ciphertext, contentIv)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.error("Failed to decrypt snippet content", e)
            snippet.content // Return original content as fallback
        }
    }
}