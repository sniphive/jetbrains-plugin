package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Dialog for creating a new tag.
 */
class CreateTagDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(CreateTagDialog::class.java)

        // Predefined colors for tags
        private val PREDEFINED_COLORS = listOf(
            "#EF4444" to "Red",
            "#F97316" to "Orange",
            "#F59E0B" to "Yellow",
            "#22C55E" to "Green",
            "#10B981" to "Emerald",
            "#14B8A6" to "Teal",
            "#06B6D4" to "Cyan",
            "#3B82F6" to "Blue",
            "#6366F1" to "Indigo",
            "#8B5CF6" to "Violet",
            "#A855F7" to "Purple",
            "#EC4899" to "Pink"
        )
    }

    // UI Components
    private val nameField = JBTextField()
    private val colorButtons = mutableListOf<JRadioButton>()
    private val colorButtonGroup = ButtonGroup()

    private var selectedColor: String = PREDEFINED_COLORS.first().first
    private var createdTag: Tag? = null

    init {
        title = "Create Tag"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(15, 20)

        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)

        // Name field
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JBLabel("Name:"), gbc)

        nameField.columns = 20
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(nameField, gbc)

        // Color selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        gbc.insets = Insets(15, 5, 5, 5)
        panel.add(JBLabel("Select a color:"), gbc)

        // Color grid
        val colorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
        PREDEFINED_COLORS.forEach { (color, name) ->
            val button = JRadioButton().apply {
                this.name = name
                actionCommand = color
                isSelected = color == selectedColor
                toolTipText = name
                addActionListener {
                    selectedColor = color
                }
            }
            colorButtons.add(button)
            colorButtonGroup.add(button)

            // Create color indicator
            val colorBox = JPanel().apply {
                preferredSize = java.awt.Dimension(24, 24)
                background = Color.decode(color)
                isOpaque = true
                border = BorderFactory.createLineBorder(Color.GRAY, 1)
            }

            val itemPanel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0))
            itemPanel.add(button)
            itemPanel.add(colorBox)
            colorPanel.add(itemPanel)
        }

        gbc.gridy = 2
        gbc.insets = Insets(5, 5, 5, 5)
        panel.add(colorPanel, gbc)

        // Set initial selection
        colorButtons.firstOrNull()?.isSelected = true

        return panel
    }

    override fun doOKAction() {
        val name = nameField.text.trim()

        if (name.isEmpty()) {
            Messages.showWarningDialog(project, "Tag name cannot be empty.", "Validation Error")
            nameField.requestFocusInWindow()
            return
        }

        if (name.length > 50) {
            Messages.showWarningDialog(project, "Tag name must be less than 50 characters.", "Validation Error")
            nameField.requestFocusInWindow()
            return
        }

        // Create tag via API
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val newTag = apiService.createTag(project, name, selectedColor)

                ApplicationManager.getApplication().invokeLater {
                    if (newTag != null) {
                        createdTag = newTag
                        close(OK_EXIT_CODE)
                    } else {
                        Messages.showErrorDialog(project, "Failed to create tag. Please try again.", "Error")
                        nameField.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to create tag", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to create tag: ${e.message}", "Error")
                }
            }
        }
    }

    fun getCreatedTag(): Tag? = createdTag

    override fun getPreferredFocusedComponent(): JComponent? = nameField
}

/**
 * Dialog for editing an existing tag.
 */
class EditTagDialog(private val project: Project, private val existingTag: Tag) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(EditTagDialog::class.java)

        private val PREDEFINED_COLORS = listOf(
            "#EF4444" to "Red",
            "#F97316" to "Orange",
            "#F59E0B" to "Yellow",
            "#22C55E" to "Green",
            "#10B981" to "Emerald",
            "#14B8A6" to "Teal",
            "#06B6D4" to "Cyan",
            "#3B82F6" to "Blue",
            "#6366F1" to "Indigo",
            "#8B5CF6" to "Violet",
            "#A855F7" to "Purple",
            "#EC4899" to "Pink"
        )
    }

    // UI Components
    private val nameField = JBTextField()
    private val colorButtons = mutableListOf<JRadioButton>()
    private val colorButtonGroup = ButtonGroup()

    private var selectedColor: String = existingTag.color ?: PREDEFINED_COLORS.first().first
    private var updatedTag: Tag? = null

    init {
        title = "Edit Tag"
        setOKButtonText("Save")
        init()

        // Populate with existing data
        nameField.text = existingTag.name
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(15, 20)

        val gbc = GridBagConstraints()
        gbc.fill = GridBagConstraints.HORIZONTAL
        gbc.insets = Insets(5, 5, 5, 5)

        // Name field
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        panel.add(JBLabel("Name:"), gbc)

        nameField.columns = 20
        gbc.gridx = 1
        gbc.weightx = 1.0
        panel.add(nameField, gbc)

        // Color selection
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 2
        gbc.weightx = 1.0
        gbc.insets = Insets(15, 5, 5, 5)
        panel.add(JBLabel("Select a color:"), gbc)

        // Color grid
        val colorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5))
        PREDEFINED_COLORS.forEach { (color, name) ->
            val button = JRadioButton().apply {
                this.name = name
                actionCommand = color
                isSelected = color == existingTag.color
                toolTipText = name
                addActionListener {
                    selectedColor = color
                }
            }
            colorButtons.add(button)
            colorButtonGroup.add(button)

            val colorBox = JPanel().apply {
                preferredSize = java.awt.Dimension(24, 24)
                background = Color.decode(color)
                isOpaque = true
                border = BorderFactory.createLineBorder(Color.GRAY, 1)
            }

            val itemPanel = JPanel(FlowLayout(FlowLayout.LEFT, 3, 0))
            itemPanel.add(button)
            itemPanel.add(colorBox)
            colorPanel.add(itemPanel)
        }

        gbc.gridy = 2
        gbc.insets = Insets(5, 5, 5, 5)
        panel.add(colorPanel, gbc)

        // Stats
        gbc.gridy = 3
        gbc.gridx = 0
        gbc.gridwidth = 2
        gbc.insets = Insets(15, 5, 5, 5)
        val statsLabel = JBLabel(
            "This tag is used in ${existingTag.snippetsCount} snippet(s) and ${existingTag.notesCount} note(s)."
        )
        statsLabel.foreground = java.awt.Color.GRAY
        panel.add(statsLabel, gbc)

        return panel
    }

    override fun doOKAction() {
        val name = nameField.text.trim()

        if (name.isEmpty()) {
            Messages.showWarningDialog(project, "Tag name cannot be empty.", "Validation Error")
            nameField.requestFocusInWindow()
            return
        }

        // Update tag via API
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val result = apiService.updateTag(project, existingTag.id, name, selectedColor)

                ApplicationManager.getApplication().invokeLater {
                    if (result != null) {
                        updatedTag = result
                        close(OK_EXIT_CODE)
                    } else {
                        Messages.showErrorDialog(project, "Failed to update tag. Please try again.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to update tag", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Failed to update tag: ${e.message}", "Error")
                }
            }
        }
    }

    fun getUpdatedTag(): Tag? = updatedTag

    override fun getPreferredFocusedComponent(): JComponent? = nameField
}

/**
 * Dialog for managing tags (list, create, edit, delete).
 */
class ManageTagsDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(ManageTagsDialog::class.java)
    }

    private val tagListModel = DefaultListModel<Tag>()
    private val tagList = JList(tagListModel)

    private val createButton = JButton("Create")
    private val editButton = JButton("Edit")
    private val deleteButton = JButton("Delete")

    private var tags: List<Tag> = emptyList()

    init {
        title = "Manage Tags"
        setOKButtonText("Close")
        init()

        // Load tags
        loadTags()

        // Setup button states
        updateButtonStates()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(10, 10))
        panel.border = JBUI.Borders.empty(15, 20)
        panel.preferredSize = java.awt.Dimension(450, 350)

        // List
        val scrollPane = JBScrollPane(tagList)
        scrollPane.border = BorderFactory.createEtchedBorder()

        // List selection listener
        tagList.addListSelectionListener {
            updateButtonStates()
        }

        panel.add(scrollPane, BorderLayout.CENTER)

        // Buttons
        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.X_AXIS)

        createButton.addActionListener { createTag() }
        editButton.addActionListener { editTag() }
        deleteButton.addActionListener { deleteTag() }

        buttonPanel.add(createButton)
        buttonPanel.add(Box.createHorizontalStrut(5))
        buttonPanel.add(editButton)
        buttonPanel.add(Box.createHorizontalStrut(5))
        buttonPanel.add(deleteButton)

        panel.add(buttonPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun loadTags() {
        tagListModel.clear()

        // Load from API
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                tags = apiService.getTags(project)

                ApplicationManager.getApplication().invokeLater {
                    tags.sortedBy { it.name }.forEach { tagListModel.addElement(it) }
                }
            } catch (e: Exception) {
                LOG.error("Failed to load tags", e)
            }
        }
    }

    private fun updateButtonStates() {
        val hasSelection = tagList.selectedValue != null
        editButton.isEnabled = hasSelection
        deleteButton.isEnabled = hasSelection
    }

    private fun createTag() {
        val dialog = CreateTagDialog(project)
        if (dialog.showAndGet()) {
            val newTag = dialog.getCreatedTag()
            if (newTag != null) {
                tagListModel.addElement(newTag)
                tags = tags + newTag
            }
        }
    }

    private fun editTag() {
        val selectedTag = tagList.selectedValue ?: return

        val dialog = EditTagDialog(project, selectedTag)
        if (dialog.showAndGet()) {
            val updatedTag = dialog.getUpdatedTag()
            if (updatedTag != null) {
                val index = tagList.selectedIndex
                tagListModel.setElementAt(updatedTag, index)
                tags = tags.map { if (it.id == updatedTag.id) updatedTag else it }
            }
        }
    }

    private fun deleteTag() {
        val selectedTag = tagList.selectedValue ?: return

        val confirmed = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete tag '${selectedTag.name}'?\n\n" +
            "It is used in ${selectedTag.snippetsCount} snippet(s) and ${selectedTag.notesCount} note(s).\n" +
            "This will remove the tag from all items.",
            "Delete Tag",
            Messages.getYesButton(),
            Messages.getNoButton(),
            Messages.getWarningIcon()
        )

        if (confirmed == Messages.YES) {
            // Delete via API
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val apiService = SnipHiveApiService.getInstance()
                    val success = apiService.deleteTag(project, selectedTag.id)

                    ApplicationManager.getApplication().invokeLater {
                        if (success) {
                            // Remove from list
                            val index = tagList.selectedIndex
                            tagListModel.removeElementAt(index)
                            tags = tags.filter { it.id != selectedTag.id }
                        } else {
                            Messages.showErrorDialog(project, "Failed to delete tag. Please try again.", "Error")
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to delete tag", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to delete tag: ${e.message}", "Error")
                    }
                }
            }
        }
    }

    fun getTags(): List<Tag> = tags.toList()
}