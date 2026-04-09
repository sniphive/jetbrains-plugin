package com.sniphive.idea.ui

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
import com.sniphive.idea.models.Tag
import java.awt.BorderLayout
import javax.swing.*

/**
 * Dialog for creating a new SnipHive snippet from selected code.
 *
 * This dialog provides:
 * - Title input field for snippet naming
 * - Language selection dropdown
 * - Visibility selection (public/private)
 * - Tag selection with checkboxes
 * - Preview of selected code content
 * - Loading state during snippet creation
 *
 * Usage Example:
 * ```kotlin
 * val dialog = CreateSnippetDialog(project, selectedCode, "kotlin")
 * if (dialog.showAndGet()) {
 *     val result = dialog.getCreateResult()
 *     // result.title, result.language, result.tags, result.isPublic are populated
 * }
 * ```
 *
 * Security Note:
 * - Code content is read from editor (no sensitive data exposure)
 * - No credentials or tokens handled in this dialog
 * - Tags and language are metadata only
 *
 * @property project The current project context
 * @property initialContent The selected code content to create snippet from
 * @property initialLanguage The detected programming language from file type
 */
class CreateSnippetDialog(
    private val project: Project,
    private val initialContent: String,
    private val initialLanguage: String
) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(CreateSnippetDialog::class.java)

        /**
         * Minimum title length for validation.
         */
        private const val MIN_TITLE_LENGTH = 1

        /**
         * Maximum title length for snippets.
         */
        private const val MAX_TITLE_LENGTH = 255

        /**
         * Maximum content preview length.
         */
        private const val MAX_PREVIEW_LENGTH = 500
    }

    /**
     * Result data class containing the snippet creation data.
     *
     * @property title The snippet title
     * @property language The snippet programming language
     * @property tags List of selected tags
     * @property content The snippet content
     * @property isPublic Whether the snippet is publicly accessible
     */
    data class CreateResult(
        val title: String,
        val language: String,
        val tags: List<Tag>,
        val content: String,
        val isPublic: Boolean = false
    )

    // UI Components
    private val titleField: JBTextField = JBTextField()
    private val languageComboBox: JComboBox<String> = JComboBox()
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val tagsScrollPane: JBScrollPane = JBScrollPane(tagsPanel)
    private val visibilityCheckBox: JBCheckBox = JBCheckBox("Make this snippet public")
    private val contentPreview: JBTextArea = JBTextArea()
    private val errorLabel: JBLabel = JBLabel()

    // Data
    private val tagCheckBoxes = mutableMapOf<String, JBCheckBox>()
    private var availableTags: List<Tag> = emptyList()
    private var createResult: CreateResult? = null

    init {
        LOG.debug("Initializing CreateSnippetDialog for project: ${project.name}")

        title = "Create Snippet"
        setOKButtonText("Create")
        setCancelButtonText("Cancel")

        // Initialize dialog
        init()
    }

    /**
     * Create the center panel for the dialog.
     * This is the main content area of the dialog.
     */
    override fun createCenterPanel(): JComponent {
        LOG.debug("Creating create snippet dialog center panel")

        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Create a new snippet from your selection:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        panel.add(headerLabel)

        // Title field
        val titlePanel = createTitleField()
        panel.add(titlePanel)

        // Language dropdown
        val languagePanel = createLanguageDropdown()
        panel.add(languagePanel)

        // Visibility checkbox
        visibilityCheckBox.toolTipText = "Public snippets can be viewed by anyone with the link"
        panel.add(visibilityCheckBox)

        // Tags selection
        val tagsSection = createTagsSection()
        panel.add(tagsSection)

        // Content preview
        val previewSection = createContentPreview()
        panel.add(previewSection)

        // Error label (hidden by default)
        errorLabel.isVisible = false
        panel.add(errorLabel)

        // Set focus on title field
        SwingUtilities.invokeLater {
            titleField.requestFocusInWindow()
        }

        LOG.debug("Create snippet dialog center panel created")
        return panel
    }

    /**
     * Create title input field with label.
     */
    private fun createTitleField(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Title:")
        titleField.setColumns(40)
        titleField.setToolTipText("Enter a descriptive title for your snippet")

        panel.add(label, BorderLayout.WEST)
        panel.add(titleField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create language selection dropdown.
     */
    private fun createLanguageDropdown(): JPanel {
        val panel = JPanel(BorderLayout(0, 5))
        panel.isOpaque = false

        val label = JBLabel("Language:")

        // Set initial language as selected
        languageComboBox.addItem(initialLanguage)
        languageComboBox.setToolTipText("Select the programming language")

        panel.add(label, BorderLayout.WEST)
        panel.add(languageComboBox, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create tags selection section.
     */
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
        tagsScrollPane.setBorder(BorderFactory.createEmptyBorder())
        tagsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        tagsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
        tagsScrollPane.setPreferredSize(java.awt.Dimension(400, 120))

        panel.add(tagsScrollPane)

        return panel
    }

    /**
     * Create content preview section (read-only).
     */
    private fun createContentPreview(): JPanel {
        val panel = JPanel(VerticalLayout(5))
        panel.isOpaque = false

        val headerLabel = JBLabel("Content Preview:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD)
        panel.add(headerLabel)

        contentPreview.isEditable = false
        contentPreview.rows = 8
        contentPreview.lineWrap = false
        contentPreview.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)

        // Show preview (truncated if too long)
        val preview = if (initialContent.length > MAX_PREVIEW_LENGTH) {
            initialContent.take(MAX_PREVIEW_LENGTH) + "... (${initialContent.length - MAX_PREVIEW_LENGTH} more characters)"
        } else {
            initialContent
        }
        contentPreview.text = preview

        val scrollPane = JBScrollPane(contentPreview)
        scrollPane.setPreferredSize(java.awt.Dimension(500, 150))

        panel.add(scrollPane)

        return panel
    }

    /**
     * Set available tags for selection.
     *
     * @param tags List of tags to show as checkboxes
     */
    fun setAvailableTags(tags: List<Tag>) {
        LOG.debug("Setting ${tags.size} available tags for snippet creation")

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
            val emptyLabel = JBLabel("<html><i>No tags available - you can add tags later</i></html>")
            tagsPanel.add(emptyLabel)
        }

        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    /**
     * Set available languages for language dropdown.
     *
     * @param languages List of language names to show in dropdown
     */
    fun setAvailableLanguages(languages: List<String>) {
        LOG.debug("Setting ${languages.size} available languages")

        // Save current selection
        val currentSelection = languageComboBox.getSelectedItem() as? String

        // Clear and repopulate combo box
        languageComboBox.removeAllItems()

        // Sort languages alphabetically
        languages.sorted().forEach { language ->
            languageComboBox.addItem(language)
        }

        // Restore selection if possible
        if (currentSelection != null && languages.contains(currentSelection)) {
            languageComboBox.setSelectedItem(currentSelection)
        } else if (languages.contains(initialLanguage)) {
            languageComboBox.setSelectedItem(initialLanguage)
        } else if (languages.isNotEmpty()) {
            languageComboBox.setSelectedItem(languages.first())
        }
    }

    /**
     * Set initial title for the snippet.
     *
     * @param title The initial title text
     */
    fun setInitialTitle(title: String) {
        titleField.text = title
    }

    /**
     * Set selected tags by IDs.
     *
     * @param tagIds List of tag IDs to select
     */
    fun setSelectedTagIds(tagIds: List<String>) {
        tagCheckBoxes.forEach { (id, checkBox) ->
            checkBox.isSelected = id in tagIds
        }
    }

    /**
     * Override OK button action to create snippet.
     */
    override fun doOKAction() {
        LOG.debug("OK button clicked - attempting to create snippet")

        // Validate inputs
        if (!validateInputs()) {
            return
        }

        // Prepare create result
        createResult = CreateResult(
            title = titleField.getText().trim(),
            language = languageComboBox.getSelectedItem() as? String ?: initialLanguage,
            tags = getSelectedTags(),
            content = initialContent,
            isPublic = visibilityCheckBox.isSelected
        )

        LOG.info("Snippet creation dialog confirmed: title='${createResult!!.title}', language=${createResult!!.language}, isPublic=${createResult!!.isPublic}")
        close(OK_EXIT_CODE)
    }

    /**
     * Validate user input fields.
     *
     * @return true if inputs are valid
     */
    private fun validateInputs(): Boolean {
        val title = titleField.getText().trim()

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

        // Validate language selection
        if (languageComboBox.getSelectedItem() == null) {
            showError("Language is required")
            languageComboBox.requestFocusInWindow()
            return false
        }

        return true
    }

    /**
     * Get list of selected tags.
     *
     * @return List of selected Tag objects
     */
    private fun getSelectedTags(): List<Tag> {
        val selectedIds = tagCheckBoxes.filter { it.value.isSelected }.keys.toList()
        return availableTags.filter { it.id in selectedIds }
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
     * Get the create result from the dialog.
     *
     * @return The CreateResult containing snippet data, or null if not completed
     */
    fun getCreateResult(): CreateResult? = createResult

    /**
     * Preferred focus component is title field.
     */
    override fun getPreferredFocusedComponent(): JComponent? {
        return titleField
    }

    /**
     * Help ID for documentation (optional).
     */
    override fun getHelpId(): String? {
        return "sniphive.create.snippet"
    }

    /**
     * Dialog dimension settings.
     */
    override fun getDimensionServiceKey(): String? {
        return "SnipHive.CreateSnippetDialog"
    }
}
