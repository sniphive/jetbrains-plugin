package com.sniphive.idea.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
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
     */
    fun openSnippetInEditor(project: Project, snippet: Snippet) {
        val settings = SnipHiveSettings.getInstance(project)
        val email = settings.getUserEmail()

        // Decrypt content if encrypted
        val decryptedContent = if (snippet.encryptedDek != null) {
            E2EEContentService.decryptContentForEdit(
                project = project,
                email = email,
                encryptedContent = snippet.content,
                encryptedDek = snippet.encryptedDek
            )
        } else {
            snippet.content ?: ""
        }

        val virtualFile = SnippetVirtualFile(snippet, decryptedContent)
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
    }

    /**
     * Open a note in the editor.
     * Automatically decrypts content if E2EE is enabled.
     */
    fun openNoteInEditor(project: Project, note: Note) {
        val settings = SnipHiveSettings.getInstance(project)
        val email = settings.getUserEmail()

        // Decrypt content if encrypted
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
        FileEditorManager.getInstance(project).openFile(virtualFile, true)
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