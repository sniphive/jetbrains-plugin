package com.sniphive.idea.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.ide.BrowserUtil
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.services.SnipHiveAuthService.LoginResult
import java.awt.BorderLayout
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Dialog for user authentication with SnipHive API.
 *
 * This dialog provides:
 * - Email and password input fields
 * - Login with API authentication
 * - Loading state during authentication
 * - Error display for failed login attempts
 * - Automatic credential storage after successful login
 *
 * Usage Example:
 * ```kotlin
 * val dialog = LoginDialog(project)
 * if (dialog.showAndGet()) {
 *     // Login was successful
 *     val user = dialog.getLoginResult()?.user
 * }
 * ```
 *
 * Security Note:
 * - Password is entered via JBPasswordField (masking)
 * - Authentication token is stored in SecureCredentialStorage (IDE Password Safe)
 * - Password is never logged or exposed
 * - All sensitive operations use secure storage
 *
 * @property project The current project context
 */
class LoginDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(LoginDialog::class.java)

        /**
         * Minimum email length for validation.
         */
        private const val MIN_EMAIL_LENGTH = 5

        /**
         * Minimum password length for validation.
         */
        private const val MIN_PASSWORD_LENGTH = 1

        /**
         * Registration URL for new users.
         */
        private const val REGISTER_URL = "https://sniphive.net/register"

        /**
         * Register action that opens the registration page in the system browser.
         */
        private class RegisterAction : AbstractAction("Register") {
            override fun actionPerformed(e: ActionEvent?) {
                LOG.debug("Opening registration page: $REGISTER_URL")
                BrowserUtil.browse(REGISTER_URL)
            }
        }
    }

    // UI Components
    private val emailField: JBTextField = JBTextField()
    private val passwordField: JBPasswordField = JBPasswordField()
    private val errorLabel: JBLabel = JBLabel()

    // Login result
    private var loginResult: LoginResult? = null

    init {
        LOG.debug("Initializing LoginDialog for project: ${project.name}")

        title = "Login to SnipHive"
        setOKButtonText("Login")
        setCancelButtonText("Cancel")

        // Initialize dialog
        init()
    }

    /**
     * Create the center panel for the dialog.
     * This is the main content area of the dialog.
     */
    override fun createCenterPanel(): JComponent {
        LOG.debug("Creating login dialog center panel")

        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Enter your SnipHive credentials to login:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        panel.add(headerLabel)

        // Email field
        val emailPanel = createEmailField()
        panel.add(emailPanel)

        // Password field
        val passwordPanel = createPasswordField()
        panel.add(passwordPanel)

        // Error label (hidden by default)
        errorLabel.isVisible = false
        panel.add(errorLabel)

        // Set focus on email field
        SwingUtilities.invokeLater {
            emailField.requestFocusInWindow()
        }

        LOG.debug("Login dialog center panel created")
        return panel
    }

    /**
     * Create email input field with label.
     */
    private fun createEmailField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Email:")
        emailField.setColumns(30)
        emailField.setToolTipText("Enter your SnipHive email address")

        panel.add(label, BorderLayout.WEST)
        panel.add(emailField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create password input field with label.
     */
    private fun createPasswordField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Password:")
        passwordField.setColumns(30)
        passwordField.setToolTipText("Enter your SnipHive password")

        panel.add(label, BorderLayout.WEST)
        panel.add(passwordField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Override OK button action to perform login.
     */
    override fun doOKAction() {
        LOG.debug("OK button clicked - attempting login")

        // Validate inputs
        if (!validateInputs()) {
            return
        }

        // Perform login on background thread
        performLogin()
    }

    /**
     * Validate user input fields.
     *
     * @return true if inputs are valid
     */
    private fun validateInputs(): Boolean {
        val email = emailField.text.trim()
        val password = String(passwordField.password)

        // Clear previous errors
        hideError()

        // Validate email
        if (email.length < MIN_EMAIL_LENGTH) {
            showError("Email address is too short")
            emailField.requestFocusInWindow()
            return false
        }

        // Validate password
        if (password.length < MIN_PASSWORD_LENGTH) {
            showError("Password is required")
            passwordField.requestFocusInWindow()
            return false
        }

        return true
    }

    /**
     * Perform login operation with API.
     */
    private fun performLogin() {
        val email = emailField.text.trim()
        val password = String(passwordField.password)

        LOG.debug("Performing login for user: ${email.lowercase()}")

        // Disable buttons and show loading state
        isOKActionEnabled = false
        setOKButtonText("Logging in...")

        // Perform login on background thread
        UIUtil.invokeLaterIfNeeded {
            try {
                val settings = SnipHiveSettings.getInstance(project)
                val apiUrl = settings.getApiUrl()
                val authService = SnipHiveAuthService.getInstance()

                // Execute login
                val result = authService.login(
                    project = project,
                    apiUrl = apiUrl,
                    email = email,
                    password = password
                )

                loginResult = result

                if (result.success) {
                    LOG.info("Login successful for user: ${email.lowercase()}")
                    close(OK_EXIT_CODE)
                } else {
                    LOG.warn("Login failed for user ${email.lowercase()}: ${result.message}")
                    showError(result.message)

                    // Re-enable buttons
                    isOKActionEnabled = true
                    setOKButtonText("Login")

                    // Focus password field for retry
                    passwordField.requestFocusInWindow()
                    passwordField.selectAll()
                }
            } catch (e: Exception) {
                LOG.error("Unexpected error during login for user ${email.lowercase()}", e)
                showError("An unexpected error occurred. Please try again.")

                // Re-enable buttons
                isOKActionEnabled = true
                setOKButtonText("Login")

                // Focus password field for retry
                passwordField.requestFocusInWindow()
            }
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
        errorLabel.foreground = java.awt.Color(211, 47, 47) // Red error color
        errorLabel.isVisible = true
    }

    /**
     * Hide error message.
     */
    private fun hideError() {
        errorLabel.isVisible = false
    }

    /**
     * Get the login result from authentication.
     *
     * @return The LoginResult, or null if login not attempted
     */
    fun getLoginResult(): LoginResult? = loginResult

    /**
     * Pre-fill email field for convenience.
     *
     * @param email The email address to pre-fill
     */
    fun setEmail(email: String) {
        emailField.text = email
    }

    /**
     * Get the entered email address.
     *
     * @return The email address from the field
     */
    fun getEmail(): String = emailField.text.trim()

    /**
     * Get the entered password.
     *
     * Security: This method should be used sparingly and never log.
     *
     * @return The password from the field
     */
    fun getPassword(): String = String(passwordField.password)

    /**
     * Clear all input fields.
     */
    fun clearFields() {
        emailField.text = ""
        passwordField.text = ""
        hideError()
    }

    /**
     * Create left side actions for the dialog.
     * This adds a "Register" button for new users to create an account.
     *
     * @return Array of action buttons to display on the left side
     */
    override fun createLeftSideActions(): Array<AbstractAction> {
        LOG.debug("Creating left side actions with Register button")
        return arrayOf(RegisterAction())
    }

    /**
     * Preferred focus component is the email field.
     */
    override fun getPreferredFocusedComponent(): JComponent? {
        return emailField
    }

    /**
     * Help ID for documentation (optional).
     */
    override fun getHelpId(): String? {
        return "sniphive.login"
    }

    /**
     * Dialog dimension settings.
     */
    override fun getDimensionServiceKey(): String? {
        return "SnipHive.LoginDialog"
    }
}
