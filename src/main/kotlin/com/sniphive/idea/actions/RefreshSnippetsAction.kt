package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnippetCacheListener
import com.sniphive.idea.services.SnippetLookupService

/**
 * Action to refresh snippets from the server.
 *
 * This action triggers a refresh of the snippet cache from the SnipHive API.
 * It's typically used when the user wants to ensure they have the latest snippets.
 *
 * Usage:
 * - Via SnipHive menu: SnipHive > Refresh Snippets
 * - Keyboard shortcut: Available in tool window
 */
class RefreshSnippetsAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(RefreshSnippetsAction::class.java)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        LOG.info("Refreshing snippets for project: ${project.name}")

        val settings = SnipHiveSettings.getInstance(project)
        if (!settings.isLoggedIn()) {
            Messages.showWarningDialog(
                project,
                "Please log in to SnipHive first.",
                "Not Logged In"
            )
            return
        }

        // Get the snippet lookup service and trigger refresh
        val lookupService = SnippetLookupService.getInstance(project)

        if (lookupService.isRefreshing()) {
            Messages.showInfoMessage(
                project,
                "Snippet refresh is already in progress. Please wait.",
                "Refresh In Progress"
            )
            return
        }

        // Subscribe to refresh events
        val bus = ApplicationManager.getApplication().messageBus.connect()
        bus.subscribe(SnippetLookupService.SNIPPET_CACHE_TOPIC, object : SnippetCacheListener {
            override fun onRefreshStarted() {
                LOG.debug("Snippet refresh started")
            }

            override fun onSnippetsRefreshed(snippets: List<com.sniphive.idea.models.Snippet>) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showInfoMessage(
                        project,
                        "Successfully loaded ${snippets.size} snippets.",
                        "Refresh Complete"
                    )
                }
                bus.dispose()
            }

            override fun onRefreshFailed(error: String) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to refresh snippets: $error",
                        "Refresh Failed"
                    )
                }
                bus.dispose()
            }
        })

        // Trigger refresh
        lookupService.refreshSnippets()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val settings = project?.let { SnipHiveSettings.getInstance(it) }
        val isLoggedIn = settings?.isLoggedIn() ?: false
        val lookupService = project?.let { SnippetLookupService.getInstance(it) }
        val isRefreshing = lookupService?.isRefreshing() ?: false

        e.presentation.isEnabled = project != null && isLoggedIn && !isRefreshing

        // Update text based on state
        if (isRefreshing) {
            e.presentation.text = "Refreshing..."
        } else {
            e.presentation.text = "Refresh Snippets"
        }
    }
}