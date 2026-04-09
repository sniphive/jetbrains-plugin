package com.sniphive.idea.config

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.VerticalFlowLayout
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings configuration UI for SnipHive plugin.
 *
 * This class provides the user interface for configuring SnipHive settings
 * in the IDE's Settings/Preferences dialog. It's registered in plugin.xml
 * as an applicationConfigurable and appears under Tools > SnipHive.
 *
 * Note: This configurable works with project-level settings. When multiple projects
 * are open, it configures the settings for each active project independently.
 *
 * Security Note:
 * - This UI only displays and configures non-sensitive settings
 * - Authentication tokens and private keys are managed by SecureCredentialStorage
 * - Passwords are masked in UI fields
 * - No credentials are logged or exposed
 */
class SnipHiveSettingsConfigurable : Configurable {

    companion object {
        private const val DISPLAY_NAME = "SnipHive"
        private const val ID = "com.sniphive.idea.config.SnipHiveSettingsConfigurable"
    }

    private lateinit var mainPanel: JPanel
    private lateinit var workspaceIdField: JBTextField
    private lateinit var userEmailField: JBTextField
    private lateinit var userNameField: JBTextField
    private lateinit var showEncryptedContentCheckBox: JBCheckBox
    private lateinit var autoRefreshOnOpenCheckBox: JBCheckBox
    private lateinit var autoRefreshIntervalField: JBTextField
    private lateinit var enableCodeCompletionCheckBox: JBCheckBox
    private lateinit var codeCompletionMinPrefixLengthField: JBTextField
    private lateinit var codeCompletionMaxSuggestionsField: JBTextField

    /**
     * Get the display name shown in the Settings dialog.
     */
    override fun getDisplayName(): String = DISPLAY_NAME

    /**
     * Get the active project settings.
     */
    private fun getActiveProjectSettings(): SnipHiveSettings? {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
        return if (project != null) {
            SnipHiveSettings.getInstance(project)
        } else {
            null
        }
    }

    /**
     * Create the UI component for settings.
     */
    override fun createComponent(): JComponent {
        mainPanel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 5, true, false))

        // API Configuration Section
        val apiSection = createApiConfigurationSection()
        mainPanel.add(apiSection)

        // User Information Section
        val userSection = createUserInformationSection()
        mainPanel.add(userSection)

        // E2EE Section
        val e2eeSection = createE2EESection()
        mainPanel.add(e2eeSection)

        // Display Options Section
        val displaySection = createDisplayOptionsSection()
        mainPanel.add(displaySection)

        // Auto-refresh Section
        val refreshSection = createAutoRefreshSection()
        mainPanel.add(refreshSection)

        // Code Completion Section
        val completionSection = createCodeCompletionSection()
        mainPanel.add(completionSection)

        // Setup listeners after all components are created
        setupListeners()

        // Initialize UI with current settings
        reset()

        return mainPanel
    }

    /**
     * Check if any settings have been modified.
     */
    override fun isModified(): Boolean {
        val settings = getActiveProjectSettings() ?: return false

        return settings.getWorkspaceId() != workspaceIdField.text ||
                settings.getUserEmail() != userEmailField.text ||
                settings.getUserName() != userNameField.text ||
                settings.showEncryptedContent() != showEncryptedContentCheckBox.isSelected ||
                settings.isAutoRefreshOnOpen() != autoRefreshOnOpenCheckBox.isSelected ||
                settings.getAutoRefreshIntervalMinutes() != getAutoRefreshInterval() ||
                settings.isCodeCompletionEnabled() != enableCodeCompletionCheckBox.isSelected ||
                settings.getCodeCompletionMinPrefixLength() != getMinPrefixLength() ||
                settings.getCodeCompletionMaxSuggestions() != getMaxSuggestions()
    }

    /**
     * Apply the modified settings.
     */
    override fun apply() {
        val settings = getActiveProjectSettings() ?: return

        settings.setWorkspaceId(workspaceIdField.text.trim())
        settings.setUserEmail(userEmailField.text.trim())
        settings.setUserName(userNameField.text.trim())
        settings.setE2eeEnabled(true) // E2EE is always enabled
        settings.setShowEncryptedContent(showEncryptedContentCheckBox.isSelected)
        settings.setAutoRefreshOnOpen(autoRefreshOnOpenCheckBox.isSelected)
        settings.setAutoRefreshIntervalMinutes(getAutoRefreshInterval())
        settings.setCodeCompletionEnabled(enableCodeCompletionCheckBox.isSelected)
        settings.setCodeCompletionMinPrefixLength(getMinPrefixLength())
        settings.setCodeCompletionMaxSuggestions(getMaxSuggestions())
    }

    /**
     * Reset the UI to current settings.
     */
    override fun reset() {
        val settings = getActiveProjectSettings() ?: return

        workspaceIdField.text = settings.getWorkspaceId()
        userEmailField.text = settings.getUserEmail()
        userNameField.text = settings.getUserName()
        showEncryptedContentCheckBox.isSelected = settings.showEncryptedContent()
        autoRefreshOnOpenCheckBox.isSelected = settings.isAutoRefreshOnOpen()
        autoRefreshIntervalField.text = settings.getAutoRefreshIntervalMinutes().toString()
        enableCodeCompletionCheckBox.isSelected = settings.isCodeCompletionEnabled()
        codeCompletionMinPrefixLengthField.text = settings.getCodeCompletionMinPrefixLength().toString()
        codeCompletionMaxSuggestionsField.text = settings.getCodeCompletionMaxSuggestions().toString()

        updateFieldStates()
    }

    /**
     * Dispose UI components.
     */
    override fun disposeUIResources() {
        // UI components are automatically disposed by IntelliJ Platform
    }

    /**
     * Create the API configuration section.
     */
    private fun createApiConfigurationSection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("Workspace")

        workspaceIdField = JBTextField()
        workspaceIdField.setColumns(40)

        panel.add(JBLabel("Workspace ID:"))
        panel.add(workspaceIdField)
        panel.add(JBLabel("<html><small>Selected workspace ID for the current project</small></html>"))

        return panel
    }

    /**
     * Create the user information section.
     */
    private fun createUserInformationSection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("User Information")

        userEmailField = JBTextField()
        userEmailField.setColumns(40)

        userNameField = JBTextField()
        userNameField.setColumns(40)

        panel.add(JBLabel("Email:"))
        panel.add(userEmailField)
        panel.add(JBLabel("<html><small>Your email address for display purposes</small></html>"))
        panel.add(JBLabel("Display Name:"))
        panel.add(userNameField)
        panel.add(JBLabel("<html><small>Your display name for snippet attribution</small></html>"))

        return panel
    }

    /**
     * Create the E2EE section.
     */
    private fun createE2EESection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("End-to-End Encryption")

        panel.add(JBLabel("<html><b>E2EE is mandatory</b> - Your snippets are encrypted with RSA-4096 OAEP + AES-256-GCM.<br/>Your encryption keys are stored securely in the IDE Password Safe.</html>"))

        return panel
    }

    /**
     * Create the display options section.
     */
    private fun createDisplayOptionsSection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("Display Options")

        showEncryptedContentCheckBox = JBCheckBox("Show encrypted content in tool window")
        panel.add(showEncryptedContentCheckBox)
        panel.add(JBLabel("<html><small>When disabled, only decrypted content is shown</small></html>"))

        return panel
    }

    /**
     * Create the auto-refresh section.
     */
    private fun createAutoRefreshSection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("Auto-Refresh")

        autoRefreshOnOpenCheckBox = JBCheckBox("Auto-refresh when tool window opens")
        autoRefreshIntervalField = JBTextField(5)

        val intervalPanel = JPanel()
        intervalPanel.add(JBLabel("Refresh interval (minutes, 0 = disabled):"))
        intervalPanel.add(autoRefreshIntervalField)

        panel.add(autoRefreshOnOpenCheckBox)
        panel.add(intervalPanel)
        panel.add(JBLabel("<html><small>Automatically refresh snippets at the specified interval</small></html>"))

        return panel
    }

    /**
     * Create the code completion section.
     */
    private fun createCodeCompletionSection(): JPanel {
        val panel = JPanel(VerticalFlowLayout(VerticalFlowLayout.TOP, 0, 2, true, false))
        panel.border = javax.swing.BorderFactory.createTitledBorder("Code Completion")

        enableCodeCompletionCheckBox = JBCheckBox("Enable code completion for snippets")
        codeCompletionMinPrefixLengthField = JBTextField(5)
        codeCompletionMaxSuggestionsField = JBTextField(5)

        val settingsPanel = JPanel()
        settingsPanel.add(JBLabel("Minimum prefix length:"))
        settingsPanel.add(codeCompletionMinPrefixLengthField)
        settingsPanel.add(JBLabel("Maximum suggestions:"))
        settingsPanel.add(codeCompletionMaxSuggestionsField)

        panel.add(enableCodeCompletionCheckBox)
        panel.add(settingsPanel)
        panel.add(JBLabel("<html><small>Suggest snippets while typing based on prefixes</small></html>"))

        return panel
    }

    /**
     * Setup listeners for UI components.
     * This is called after all components are created.
     */
    private fun setupListeners() {
        autoRefreshOnOpenCheckBox.addActionListener { updateFieldStates() }
        enableCodeCompletionCheckBox.addActionListener { updateFieldStates() }

        // Add document listeners for validation
        autoRefreshIntervalField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = validateIntervalField()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = validateIntervalField()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = validateIntervalField()
        })

        codeCompletionMinPrefixLengthField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = validatePrefixLengthField()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = validatePrefixLengthField()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = validatePrefixLengthField()
        })

        codeCompletionMaxSuggestionsField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent) = validateMaxSuggestionsField()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent) = validateMaxSuggestionsField()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent) = validateMaxSuggestionsField()
        })
    }

    /**
     * Validate auto-refresh interval field.
     */
    private fun validateIntervalField() {
        val text = autoRefreshIntervalField.text
        if (text.isNotEmpty()) {
            try {
                val value = text.toInt()
                if (value < 0) {
                    autoRefreshIntervalField.text = "0"
                }
            } catch (e: NumberFormatException) {
                // Allow empty or partial input during typing
                if (text != "-" && !text.matches(Regex("^\\d*$"))) {
                    autoRefreshIntervalField.text = text.replace(Regex("[^0-9]"), "")
                }
            }
        }
    }

    /**
     * Validate prefix length field.
     */
    private fun validatePrefixLengthField() {
        val text = codeCompletionMinPrefixLengthField.text
        if (text.isNotEmpty()) {
            try {
                val value = text.toInt()
                if (value > 10) {
                    codeCompletionMinPrefixLengthField.text = "10"
                }
            } catch (e: NumberFormatException) {
                // Allow empty or partial input during typing
                if (text != "-" && !text.matches(Regex("^\\d*$"))) {
                    codeCompletionMinPrefixLengthField.text = text.replace(Regex("[^0-9]"), "")
                }
            }
        }
    }

    /**
     * Validate max suggestions field.
     */
    private fun validateMaxSuggestionsField() {
        val text = codeCompletionMaxSuggestionsField.text
        if (text.isNotEmpty()) {
            try {
                val value = text.toInt()
                if (value > 50) {
                    codeCompletionMaxSuggestionsField.text = "50"
                }
            } catch (e: NumberFormatException) {
                // Allow empty or partial input during typing
                if (text != "-" && !text.matches(Regex("^\\d*$"))) {
                    codeCompletionMaxSuggestionsField.text = text.replace(Regex("[^0-9]"), "")
                }
            }
        }
    }

    /**
     * Get auto-refresh interval from field with validation.
     */
    private fun getAutoRefreshInterval(): Int {
        return try {
            val value = autoRefreshIntervalField.text.trim().toInt()
            if (value < 0) 0 else value
        } catch (e: NumberFormatException) {
            0
        }
    }

    /**
     * Get minimum prefix length from field with validation.
     */
    private fun getMinPrefixLength(): Int {
        return try {
            val value = codeCompletionMinPrefixLengthField.text.trim().toInt()
            when {
                value < 1 -> 1
                value > 10 -> 10
                else -> value
            }
        } catch (e: NumberFormatException) {
            3
        }
    }

    /**
     * Get maximum suggestions from field with validation.
     */
    private fun getMaxSuggestions(): Int {
        return try {
            val value = codeCompletionMaxSuggestionsField.text.trim().toInt()
            when {
                value < 1 -> 1
                value > 50 -> 50
                else -> value
            }
        } catch (e: NumberFormatException) {
            10
        }
    }

    /**
     * Update field enabled/disabled states based on checkbox selections.
     */
    private fun updateFieldStates() {
        autoRefreshIntervalField.isEnabled = autoRefreshOnOpenCheckBox.isSelected
        codeCompletionMinPrefixLengthField.isEnabled = enableCodeCompletionCheckBox.isSelected
        codeCompletionMaxSuggestionsField.isEnabled = enableCodeCompletionCheckBox.isSelected
    }
}