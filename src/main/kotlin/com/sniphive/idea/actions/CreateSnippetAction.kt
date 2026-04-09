package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnipHiveApiService
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.ui.CreateSnippetDialog

/**
 * Action to create a SnipHive snippet from selected code in the editor.
 *
 * This action:
 * 1. Checks if the user is authenticated with SnipHive
 * 2. Retrieves the selected text from the active editor
 * 3. Opens the CreateSnippetDialog to configure the snippet
 * 4. Sends the snippet to the SnipHive API
 *
 * The action is available in:
 * - Editor context menu (first position)
 * - Project view context menu (after "Edit Source" action)
 * - Keyboard shortcut: Shift+Alt+S
 *
 * Security Note:
 * - Code selection is read from the editor (no sensitive data exposure)
 * - Authentication check performed before action execution
 * - No credentials or tokens logged or exposed
 */
class CreateSnippetAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(CreateSnippetAction::class.java)
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
     * - There is an active editor with text
     * - There is a non-empty selection in the editor
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
                settings.getApiUrl().isNotEmpty() &&
                hasSelection(editor)

        event.presentation.isEnabledAndVisible = isEnabled
    }

    /**
     * Perform the action when triggered.
     *
     * This method:
     * 1. Retrieves the selected text from the editor
     * 2. Detects the programming language
     * 3. Opens the CreateSnippetDialog
     * 4. If the user confirms, creates the snippet via API
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

        val selectedText = getSelectedText(editor) ?: run {
            Messages.showWarningDialog(
                project,
                "Please select some code to create a snippet.",
                "No Selection"
            )
            return
        }

        val fileType = getFileType(project, editor)
        val language = fileType?.name ?: "Plain Text"

        LOG.debug("Creating snippet for language: $language, length: ${selectedText.length} chars")

        // Open create snippet dialog
        ApplicationManager.getApplication().invokeLater {
            openCreateSnippetDialog(project, selectedText, language)
        }
    }

    /**
     * Open the CreateSnippetDialog and handle the result.
     */
    private fun openCreateSnippetDialog(project: Project, selectedText: String, language: String) {
        try {
            val dialog = CreateSnippetDialog(project, selectedText, language)

            // Load tags and languages in background
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val apiService = SnipHiveApiService.getInstance()
                    val tags = apiService.getTags(project)

                    // Get unique languages from existing snippets (for now, use common languages)
                    val languages = getCommonLanguages()

                    ApplicationManager.getApplication().invokeLater {
                        dialog.setAvailableTags(tags)
                        dialog.setAvailableLanguages(languages)

                        // Show dialog
                        if (dialog.showAndGet()) {
                            val result = dialog.getCreateResult()
                            if (result != null) {
                                createSnippetViaApi(project, result)
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Error loading data for create snippet dialog", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to load tags: ${e.message}",
                            "Error"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            LOG.error("Error opening create snippet dialog", e)
            Messages.showErrorDialog(
                project,
                "Failed to open create snippet dialog: ${e.message}",
                "Error"
            )
        }
    }

    /**
     * Get common programming languages for the dropdown.
     */
    private fun getCommonLanguages(): List<String> {
        return listOf(
            "Kotlin", "Java", "JavaScript", "TypeScript", "Python", "PHP", "Ruby",
            "Go", "Rust", "Swift", "C", "C++", "C#", "SQL", "HTML", "CSS", "SCSS",
            "Bash", "Shell", "JSON", "YAML", "XML", "Markdown", "Dart", "Scala",
            "Groovy", "Perl", "Lua", "R", "MATLAB", "Objective-C"
        )
    }

    /**
     * Create snippet via API.
     */
    private fun createSnippetViaApi(project: Project, result: CreateSnippetDialog.CreateResult) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val tagIds = result.tags.map { it.id }

                val snippet = apiService.createSnippet(
                    project = project,
                    title = result.title,
                    content = result.content,
                    language = result.language,
                    tags = tagIds,
                    isPublic = result.isPublic
                )

                if (snippet != null) {
                    LOG.info("Snippet created successfully: ${snippet.id}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showInfoMessage(
                            project,
                            "Snippet '${result.title}' created successfully!",
                            "Snippet Created"
                        )
                    }
                } else {
                    LOG.warn("Failed to create snippet: ${result.title}")
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(
                            project,
                            "Failed to create snippet. Please check your connection and try again.",
                            "Create Failed"
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error creating snippet via API", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to create snippet: ${e.message}",
                        "Error"
                    )
                }
            }
        }
    }

    /**
     * Check if the editor has a non-empty selection.
     *
     * @param editor The editor to check
     * @return true if there is a non-empty selection
     */
    private fun hasSelection(editor: Editor): Boolean {
        val selectionModel = editor.selectionModel
        return selectionModel.hasSelection() && selectionModel.selectedText?.isNotEmpty() == true
    }

    /**
     * Get the selected text from the editor.
     *
     * @param editor The editor to get the selection from
     * @return The selected text, or null if no selection
     */
    private fun getSelectedText(editor: Editor): String? {
        val selectionModel = editor.selectionModel
        return if (selectionModel.hasSelection()) {
            selectionModel.selectedText
        } else {
            null
        }
    }

    /**
     * Get the file type of the current file in the editor.
     *
     * This is used to auto-populate the language field in the snippet dialog.
     *
     * @param project The project
     * @param editor The editor
     * @return The file type, or null if not available
     */
    private fun getFileType(project: Project, editor: Editor): FileType? {
        val virtualFile = editor.virtualFile ?: return null
        return virtualFile.fileType
    }
}