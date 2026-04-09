package com.sniphive.idea.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.RSACrypto
import com.sniphive.idea.services.SecureCredentialStorage
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Centralized utility for handling item actions (copy, edit, delete, copy public URL)
 * across both snippet and note list/detail panels.
 *
 * This class provides the shared action methods invoked by toolbar buttons
 * in SnippetListPanel and NoteListPanel.
 *
 * Usage:
 * ```kotlin
 * val handler = ItemActionHandler(project)
 * handler.copyContent(content, itemId, isEncrypted)
 * handler.editItem("My Title", "my content") { newTitle, newContent -> ... }
 * handler.deleteItem("My Title") { /* confirmed */ }
 * handler.copyPublicUrl("https://sniphive.net/s/abc")
 * ```
 *
 * Security Note:
 * - Encrypted content copy shows a warning dialog and does not proceed
 * - Delete confirmation is always required
 * - Public URL copy is only called when isPublic && publicUrl != null
 */
class ItemActionHandler(private val project: Project) {

    companion object {
        private val LOG = Logger.getInstance(ItemActionHandler::class.java)
        private const val MAX_CONTENT_LENGTH = 500_000
    }

    /**
     * Show a non-intrusive toast notification (balloon) instead of a modal dialog.
     */
    private fun showNotification(message: String, type: NotificationType = NotificationType.INFORMATION) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("SnipHive Notifications")
            .createNotification(message, type)
            .notify(project)
    }

    /**
     * Copy content to system clipboard.
     * Shows encrypted content warning if the item is encrypted.
     *
     * @param content The content to copy
     * @param itemId The item ID for logging purposes
     * @param isEncrypted Whether the item is encrypted (prevents copy if true)
     */
    fun copyContent(content: String, itemId: String, isEncrypted: Boolean) {
        if (isEncrypted) {
            Messages.showWarningDialog(
                project,
                "This content is encrypted. Cannot copy encrypted content.",
                "Cannot Copy Encrypted Content"
            )
            return
        }

        try {
            val textToCopy = content.take(MAX_CONTENT_LENGTH)

            if (content.length > MAX_CONTENT_LENGTH) {
                showNotification("Content truncated to ${MAX_CONTENT_LENGTH / 1000}KB", NotificationType.WARNING)
            }

            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(textToCopy)
            clipboard.setContents(selection, null)

            LOG.debug("Copied content to clipboard for item: $itemId")
            showNotification("Content copied to clipboard!")
        } catch (e: Exception) {
            LOG.error("Failed to copy to clipboard for item: $itemId", e)
            showNotification("Failed to copy content to clipboard: ${e.message}", NotificationType.ERROR)
        }
    }

    /**
     * Show an edit dialog for an item (snippet or note).
     * Calls the onSave callback with new title and content only if the user confirms.
     *
     * @param title The current item title
     * @param content The current item content
     * @param dialogTitle The title of the edit dialog (e.g. "Edit Snippet" or "Edit Note")
     * @param contentLabel The label for the content field (e.g. "Content:" or "Content (Markdown):")
     * @param onSave Callback receiving (newTitle, newContent) when user confirms
     */
    fun editItem(
        title: String,
        content: String,
        dialogTitle: String = "Edit Item",
        contentLabel: String = "Content:",
        onSave: (newTitle: String, newContent: String) -> Unit
    ) {
        val dialog = object : DialogWrapper(project) {
            private val titleField = JBTextField(title)
            private val contentArea = JBTextArea(content)

            init {
                this.title = dialogTitle
                contentArea.rows = 15
                contentArea.columns = 50
                contentArea.lineWrap = true
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(VerticalLayout(10))
                panel.add(com.intellij.ui.components.JBLabel("Title:"))
                panel.add(titleField)
                panel.add(com.intellij.ui.components.JBLabel(contentLabel))
                panel.add(JScrollPane(contentArea))
                return panel
            }

            fun getEditedTitle(): String = titleField.text.trim()
            fun getEditedContent(): String = contentArea.text
        }

        if (dialog.showAndGet()) {
            val newTitle = dialog.getEditedTitle()
            val newContent = dialog.getEditedContent()

            if (newTitle.isEmpty()) {
                Messages.showWarningDialog(project, "Title cannot be empty.", "Validation Error")
                return
            }

            onSave(newTitle, newContent)
        }
    }

    /**
     * Show a delete confirmation dialog for an item.
     * Calls the onConfirm callback only if the user confirms deletion.
     *
     * @param title The item title to display in the confirmation message
     * @param itemType The type label (e.g. "Snippet" or "Note")
     * @param onConfirm Callback invoked when user confirms deletion
     */
    fun deleteItem(
        title: String,
        itemType: String = "Item",
        onConfirm: () -> Unit
    ) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete '$title'?\n\nThis action cannot be undone.",
            "Delete $itemType",
            Messages.getYesButton(),
            Messages.getNoButton(),
            Messages.getWarningIcon()
        )

        if (confirmed == Messages.YES) {
            onConfirm()
        }
    }

    /**
     * Copy a public URL to the system clipboard.
     * For encrypted content, decrypts the DEK and appends it as a hash fragment.
     *
     * Security Note:
     * - For encrypted content, a warning notification is shown after copy
     * - Private key is retrieved from IDE Password Safe (never stored in plaintext)
     *
     * @param publicUrl The public URL to copy
     * @param encryptedDek The encrypted data encryption key (optional, for E2EE content)
     * @param email The user's email for private key lookup (optional)
     */
    fun copyPublicUrl(publicUrl: String, encryptedDek: String? = null, email: String? = null) {
        val hasDek = encryptedDek != null && email != null

        try {
            var urlToCopy = publicUrl

            // If encrypted content, try to decrypt DEK and append as hash
            if (hasDek) {
                val secureStorage = SecureCredentialStorage.getInstance()
                val privateKeyJwk = secureStorage.getPrivateKey(project, email)

                if (privateKeyJwk != null) {
                    try {
                        // Import private key from JWK
                        val privateKey = RSACrypto.importPrivateKeyFromJWK(privateKeyJwk)

                        // Decrypt the encrypted DEK
                        val encryptedDekBytes = E2EECryptoService.base64ToArrayBuffer(encryptedDek)
                        val dekBytes = RSACrypto.decrypt(privateKey, encryptedDekBytes)

                        // Convert DEK to base64 and append as hash
                        val dekBase64 = E2EECryptoService.arrayBufferToBase64(dekBytes)
                        urlToCopy = "$publicUrl#$dekBase64"

                        LOG.debug("Decrypted DEK and appended to public URL for encrypted content")
                    } catch (e: Exception) {
                        LOG.warn("Failed to decrypt DEK for public URL copy: ${e.message}")
                        // Graceful fallback - copy URL without DEK hash
                    }
                } else {
                    LOG.warn("Private key not available for DEK decryption - E2EE not unlocked")
                    // Graceful fallback - copy URL without DEK hash
                }
            }

            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val selection = StringSelection(urlToCopy)
            clipboard.setContents(selection, null)

            LOG.debug("Copied public URL to clipboard (length: ${urlToCopy.length})")
            
            // Show post-copy notification with security reminder
            if (hasDek) {
                showNotification("URL copied. Share carefully — anyone with this link can decrypt the content.", NotificationType.WARNING)
            } else {
                showNotification("Public URL copied to clipboard!")
            }
        } catch (e: Exception) {
            LOG.error("Failed to copy public URL to clipboard", e)
            showNotification("Failed to copy URL to clipboard: ${e.message}", NotificationType.ERROR)
        }
    }
}
