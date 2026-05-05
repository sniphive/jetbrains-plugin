package com.sniphive.idea.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.E2EEProfile
import com.sniphive.idea.crypto.RSACrypto
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.SecureCredentialStorage
import com.sniphive.idea.services.SnipHiveApiService
import com.sniphive.idea.ui.MasterPasswordDialog
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Utility functions for opening snippets and notes in the editor.
 * Handles E2EE decryption when opening encrypted content.
 */
object EditorUtils {

    /**
     * Open a snippet in the editor.
     * Automatically decrypts content if E2EE is enabled.
     * 
     * EDT-safe: Decryption runs on background thread, file opening on EDT.
     */
    fun openSnippetInEditor(project: Project, snippet: Snippet) {
        // Run heavy operations (decryption) on background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = SnipHiveSettings.getInstance(project)
            val email = settings.getUserEmail()
            val apiService = SnipHiveApiService.getInstance()
            val snippetForEdit = apiService.getSnippet(project, snippet.slug ?: snippet.id) ?: snippet

            if (snippetForEdit.encryptedDek != null && !ensureE2EEUnlocked(project, email) {
                    openSnippetInEditor(project, snippet)
                }) {
                return@executeOnPooledThread
            }

            // Decrypt content if encrypted (heavy operation)
            val decryptedContent = if (snippetForEdit.encryptedDek != null) {
                E2EEContentService.decryptContentForEdit(
                    project = project,
                    email = email,
                    encryptedContent = snippetForEdit.content,
                    encryptedDek = snippetForEdit.encryptedDek
                )
            } else {
                snippetForEdit.content ?: ""
            }

            val virtualFile = SnippetVirtualFile(snippetForEdit, decryptedContent)

            // Open file on EDT (UI operation)
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    /**
     * Open a note in the editor.
     * Automatically decrypts content if E2EE is enabled.
     * 
     * EDT-safe: Decryption runs on background thread, file opening on EDT.
     */
    fun openNoteInEditor(project: Project, note: Note) {
        // Run heavy operations (decryption) on background thread to avoid EDT blocking
        ApplicationManager.getApplication().executeOnPooledThread {
            val settings = SnipHiveSettings.getInstance(project)
            val email = settings.getUserEmail()

            if (note.encryptedDek != null && !ensureE2EEUnlocked(project, email) {
                    openNoteInEditor(project, note)
                }) {
                return@executeOnPooledThread
            }

            // Decrypt content if encrypted (heavy operation)
            val decryptedContent = if (note.encryptedDek != null) {
                E2EEContentService.decryptContentForEdit(
                    project = project,
                    email = email,
                    encryptedContent = note.content,
                    encryptedDek = note.encryptedDek
                )
            } else {
                note.content ?: ""
            }

            val virtualFile = NoteVirtualFile(note, decryptedContent)

            // Open file on EDT (UI operation)
            ApplicationManager.getApplication().invokeLater {
                FileEditorManager.getInstance(project).openFile(virtualFile, true)
            }
        }
    }

    private fun ensureE2EEUnlocked(
        project: Project,
        email: String,
        retryAfterUnlock: () -> Unit
    ): Boolean {
        if (email.isEmpty()) {
            showUnlockError(project, "User email is missing. Please log in again.")
            return false
        }

        if (E2EEContentService.isE2EEUnlocked(project, email)) {
            return true
        }

        val securityStatus = SnipHiveApiService.getInstance().getSecurityStatus(project)
        val profile = securityStatus?.e2eeProfile
        if (securityStatus?.setupComplete != true || profile == null) {
            showUnlockError(project, "E2EE profile could not be loaded. Please try refreshing SnipHive.")
            return false
        }

        if (tryAutoUnlock(project, email, profile)) {
            return true
        }

        ApplicationManager.getApplication().invokeLater {
            val dialog = MasterPasswordDialog(project, email, profile)
            if (dialog.showAndGet() && dialog.isUnlockSuccessful()) {
                retryAfterUnlock()
            }
        }

        return false
    }

    private fun tryAutoUnlock(project: Project, email: String, profile: E2EEProfile): Boolean {
        val secureStorage = SecureCredentialStorage.getInstance()
        val storedMasterPassword = secureStorage.getMasterPassword(project, email)
            ?: return false

        return try {
            val privateKey = E2EECryptoService.unlockWithMasterPassword(storedMasterPassword, profile)
            val privateKeyJwk = RSACrypto.exportPrivateKeyToJWK(privateKey).toString()
            secureStorage.storePrivateKey(project, email, privateKeyJwk)
            SnipHiveSettings.getInstance(project).apply {
                setRememberMasterPassword(true)
                setE2eeUnlocked(true)
            }
            true
        } catch (e: Exception) {
            secureStorage.removeMasterPassword(project, email)
            false
        }
    }

    private fun showUnlockError(project: Project, message: String) {
        ApplicationManager.getApplication().invokeLater {
            Messages.showErrorDialog(project, message, "SnipHive E2EE")
        }
    }
}

/**
 * Debounced saver for auto-save functionality.
 */
class DebouncedSaver(private val saveAction: () -> Unit) {
    private var saveFuture: ScheduledFuture<*>? = null
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val delayMs = 2000L

    fun onContentChanged() {
        saveFuture?.cancel(false)
        saveFuture = scheduler.schedule({
            ApplicationManager.getApplication().invokeLater {
                try { saveAction() } catch (e: Exception) { }
            }
        }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun saveNow() {
        saveFuture?.cancel(false)
        saveFuture = null
        ApplicationManager.getApplication().invokeLater {
            try { saveAction() } catch (e: Exception) { }
        }
    }

    fun dispose() {
        saveFuture?.cancel(false)
        scheduler.shutdown()
    }
}
