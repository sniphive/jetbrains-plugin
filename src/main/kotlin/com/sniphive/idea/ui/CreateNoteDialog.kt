package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnipHiveApiService
import com.sniphive.idea.services.SecureCredentialStorage
import com.sniphive.idea.editor.E2EEContentService
import java.awt.BorderLayout
import javax.swing.*

/**
 * Dialog for creating a new SnipHive note.
 *
 * This dialog provides:
 * - Title input field
 * - Content text area with Markdown support
 * - Tag selection with checkboxes
 * - Create button with loading state
 *
 * Security Note:
 * - No credentials or tokens handled in this dialog
 * - Tags are metadata only
 *
 * @property project The current project context
 */
class CreateNoteDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(CreateNoteDialog::class.java)

        private const val MIN_TITLE_LENGTH = 1
        private const val MAX_TITLE_LENGTH = 255
    }

    // UI Components
    private val titleField: JBTextField = JBTextField()
    private val contentArea: JBTextArea = JBTextArea()
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val tagsScrollPane: JBScrollPane = JBScrollPane(tagsPanel)
    private val errorLabel: JBLabel = JBLabel()
    private val statusLabel: JBLabel = JBLabel()

    // Data
    private val tagCheckBoxes = mutableMapOf<String, JBCheckBox>()
    private var availableTags: List<Tag> = emptyList()
    private var createdNote: Note? = null

    // Timer for delayed dialog close - must be stored to prevent GC
    private var closeTimer: Timer? = null

    init {
        LOG.debug("Initializing CreateNoteDialog for project: ${project.name}")

        title = "Create Note"
        setOKButtonText("Create")
        setCancelButtonText("Cancel")

        init()
    }

    override fun createCenterPanel(): JComponent {
        LOG.debug("Creating create note dialog center panel")

        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Create a new note:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        panel.add(headerLabel)

        // Title field
        val titlePanel = createTitleField()
        panel.add(titlePanel)

        // Content area
        val contentPanel = createContentArea()
        panel.add(contentPanel)

        // Tags selection
        val tagsSection = createTagsSection()
        panel.add(tagsSection)

        // Error label (hidden by default)
        errorLabel.isVisible = false
        panel.add(errorLabel)

        // Status label (hidden by default)
        statusLabel.isVisible = false
        panel.add(statusLabel)

        // Set focus on title field
        SwingUtilities.invokeLater {
            titleField.requestFocusInWindow()
        }

        LOG.debug("Create note dialog center panel created")
        return panel
    }

    private fun createTitleField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Title:")
        titleField.columns = 40
        titleField.toolTipText = "Enter a title for your note"

        panel.add(label, BorderLayout.WEST)
        panel.add(titleField, BorderLayout.CENTER)

        return panel
    }

    private fun createContentArea(): JPanel {
        val panel = JPanel(VerticalLayout(5))
        panel.isOpaque = false

        val headerLabel = JBLabel("Content (Markdown supported):")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD)
        panel.add(headerLabel)

        contentArea.rows = 12
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)

        val scrollPane = JBScrollPane(contentArea)
        scrollPane.preferredSize = java.awt.Dimension(500, 200)

        panel.add(scrollPane)

        return panel
    }

    private fun createTagsSection(): JPanel {
        val panel = JPanel(VerticalLayout(5))
        panel.isOpaque = false

        val headerPanel = JPanel(BorderLayout(0, 0))
        headerPanel.isOpaque = false

        val label = JBLabel("Tags:")
        label.font = label.font.deriveFont(java.awt.Font.BOLD)

        headerPanel.add(label, BorderLayout.WEST)
        panel.add(headerPanel)

        // Tags panel with checkboxes
        tagsPanel.isOpaque = false
        tagsScrollPane.border = BorderFactory.createEmptyBorder()
        tagsScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        tagsScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        tagsScrollPane.preferredSize = java.awt.Dimension(400, 100)

        panel.add(tagsScrollPane)

        return panel
    }

    /**
     * Set available tags for selection.
     *
     * @param tags List of tags to show as checkboxes
     */
    fun setAvailableTags(tags: List<Tag>) {
        LOG.debug("Setting ${tags.size} available tags for note creation")

        availableTags = tags
        tagCheckBoxes.clear()
        tagsPanel.removeAll()

        // Sort tags by name
        val sortedTags = tags.sortedBy { it.name }

        // Create checkbox for each tag
        sortedTags.forEach { tag ->
            val checkBox = JBCheckBox(tag.name)
            checkBox.toolTipText = "${tag.name} (${tag.getTotalCount()} items)"

            // Use tag color if available
            if (tag.hasColor()) {
                checkBox.foreground = java.awt.Color.decode(tag.color)
            }

            tagsPanel.add(checkBox)
            tagCheckBoxes[tag.id] = checkBox
        }

        // Show empty message if no tags
        if (tags.isEmpty()) {
            val emptyLabel = JBLabel("<html><i>No tags available</i></html>")
            tagsPanel.add(emptyLabel)
        }

        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    /**
     * Override OK button action to create note via API.
     */
    override fun doOKAction() {
        LOG.debug("OK button clicked - attempting to create note")

        // Validate inputs
        if (!validateInputs()) {
            return
        }

        // Disable UI and show loading
        titleField.isEnabled = false
        contentArea.isEnabled = false
        setOKActionEnabled(false)
        statusLabel.text = "Creating note..."
        statusLabel.foreground = java.awt.Color(0, 100, 0)
        statusLabel.isVisible = true

        val title = titleField.text.trim()
        val plainContent = contentArea.text
        val tagIds = getSelectedTagIds()

        // Create note via API
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val settings = com.sniphive.idea.config.SnipHiveSettings.getInstance()
                val email = settings.getUserEmail()

                // Check if user has E2EE enabled
                val securityStatus = apiService.getSecurityStatus(project)
                val userHasE2EE = securityStatus?.setupComplete == true

                // Ensure public key is available for encryption
                if (userHasE2EE && securityStatus?.e2eeProfile?.publicKeyJWK != null) {
                    val secureStorage = SecureCredentialStorage.getInstance()
                    val existingPublicKey = secureStorage.getPublicKey(project, email)
                    if (existingPublicKey.isNullOrEmpty()) {
                        val publicKeyJwk = securityStatus.e2eeProfile!!.publicKeyJWK.toString()
                        secureStorage.storePublicKey(project, email, publicKeyJwk)
                    }
                }

                // Encrypt content if E2EE is enabled
                val (content, encryptedDek) = if (userHasE2EE) {
                    val encrypted = E2EEContentService.encryptContentForSave(project, email, plainContent)
                    if (encrypted != null) {
                        Pair(encrypted.encryptedContent, encrypted.encryptedDek)
                    } else {
                        ApplicationManager.getApplication().invokeLater {
                            showError("E2EE encryption failed. Please check your encryption setup.")
                            titleField.isEnabled = true
                            contentArea.isEnabled = true
                            setOKActionEnabled(true)
                            statusLabel.isVisible = false
                        }
                        return@executeOnPooledThread
                    }
                } else {
                    Pair(plainContent, null)
                }

                val note = apiService.createNote(project, title, content, tagIds, encryptedDek)

                ApplicationManager.getApplication().invokeLater {
                    try {
                        if (note != null) {
                            createdNote = note

                            // Add to cache
                            NoteLookupService.getInstance(project).addNote(note)

                            statusLabel.text = "Note created successfully!"
                            statusLabel.foreground = java.awt.Color(0, 150, 0)

                            // Close dialog after short delay - store timer to prevent GC
                            closeTimer?.stop()
                            closeTimer = Timer(500) {
                                close(OK_EXIT_CODE)
                            }.apply {
                                isRepeats = false
                                start()
                            }
                        } else {
                            showError("Failed to create note. Please try again.")
                            titleField.isEnabled = true
                            contentArea.isEnabled = true
                            setOKActionEnabled(true)
                            statusLabel.isVisible = false
                        }
                    } catch (e: Exception) {
                        LOG.error("Error processing created note response", e)
                        showError("Error processing note: ${e.message}")
                        titleField.isEnabled = true
                        contentArea.isEnabled = true
                        setOKActionEnabled(true)
                        statusLabel.isVisible = false
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to create note", e)
                ApplicationManager.getApplication().invokeLater {
                    showError("Error: ${e.message}")
                    titleField.isEnabled = true
                    contentArea.isEnabled = true
                    setOKActionEnabled(true)
                    statusLabel.isVisible = false
                }
            }
        }
    }

    /**
     * Validate user input fields.
     *
     * @return true if inputs are valid
     */
    private fun validateInputs(): Boolean {
        val title = titleField.text.trim()

        // Clear previous errors
        hideError()

        // Validate title is not empty
        if (title.isEmpty()) {
            showError("Title is required")
            titleField.requestFocusInWindow()
            return false
        }

        // Validate title length
        if (title.length > MAX_TITLE_LENGTH) {
            showError("Title must be less than $MAX_TITLE_LENGTH characters")
            titleField.requestFocusInWindow()
            return false
        }

        return true
    }

    /**
     * Get list of selected tag IDs.
     *
     * @return List of selected tag IDs
     */
    private fun getSelectedTagIds(): List<String> {
        return tagCheckBoxes.filter { it.value.isSelected }.keys.toList()
    }

    private fun showError(message: String) {
        LOG.debug("Showing error: $message")
        errorLabel.text = message
        errorLabel.foreground = java.awt.Color(211, 47, 47)
        errorLabel.isVisible = true
    }

    private fun hideError() {
        errorLabel.isVisible = false
    }

    /**
     * Get the created note.
     *
     * @return The created Note, or null if creation failed
     */
    fun getCreatedNote(): Note? = createdNote

    override fun getPreferredFocusedComponent(): JComponent? = titleField

    override fun getDimensionServiceKey(): String? = "SnipHive.CreateNoteDialog"
}
