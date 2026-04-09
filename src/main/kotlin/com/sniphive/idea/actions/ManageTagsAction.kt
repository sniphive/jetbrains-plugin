package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.ui.ManageTagsDialog

/**
 * Action to open the tag management dialog.
 */
class ManageTagsAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val settings = project?.let { SnipHiveSettings.getInstance(it) }
        val isAuthenticated = project?.let { SnipHiveAuthService.getInstance().isCurrentAuthenticated(it) } ?: false

        e.presentation.isEnabled = project != null && isAuthenticated
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        val dialog = ManageTagsDialog(project)
        dialog.show()
    }
}