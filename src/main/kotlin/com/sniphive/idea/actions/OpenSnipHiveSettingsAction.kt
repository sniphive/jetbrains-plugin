package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * Action to open SnipHive settings.
 *
 * This action opens the SnipHive settings page in the IDE's Settings dialog.
 * Users can configure API URL, workspace, and other options.
 *
 * Usage:
 * - Via SnipHive menu: SnipHive > Settings...
 * - Via Settings dialog: Tools > SnipHive
 */
class OpenSnipHiveSettingsAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(OpenSnipHiveSettingsAction::class.java)
        private const val SETTINGS_ID = "com.sniphive.idea.config.SnipHiveSettingsConfigurable"
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        LOG.debug("Opening SnipHive settings for project: ${project.name}")

        // Open the SnipHive settings page
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            SETTINGS_ID
        )
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}