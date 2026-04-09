package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.util.ui.JBUI
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.E2EEProfile
import com.sniphive.idea.services.SecureCredentialStorage
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Dialog for entering master password to unlock E2EE private key.
 *
 * This dialog is shown after login if:
 * - E2EE is enabled for the user
 * - Private key is not yet decrypted in session
 *
 * Security Note:
 * - Master password is never stored
 * - Only used to decrypt private key in memory
 */
class MasterPasswordDialog(
    private val project: Project,
    private val email: String,
    private val e2eeProfile: E2EEProfile
) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(MasterPasswordDialog::class.java)
    }

    private val passwordField = JBPasswordField()
    private val errorLabel = JBLabel("")
    private var unlockSuccessful = false

    init {
        title = "Unlock E2EE"
        setOKButtonText("Unlock")
        setCancelButtonText("Skip")

        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(15, 20)

        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)

        // Header
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        val headerLabel = JBLabel("<html><b>Enter your master password to unlock encrypted content</b></html>")
        panel.add(headerLabel, gbc)

        // Info
        gbc.gridy = 1
        gbc.insets = Insets(10, 5, 10, 5)
        val infoLabel = JBLabel("<html>Your snippets and notes are encrypted with end-to-end encryption.<br>Enter your master password to decrypt them.</html>")
        infoLabel.foreground = java.awt.Color.GRAY
        panel.add(infoLabel, gbc)

        // Password field
        gbc.gridy = 2
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        gbc.insets = Insets(10, 5, 5, 5)
        panel.add(JBLabel("Master Password:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        passwordField.columns = 30
        passwordField.toolTipText = "Enter the master password you set during E2EE setup"
        panel.add(passwordField, gbc)

        // Error label
        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 2
        errorLabel.foreground = java.awt.Color(211, 47, 47)
        errorLabel.isVisible = false
        panel.add(errorLabel, gbc)

        // Recovery option
        gbc.gridy = 4
        gbc.insets = Insets(15, 5, 5, 5)
        val recoveryLabel = JBLabel("<html><small>Forgot your master password? Use your recovery code instead.</small></html>")
        recoveryLabel.foreground = java.awt.Color.GRAY
        panel.add(recoveryLabel, gbc)

        return panel
    }

    override fun doOKAction() {
        val password = String(passwordField.password)

        if (password.isEmpty()) {
            showError("Please enter your master password")
            return
        }

        // Try to unlock
        try {
            val privateKey = E2EECryptoService.unlockWithMasterPassword(password, e2eeProfile)

            // Store decrypted private key in memory (secure storage)
            val secureStorage = SecureCredentialStorage.getInstance()
            val privateKeyJwk = com.google.gson.JsonParser.parseString(
                com.sniphive.idea.crypto.RSACrypto.exportPrivateKeyToJWK(privateKey).toString()
            ).asString

            secureStorage.storePrivateKey(project, email, privateKeyJwk)

            unlockSuccessful = true
            LOG.info("E2EE unlocked successfully for user: $email")

            close(OK_EXIT_CODE)
        } catch (e: Exception) {
            LOG.warn("Failed to unlock E2EE: ${e.message}")
            showError("Invalid master password. Please try again.")
            passwordField.text = ""
            passwordField.requestFocusInWindow()
        }
    }

    private fun showError(message: String) {
        errorLabel.text = message
        errorLabel.isVisible = true
    }

    fun isUnlockSuccessful(): Boolean = unlockSuccessful

    override fun getPreferredFocusedComponent(): JComponent? = passwordField
}