package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Dialog for importing GitHub Gists as SnipHive snippets.
 *
 * Features:
 * - Gist URL or ID input
 * - E2EE encryption option
 * - Import progress indicator
 * - Result display
 */
class GistImportDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(GistImportDialog::class.java)

        // Regex patterns for Gist URLs
        private val GIST_URL_PATTERN = Regex(
            """https?://gist\.github\.com/[^/]+/([a-fA-F0-9]+)"""
        )
        private val GIST_ID_PATTERN = Regex(
            """^[a-fA-F0-9]{32}$"""
        )
    }

    // UI Components
    private val gistUrlField = JBTextField()
    private val encryptCheckBox = JBCheckBox("Encrypt imported snippets with E2EE")
    private val statusLabel = JBLabel("")
    private val errorLabel = JBLabel("")

    // Result
    private var importResult: SnipHiveApiService.GistImport? = null

    init {
        title = "Import GitHub Gist"
        setOKButtonText("Import")
        setCancelButtonText("Cancel")

        // Default encryption to enabled
        encryptCheckBox.isSelected = true

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
        val headerLabel = JBLabel("<html><b>Import a GitHub Gist as SnipHive snippets</b></html>")
        panel.add(headerLabel, gbc)

        // URL field
        gbc.gridy = 1
        gbc.gridwidth = 1
        gbc.weightx = 0.0
        panel.add(JBLabel("Gist URL or ID:"), gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gistUrlField.columns = 40
        gistUrlField.emptyText.text = "https://gist.github.com/username/gist-id"
        panel.add(gistUrlField, gbc)

        // Help text
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        gbc.insets = Insets(0, 5, 10, 5)
        val helpLabel = JBLabel("<html><small>Enter a GitHub Gist URL or the 32-character Gist ID</small></html>")
        helpLabel.foreground = java.awt.Color.GRAY
        panel.add(helpLabel, gbc)

        // Encrypt checkbox
        gbc.gridy = 3
        gbc.insets = Insets(10, 5, 5, 5)
        panel.add(encryptCheckBox, gbc)

        // Status label
        gbc.gridy = 4
        gbc.insets = Insets(10, 5, 5, 5)
        statusLabel.foreground = java.awt.Color(0, 100, 0)
        statusLabel.isVisible = false
        panel.add(statusLabel, gbc)

        // Error label
        gbc.gridy = 5
        errorLabel.foreground = java.awt.Color(200, 0, 0)
        errorLabel.isVisible = false
        panel.add(errorLabel, gbc)

        return panel
    }

    override fun doOKAction() {
        val input = gistUrlField.text.trim()

        // Validate input
        if (input.isEmpty()) {
            showError("Please enter a Gist URL or ID")
            return
        }

        // Extract Gist ID from URL or use as-is
        val gistId = extractGistId(input)
        if (gistId == null) {
            showError("Invalid Gist URL or ID format")
            return
        }

        // Clear previous errors
        hideError()

        // Disable UI and show loading
        gistUrlField.isEnabled = false
        encryptCheckBox.isEnabled = false
        setOKActionEnabled(false)
        statusLabel.text = "Importing Gist..."
        statusLabel.isVisible = true

        // Perform import in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val gistUrl = "https://gist.github.com/anonymous/$gistId"
                val encrypt = encryptCheckBox.isSelected

                val result = apiService.importGist(project, gistUrl, encrypt)

                ApplicationManager.getApplication().invokeLater {
                    if (result != null) {
                        importResult = result
                        statusLabel.text = "✓ Successfully imported Gist"
                        statusLabel.foreground = java.awt.Color(0, 150, 0)

                        // Close dialog after short delay
                        Timer(1000) {
                            close(OK_EXIT_CODE)
                        }.apply {
                            isRepeats = false
                            start()
                        }
                    } else {
                        showError("Failed to import Gist. Please check the URL and try again.")
                        gistUrlField.isEnabled = true
                        encryptCheckBox.isEnabled = true
                        setOKActionEnabled(true)
                        statusLabel.isVisible = false
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to import Gist", e)
                ApplicationManager.getApplication().invokeLater {
                    showError("Error: ${e.message}")
                    gistUrlField.isEnabled = true
                    encryptCheckBox.isEnabled = true
                    setOKActionEnabled(true)
                    statusLabel.isVisible = false
                }
            }
        }
    }

    /**
     * Extract Gist ID from URL or validate as ID.
     */
    private fun extractGistId(input: String): String? {
        // Try to match URL pattern
        val urlMatch = GIST_URL_PATTERN.find(input)
        if (urlMatch != null) {
            return urlMatch.groupValues[1]
        }

        // Try to match as raw ID
        if (GIST_ID_PATTERN.matches(input)) {
            return input
        }

        return null
    }

    private fun showError(message: String) {
        errorLabel.text = message
        errorLabel.isVisible = true
    }

    private fun hideError() {
        errorLabel.isVisible = false
    }

    /**
     * Get the import result.
     */
    fun getImportResult(): SnipHiveApiService.GistImport? = importResult

    override fun getPreferredFocusedComponent(): JComponent? = gistUrlField
}