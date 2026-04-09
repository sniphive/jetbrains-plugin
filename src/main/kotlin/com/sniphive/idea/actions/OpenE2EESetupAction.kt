package com.sniphive.idea.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SecureCredentialStorage
import com.sniphive.idea.ui.E2EESetupDialog

/**
 * Action to open E2EE setup dialog.
 *
 * This action allows users to set up end-to-end encryption for their snippets.
 * It checks if E2EE is already enabled and shows appropriate messaging.
 *
 * Usage:
 * - Via SnipHive menu: SnipHive > Setup E2EE...
 *
 * Security Note:
 * - E2EE setup generates RSA-4096 key pair
 * - Private key is encrypted with master password
 * - Recovery code is shown ONCE and must be saved by user
 */
class OpenE2EESetupAction : AnAction() {

    companion object {
        private val LOG = Logger.getInstance(OpenE2EESetupAction::class.java)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project: Project = e.project ?: return

        LOG.info("Opening E2EE setup dialog for project: ${project.name}")

        val settings = SnipHiveSettings.getInstance(project)

        // Check if user is authenticated
        val email = settings.getUserEmail()
        if (email.isEmpty()) {
            Messages.showWarningDialog(
                project,
                "Please configure your SnipHive account in Settings first.",
                "E2EE Setup"
            )
            return
        }

        // Check if E2EE is already enabled
        if (settings.isE2eeEnabled()) {
            val result = Messages.showYesNoDialog(
                project,
                "E2EE is already enabled. Do you want to reconfigure it?\n\n" +
                "Warning: Reconfiguring will generate new keys and you'll need to save a new recovery code.",
                "E2EE Already Configured",
                "Reconfigure",
                "Cancel",
                Messages.getWarningIcon()
            )

            if (result != Messages.YES) {
                return
            }
        }

        // Show E2EE setup dialog
        val dialog = E2EESetupDialog(project)
        if (dialog.showAndGet()) {
            val setupResult = dialog.getSetupResult()
            if (setupResult != null) {
                // E2EE setup was successful
                settings.setE2eeEnabled(true)

                // Store E2EE data in secure storage
                val credentialStorage = SecureCredentialStorage.getInstance()
                credentialStorage.storeE2EEData(
                    project,
                    email,
                    setupResult.encryptedPrivateKey,
                    setupResult.recoveryEncryptedPrivateKey,
                    setupResult.privateKeyIV,
                    setupResult.recoveryIV,
                    setupResult.kdfSalt,
                    setupResult.recoverySalt,
                    setupResult.kdfIterations
                )

                LOG.info("E2EE setup completed successfully for user: $email")

                Messages.showInfoMessage(
                    project,
                    "End-to-End Encryption has been set up successfully.\n\n" +
                    "Your snippets will now be encrypted before leaving your device.",
                    "E2EE Setup Complete"
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null
    }
}