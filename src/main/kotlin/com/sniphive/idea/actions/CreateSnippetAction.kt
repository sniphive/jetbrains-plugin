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
import com.sniphive.idea.crypto.EnvelopeEncryption
import com.sniphive.idea.crypto.RSACrypto
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
        val settings = SnipHiveSettings.getInstance()
        val authService = SnipHiveAuthService.getInstance()
        val isAuthenticated = project?.let { authService.isCurrentAuthenticated(it) } ?: false

        // Debug: Log visibility conditions
        LOG.debug("CreateSnippetAction update: project=${project?.name}, editor=${editor != null}, isAuthenticated=$isAuthenticated, apiUrl=${settings.getApiUrl().takeIf { it.isNotEmpty() } ?: "empty"}")

        // Show action when authenticated (even without selection)
        val isVisible = project != null &&
                editor != null &&
                isAuthenticated &&
                settings.getApiUrl().isNotEmpty()

        // Enable only when there's a selection
        val isEnabled = isVisible && hasSelection(editor)

        LOG.debug("CreateSnippetAction: isVisible=$isVisible, isEnabled=$isEnabled")

        event.presentation.setVisible(isVisible)
        event.presentation.setEnabled(isEnabled)
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
     * Values must match the API's accepted language codes.
     */
    private fun getCommonLanguages(): List<String> {
        return listOf(
            "kotlin", "java", "javascript", "typescript", "python", "php", "ruby",
            "go", "rust", "swift", "c", "cpp", "csharp", "sql", "html", "css",
            "bash", "shell", "json", "yaml", "xml", "markdown", "plaintext",
            "text", "dart", "scala", "groovy", "perl", "lua", "r"
        )
    }

    /**
     * Normalize IDE file type name to API-compatible language code.
     * The API accepts only specific lowercase values (see StoreSnippetRequest validation).
     */
    private fun normalizeLanguage(fileTypeName: String): String {
        return when (fileTypeName.trim()) {
            "JavaScript" -> "javascript"
            "TypeScript" -> "typescript"
            "Python" -> "python"
            "PHP" -> "php"
            "Java" -> "java"
            "Go" -> "go"
            "Ruby" -> "ruby"
            "Rust" -> "rust"
            "C++" -> "cpp"
            "C" -> "c"
            "C#" -> "csharp"
            "SQL" -> "sql"
            "HTML" -> "html"
            "CSS" -> "css"
            "SCSS" -> "css"
            "Bash" -> "bash"
            "Shell Script" -> "shell"
            "JSON" -> "json"
            "YAML" -> "yaml"
            "XML" -> "xml"
            "Markdown" -> "markdown"
            "Plain Text" -> "plaintext"
            "Kotlin" -> "kotlin"
            "Swift" -> "swift"
            "Dart" -> "dart"
            "Scala" -> "scala"
            "Groovy" -> "groovy"
            "Perl" -> "perl"
            "Lua" -> "lua"
            "R" -> "r"
            "Objective-C" -> "objectivec"
            else -> fileTypeName.lowercase().replace("+", "p").replace("#", "sharp").replace(" ", "")
        }
    }

    /**
     * Create snippet via API.
     * If the user has E2EE enabled, content is encrypted before sending.
     */
    private fun createSnippetViaApi(project: Project, result: CreateSnippetDialog.CreateResult) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val tagIds = result.tags.map { it.id }

                // Check E2EE status and encrypt content if required
                val securityStatus = apiService.getSecurityStatus(project)
                val (snippetContent, encryptedDek) = if (
                    securityStatus?.setupComplete == true &&
                    securityStatus.e2eeProfile?.publicKeyJWK != null
                ) {
                    LOG.debug("E2EE is active - encrypting snippet content")
                    val publicKey = RSACrypto.importPublicKeyFromJWK(securityStatus.e2eeProfile.publicKeyJWK)
                    val encryptedResult = EnvelopeEncryption.encryptContent(result.content, publicKey)
                    Pair(encryptedResult.content, encryptedResult.encryptedDEK)
                } else {
                    LOG.debug("E2EE is not active - sending plaintext content")
                    Pair(result.content, null)
                }

                val snippet = apiService.createSnippet(
                    project = project,
                    title = result.title,
                    content = snippetContent,
                    language = normalizeLanguage(result.language),
                    tags = tagIds,
                    encryptedDek = encryptedDek,
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
