package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.ui.InsertSnippetDialog
import com.sniphive.idea.ui.RecentSnippetsPopup
import com.google.gson.JsonParser
import com.sniphive.idea.crypto.AESCrypto
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.RSACrypto
import com.sniphive.idea.services.SecureCredentialStorage
import javax.crypto.spec.SecretKeySpec

/**
 * Action to insert a SnipHive snippet at the cursor position in the editor.
 *
 * This action:
 * 1. Checks if the user is authenticated with SnipHive
 * 2. Opens the InsertSnippetDialog to search and select a snippet
 * 3. Inserts the selected snippet content at the cursor position
 *
 * The action is available in:
 * - Code generation menu (Alt+Insert on Windows/Linux, Cmd+N on Mac)
 * - Editor context menu (after Create Snippet action)
 * - Keyboard shortcut: Shift+Alt+I
 *
 * Security Note:
 * - No credentials or tokens logged or exposed
 * - Authentication check performed before action execution
 * - Content insertion happens client-side only
 * - E2EE decryption performed client-side with private key from secure storage
 */
class InsertSnippetAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(InsertSnippetAction::class.java)
    }

    /**
     * Get the action update thread (EDT for UI updates).
     */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    /**
     * Update the action's enabled state based on context.
     *
     * The action is enabled when:
     * - A project is open
     * - User is authenticated with SnipHive
     * - There is an active editor
     *
     * @param event The action event containing context information
     */
    override fun update(event: AnActionEvent) {
        val project = event.project
        val editor = event.getData(CommonDataKeys.EDITOR)
        val settings = project?.let { SnipHiveSettings.getInstance(it) }
        val authService = SnipHiveAuthService.getInstance()
        val isAuthenticated = project?.let { authService.isCurrentAuthenticated(it) } ?: false

        val isEnabled = project != null &&
                editor != null &&
                isAuthenticated &&
                settings != null &&
                settings.getApiUrl().isNotEmpty()

        event.presentation.isEnabledAndVisible = isEnabled
    }

    /**
     * Perform the action when triggered.
     *
     * This method:
     * 1. Validates that editor is available
     * 2. Opens the InsertSnippetDialog to select a snippet
     * 3. If a snippet is selected, inserts its content at cursor position
     *
     * @param event The action event containing context information
     */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: run {
            LOG.warn("No project available")
            return
        }

        val editor = event.getData(CommonDataKeys.EDITOR) ?: run {
            LOG.warn("No editor available")
            return
        }

        LOG.debug("Insert snippet action triggered in project: ${project.name}")

        // Open insert snippet dialog
        ApplicationManager.getApplication().invokeLater {
            try {
                val dialog = InsertSnippetDialog(project)

                if (dialog.showAndGet()) {
                    val snippet = dialog.getSelectedSnippet()
                    if (snippet != null) {
                        insertSnippet(project, editor, snippet)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error opening insert snippet dialog", e)
            }
        }
    }

    /**
     * Insert snippet content into the editor.
     *
     * This method:
     * 1. Decrypts the snippet content if E2EE is enabled
     * 2. Inserts the content at cursor position
     * 3. Positions the caret appropriately
     *
     * @param project The current project
     * @param editor The editor to insert into
     * @param snippet The snippet to insert
     */
    private fun insertSnippet(project: Project, editor: Editor, snippet: Snippet) {
        // Get snippet content (decrypt if needed)
        val content = getSnippetContent(project, snippet)

        if (content == null) {
            LOG.warn("Failed to get content for snippet: ${snippet.id}")
            return
        }

        // Insert on EDT with write action
        ApplicationManager.getApplication().invokeLater {
            ApplicationManager.getApplication().runWriteAction {
                try {
                    val document: Document = editor.document
                    val caretOffset = editor.caretModel.offset

                    // Check if offset is valid
                    if (caretOffset < 0 || caretOffset > document.textLength) {
                        LOG.warn("Invalid caret offset: $caretOffset, document length: ${document.textLength}")
                        return@runWriteAction
                    }

                    // Insert content at cursor position
                    document.insertString(caretOffset, content)

                    // Move caret to end of inserted content
                    val newCaretOffset = caretOffset + content.length
                    editor.caretModel.moveToOffset(newCaretOffset)

                    // Track as recent snippet
                    RecentSnippetsPopup.addToRecent(snippet.id)

                    LOG.debug("Inserted snippet '${snippet.title}' at offset $caretOffset, length: ${content.length} chars")
                } catch (e: Exception) {
                    LOG.error("Failed to insert snippet", e)
                }
            }
        }
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
     * @param project The current project
     * @param snippet The snippet to get content from
     * @return The decrypted content, or null if decryption failed
     */
    private fun getSnippetContent(project: Project, snippet: Snippet): String? {
        return if (snippet.isEncrypted()) {
            decryptSnippetContent(project, snippet)
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
     * @param project The current project
     * @param snippet The encrypted snippet
     * @return The decrypted content, or null if decryption failed
     */
    private fun decryptSnippetContent(project: Project, snippet: Snippet): String? {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val secureStorage = SecureCredentialStorage.getInstance()
            val email = settings.getUserEmail()

            if (email.isEmpty()) {
                LOG.warn("User email not available for snippet decryption")
                return null
            }

            // Get private key from secure storage (already decrypted)
            val privateKeyJwkStr = secureStorage.getPrivateKey(project, email)

            if (privateKeyJwkStr == null) {
                LOG.warn("Private key not available for snippet decryption - user may need to unlock E2EE")
                return null // Do NOT return encrypted content
            }

            // Parse private key JWK
            val privateKeyJWK = JsonParser.parseString(privateKeyJwkStr).asJsonObject
            val privateKey = RSACrypto.importPrivateKeyFromJWK(privateKeyJWK)

            // Decrypt content using envelope encryption
            val encryptedDek = E2EECryptoService.base64ToArrayBuffer(snippet.encryptedDek ?: "")
            if (encryptedDek.isEmpty()) {
                LOG.warn("No encrypted DEK for snippet: ${snippet.id}")
                return null // Do NOT return encrypted content
            }

            val dekBytes = RSACrypto.decrypt(privateKey, encryptedDek)
            val dek = SecretKeySpec(dekBytes, "AES")

            // Parse encrypted content as envelope (format: base64(iv).base64(ciphertext))
            val parts = snippet.content.split('.')
            if (parts.size != 2) {
                LOG.warn("Invalid encrypted content format for snippet: ${snippet.id}")
                return null // Do NOT return encrypted content
            }

            val contentIv = E2EECryptoService.base64ToArrayBuffer(parts[0])
            val ciphertext = E2EECryptoService.base64ToArrayBuffer(parts[1])

            // Decrypt content
            val decryptedBytes = AESCrypto.decrypt(dek, ciphertext, contentIv)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            LOG.error("Failed to decrypt snippet content", e)
            null // Do NOT return encrypted content on failure
        }
    }
}