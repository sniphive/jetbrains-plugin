package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.ui.RecentSnippetsPopup

/**
 * Action to show recent snippets popup.
 *
 * Keyboard shortcut: Ctrl+Shift+E (or Cmd+Shift+E on Mac)
 */
class ShowRecentSnippetsAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val settings = SnipHiveSettings.getInstance()
        val isAuthenticated = project?.let { SnipHiveAuthService.getInstance().isCurrentAuthenticated(it) } ?: false

        e.presentation.isEnabled = project != null && editor != null && isAuthenticated
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return
        val editor: Editor = e.getData(CommonDataKeys.EDITOR) ?: return

        val popup = RecentSnippetsPopup(project, editor)
        popup.showPopup()
    }
}