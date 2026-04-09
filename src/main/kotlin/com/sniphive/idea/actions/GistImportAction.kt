package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.ui.GistImportDialog

/**
 * Action to import GitHub Gists as SnipHive snippets.
 *
 * This action:
 * 1. Checks if the user is authenticated
 * 2. Opens the Gist import dialog
 * 3. Imports the Gist with optional E2EE encryption
 *
 * Usage:
 * - Via SnipHive menu: SnipHive > Import Gist...
 * - Keyboard shortcut: Available
 */
class GistImportAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(GistImportAction::class.java)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val settings = project?.let { SnipHiveSettings.getInstance(it) }
        val isAuthenticated = project?.let { SnipHiveAuthService.getInstance().isCurrentAuthenticated(it) } ?: false

        e.presentation.isEnabled = project != null && isAuthenticated
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        LOG.info("Opening Gist import dialog for project: ${project.name}")

        ApplicationManager.getApplication().invokeLater {
            try {
                val dialog = GistImportDialog(project)

                if (dialog.showAndGet()) {
                    val result = dialog.getImportResult()
                    if (result != null) {
                        // Refresh snippets to show newly imported ones
                        SnippetLookupService.getInstance(project).refreshSnippets()

                        val message = buildString {
                            append("Successfully imported Gist!\n\n")
                            append("Status: ${result.status}\n")
                            append("Gist ID: ${result.gistId}\n")
                            if (result.importedSnippets.isNotEmpty()) {
                                append("Imported ${result.importedSnippets.size} snippet(s)")
                            }
                        }

                        Messages.showInfoMessage(project, message, "Import Successful")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Error during Gist import", e)
                Messages.showErrorDialog(
                    project,
                    "Failed to import Gist: ${e.message}",
                    "Import Error"
                )
            }
        }
    }
}