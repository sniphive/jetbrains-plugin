package com.sniphive.idea.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.E2EESetupResult
import com.sniphive.idea.services.SecureCredentialStorage
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Dialog for E2EE (End-to-End Encryption) setup with recovery code display.
 *
 * This dialog provides:
 * - Master password input and confirmation
 * - E2EE setup using E2EECryptoService
 * - Recovery code display with prominent warnings
 * - Copy to clipboard functionality for recovery code
 * - Confirmation that user has saved recovery code
 *
 * The dialog operates in two phases:
 * 1. **Setup Phase**: User enters and confirms master password
 * 2. **Recovery Code Phase**: Recovery code is displayed with warnings
 *
 * Usage Example:
 * ```kotlin
 * val dialog = E2EESetupDialog(project)
 * if (dialog.showAndGet()) {
 *     // E2EE setup was successful
 *     val result = dialog.getSetupResult()
 * }
 * ```
 *
 * Security Note:
 * - Master password is never stored (only used for key derivation)
 * - Recovery code is shown ONLY ONCE
 * - All encryption keys are stored in SecureCredentialStorage (IDE Password Safe)
 * - Recovery code should be stored securely by the user (e.g., password manager)
 * - If recovery code is lost, access cannot be recovered
 *
 * @property project The current project context
 */
class E2EESetupDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(E2EESetupDialog::class.java)

        /**
         * Minimum master password length for security.
         */
        private const val MIN_PASSWORD_LENGTH = 8
    }

    /**
     * Dialog phase state.
     */
    private enum class DialogPhase {
        SETUP,
        RECOVERY_CODE
    }

    // UI Components - Setup Phase
    private val passwordField: JBPasswordField = JBPasswordField()
    private val confirmPasswordField: JBPasswordField = JBPasswordField()
    private val errorLabel: JBLabel = JBLabel()

    // UI Components - Recovery Code Phase
    private val recoveryCodeTextArea: JBTextArea = JBTextArea()
    private val savedRecoveryCodeCheckbox: JCheckBox = JCheckBox()

    // Main panel reference for switching phases
    private var mainPanel: JPanel? = null

    // Dialog State
    private var currentPhase: DialogPhase = DialogPhase.SETUP
    private var setupResult: E2EESetupResult? = null

    init {
        LOG.debug("Initializing E2EESetupDialog for project: ${project.name}")

        title = "Setup End-to-End Encryption"
        setOKButtonText("Setup")
        setCancelButtonText("Cancel")

        // Initialize dialog
        init()
    }

    /**
     * Create the center panel for the dialog.
     * This is the main content area of the dialog.
     */
    override fun createCenterPanel(): JComponent {
        LOG.debug("Creating E2EE setup dialog center panel")

        mainPanel = JPanel()
        mainPanel?.layout = CardLayout()

        // Phase 1: Setup panel
        val setupPanel = createSetupPanel()
        mainPanel?.add(setupPanel, DialogPhase.SETUP.name)

        // Phase 2: Recovery code panel
        val recoveryPanel = createRecoveryCodePanel()
        mainPanel?.add(recoveryPanel, DialogPhase.RECOVERY_CODE.name)

        LOG.debug("E2EE setup dialog center panel created")
        return mainPanel!!
    }

    /**
     * Create the setup phase panel with password fields.
     */
    private fun createSetupPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Set up End-to-End Encryption for your snippets")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        panel.add(headerLabel)

        // Description label
        val descLabel = JBLabel(
            """
            <html>
            <div style="width: 350px;">
            E2EE encrypts your snippets before they leave your device.
            Only you can decrypt them with your master password.
            </div>
            </html>
            """.trimIndent()
        )
        panel.add(descLabel)

        // Password field
        val passwordPanel = createPasswordField()
        panel.add(passwordPanel)

        // Confirm password field
        val confirmPasswordPanel = createConfirmPasswordField()
        panel.add(confirmPasswordField)

        // Error label (hidden by default)
        errorLabel.isVisible = false
        panel.add(errorLabel)

        // Security note
        val securityNote = JBLabel(
            """
            <html>
            <div style="color: gray; font-size: 11px; width: 350px;">
            <b>Important:</b> Your master password is never stored on our servers.
            If you forget it, you can use a recovery code to regain access.
            </div>
            </html>
            """.trimIndent()
        )
        panel.add(securityNote)

        // Set focus on password field
        SwingUtilities.invokeLater {
            passwordField.requestFocusInWindow()
        }

        return panel
    }

    /**
     * Create the recovery code display panel.
     */
    private fun createRecoveryCodePanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Save Your Recovery Code")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        panel.add(headerLabel)

        // Warning label
        val warningLabel = JBLabel(
            """
            <html>
            <div style="width: 350px; color: #d32f2f;">
            <b>⚠ IMPORTANT: This code will NOT be shown again!</b><br><br>
            Save this recovery code in a secure location (e.g., password manager).
            If you lose both your master password and recovery code, your data cannot be recovered.
            </div>
            </html>
            """.trimIndent()
        )
        panel.add(warningLabel)

        // Recovery code display area
        val recoveryLabel = JBLabel("Recovery Code:")
        panel.add(recoveryLabel)

        recoveryCodeTextArea.isEditable = false
        recoveryCodeTextArea.rows = 2
        recoveryCodeTextArea.font = JBUI.Fonts.label().deriveFont(18f).deriveFont(java.awt.Font.BOLD)
        recoveryCodeTextArea.background = java.awt.Color(245, 245, 245) // Light gray background
        recoveryCodeTextArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        )
        recoveryCodeTextArea.text = setupResult?.recoveryCode ?: ""
        panel.add(recoveryCodeTextArea)

        // Copy button panel
        val copyButtonPanel = JPanel()
        copyButtonPanel.layout = BorderLayout()
        copyButtonPanel.isOpaque = false

        val copyButton = JButton("Copy to Clipboard")
        copyButton.addActionListener {
            copyRecoveryCodeToClipboard()
        }

        copyButtonPanel.add(copyButton, BorderLayout.WEST)
        panel.add(copyButtonPanel)

        // Confirmation checkbox
        savedRecoveryCodeCheckbox.text = "I have saved my recovery code in a secure location"
        savedRecoveryCodeCheckbox.addActionListener { updateOKButtonState() }
        panel.add(savedRecoveryCodeCheckbox)

        // Instruction label
        val instructionLabel = JBLabel(
            """
            <html>
            <div style="color: gray; font-size: 11px; width: 350px;">
            Click "Done" only after you have securely stored your recovery code.
            You will need this code if you forget your master password.
            </div>
            </html>
            """.trimIndent()
        )
        panel.add(instructionLabel)

        return panel
    }

    /**
     * Create master password input field with label.
     */
    private fun createPasswordField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Master Password:")
        passwordField.setColumns(30)
        passwordField.setToolTipText("Enter a strong password (at least 8 characters)")

        panel.add(label, BorderLayout.WEST)
        panel.add(passwordField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create confirm password input field with label.
     */
    private fun createConfirmPasswordField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Confirm Password:")
        confirmPasswordField.setColumns(30)
        confirmPasswordField.setToolTipText("Enter the same password again to confirm")

        panel.add(label, BorderLayout.WEST)
        panel.add(confirmPasswordField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Override OK button action to perform E2EE setup.
     */
    override fun doOKAction() {
        LOG.debug("OK button clicked - current phase: $currentPhase")

        when (currentPhase) {
            DialogPhase.SETUP -> {
                // Validate and perform setup
                if (!validatePasswordInputs()) {
                    return
                }
                performE2EESetup()
            }
            DialogPhase.RECOVERY_CODE -> {
                // User confirmed they saved the recovery code
                LOG.info("E2EE setup completed successfully")
                close(OK_EXIT_CODE)
            }
        }
    }

    /**
     * Validate password input fields.
     *
     * @return true if passwords are valid
     */
    private fun validatePasswordInputs(): Boolean {
        val password = String(passwordField.password)
        val confirmPassword = String(confirmPasswordField.password)

        // Clear previous errors
        hideError()

        // Validate password length
        if (password.length < MIN_PASSWORD_LENGTH) {
            showError("Master password must be at least $MIN_PASSWORD_LENGTH characters")
            passwordField.requestFocusInWindow()
            return false
        }

        // Validate passwords match
        if (password != confirmPassword) {
            showError("Passwords do not match")
            confirmPasswordField.requestFocusInWindow()
            confirmPasswordField.selectAll()
            return false
        }

        return true
    }

    /**
     * Perform E2EE setup operation.
     */
    private fun performE2EESetup() {
        val password = String(passwordField.password)

        LOG.debug("Performing E2EE setup")

        // Disable buttons and show loading state
        isOKActionEnabled = false
        setOKButtonText("Setting up...")

        // Perform setup on background thread
        UIUtil.invokeLaterIfNeeded {
            try {
                // Call E2EECryptoService to setup E2EE
                setupResult = E2EECryptoService.setupE2EE(password)

                if (setupResult != null) {
                    LOG.info("E2EE setup successful, switching to recovery code phase")

                    // Store credentials in secure storage
                    val settings = SnipHiveSettings.getInstance()
                    val email = settings.getUserEmail()
                    if (email.isEmpty()) {
                        LOG.warn("User email not found in settings")
                        return@invokeLaterIfNeeded
                    }
                    val credentialStorage = SecureCredentialStorage.getInstance()

                    // Store recovery code in secure storage (optional convenience)
                    credentialStorage.storeRecoveryCode(project, email, setupResult!!.recoveryCode)

                    // Switch to recovery code phase
                    switchToRecoveryCodePhase()
                } else {
                    LOG.error("E2EE setup returned null result")
                    showError("E2EE setup failed. Please try again.")

                    // Re-enable buttons
                    isOKActionEnabled = true
                    setOKButtonText("Setup")
                }
            } catch (e: IllegalArgumentException) {
                LOG.warn("E2EE setup validation failed: ${e.message}")
                showError(e.message ?: "Invalid input")

                // Re-enable buttons
                isOKActionEnabled = true
                setOKButtonText("Setup")
            } catch (e: Exception) {
                LOG.error("Unexpected error during E2EE setup", e)
                showError("An unexpected error occurred. Please try again.")

                // Re-enable buttons
                isOKActionEnabled = true
                setOKButtonText("Setup")
            }
        }
    }

    /**
     * Switch to recovery code display phase.
     */
    private fun switchToRecoveryCodePhase() {
        LOG.debug("Switching to recovery code phase")

        currentPhase = DialogPhase.RECOVERY_CODE

        // Update dialog for recovery code phase
        title = "Save Your Recovery Code"
        setOKButtonText("Done")
        isOKActionEnabled = false  // Disabled until checkbox is checked

        // Switch to recovery code panel
        val cardLayout = (mainPanel?.layout as? CardLayout) ?: return
        cardLayout.show(mainPanel, DialogPhase.RECOVERY_CODE.name)

        // Enable OK button only after checkbox is checked
        updateOKButtonState()
    }

    /**
     * Update OK button state based on checkbox.
     */
    private fun updateOKButtonState() {
        isOKActionEnabled = savedRecoveryCodeCheckbox.isSelected
    }

    /**
     * Copy recovery code to clipboard.
     */
    private fun copyRecoveryCodeToClipboard() {
        val recoveryCode = setupResult?.recoveryCode ?: return

        try {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val transferable: Transferable = StringSelection(recoveryCode)
            clipboard.setContents(transferable, null)

            LOG.debug("Recovery code copied to clipboard")

            // Show brief success feedback
            val originalText = recoveryCodeTextArea.text
            recoveryCodeTextArea.background = Color(76, 175, 80) // Green
            SwingUtilities.invokeLater {
                Thread.sleep(300)
                recoveryCodeTextArea.background = Color(245, 245, 245) // Light gray
            }
        } catch (e: Exception) {
            LOG.error("Failed to copy recovery code to clipboard", e)
            showError("Failed to copy to clipboard. Please copy manually.")
        }
    }

    /**
     * Show error message to user.
     *
     * @param message The error message to display
     */
    private fun showError(message: String) {
        LOG.debug("Showing error: $message")
        errorLabel.text = message
        errorLabel.foreground = Color(211, 47, 47) // Red error color
        errorLabel.isVisible = true
    }

    /**
     * Hide error message.
     */
    private fun hideError() {
        errorLabel.isVisible = false
    }

    /**
     * Get the E2EE setup result.
     *
     * @return The E2EESetupResult, or null if setup not completed
     */
    fun getSetupResult(): E2EESetupResult? = setupResult

    /**
     * Preferred focus component is password field (setup phase).
     */
    override fun getPreferredFocusedComponent(): JComponent? {
        return when (currentPhase) {
            DialogPhase.SETUP -> passwordField
            DialogPhase.RECOVERY_CODE -> savedRecoveryCodeCheckbox
        }
    }

    /**
     * Help ID for documentation (optional).
     */
    override fun getHelpId(): String? {
        return "sniphive.e2ee.setup"
    }

    /**
     * Dialog dimension settings.
     */
    override fun getDimensionServiceKey(): String? {
        return "SnipHive.E2EESetupDialog"
    }
}
