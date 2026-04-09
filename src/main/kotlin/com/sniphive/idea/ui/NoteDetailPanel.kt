package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import javax.swing.*

/**
 * Listener interface for note actions.
 */
interface NoteActionListener {
    fun onNoteDeleted(noteId: String)
    fun onNoteUpdated(note: Note)
}

/**
 * Panel for displaying note details in the SnipHive tool window.
 *
 * This panel provides:
 * - Full note content display with Markdown support
 * - Note metadata (title, tags, timestamps)
 * - Copy to clipboard functionality
 * - Edit note functionality
 * - Delete note functionality
 * - Pin/Favorite toggle
 * - Empty state when no note is selected
 * - Encrypted content indicator
 */
class NoteDetailPanel(private val project: com.intellij.openapi.project.Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(NoteDetailPanel::class.java)

        private const val STATE_EMPTY = "empty"
        private const val STATE_CONTENT = "content"
        private const val MAX_CONTENT_LENGTH = 500_000
    }

    private var currentNote: Note? = null
    private val actionListeners = mutableListOf<NoteActionListener>()

    // UI Components
    private val cardPanel: JPanel = JPanel()
    private val cardLayout: CardLayout = CardLayout()

    // Content panel components
    private val titleLabel: JBLabel = JBLabel()
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val contentTextArea: JBTextArea = JBTextArea()
    private val contentScrollPane: JScrollPane = JScrollPane(contentTextArea)
    private val metadataPanel: JPanel = JPanel(VerticalLayout(4))

    // Buttons (only secondary actions — primary copy/edit/delete moved to inline list icons)
    private val pinButton: JButton = JButton("Pin")
    private val favoriteButton: JButton = JButton("Favorite")
    private val clearButton: JButton = JButton("Clear")

    // State panels
    private val emptyPanel: JPanel = createEmptyPanel()
    private val contentPanel: JPanel = createContentPanel()

    private var currentState: String = STATE_EMPTY

    init {
        LOG.debug("Initializing NoteDetailPanel for project: ${project.name}")

        layout = BorderLayout()
        border = JBUI.Borders.empty(10, 10, 10, 10)

        cardPanel.layout = cardLayout

        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(contentPanel, STATE_CONTENT)

        add(cardPanel, BorderLayout.CENTER)

        setupContentComponents()

        showEmptyState()

        LOG.debug("NoteDetailPanel initialized successfully")
    }

    /**
     * Add an action listener.
     */
    fun addActionListener(listener: NoteActionListener) {
        actionListeners.add(listener)
    }

    /**
     * Remove an action listener.
     */
    fun removeActionListener(listener: NoteActionListener) {
        actionListeners.remove(listener)
    }

    private fun setupContentComponents() {
        // Configure title label
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 16f)

        // Configure tags panel
        tagsPanel.border = JBUI.Borders.empty(5, 0, 10, 0)

        // Configure content text area
        contentTextArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        contentTextArea.lineWrap = true
        contentTextArea.wrapStyleWord = true
        contentTextArea.tabSize = 4
        contentTextArea.isEditable = false
        contentTextArea.margin = JBUI.insets(10)
        contentTextArea.background = JBUI.CurrentTheme.DefaultTabs.background()

        // Configure content scroll pane
        contentScrollPane.setBorder(BorderFactory.createLineBorder(
            JBUI.CurrentTheme.DefaultTabs.borderColor(),
            1
        ))
        contentScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
        contentScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED)

        // Configure metadata panel
        metadataPanel.border = JBUI.Borders.empty(10, 0, 0, 0)

        // Configure buttons (primary actions are now inline icons on the list)
        pinButton.toolTipText = "Pin this note"
        pinButton.addActionListener { togglePin() }

        favoriteButton.toolTipText = "Add to favorites"
        favoriteButton.addActionListener { toggleFavorite() }

        clearButton.toolTipText = "Clear note selection"
        clearButton.addActionListener { clearNote() }
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(40, 20, 40, 20)

        val icon = JBLabel(com.intellij.icons.AllIcons.General.InformationDialog)
        val titleLabel = JBLabel("No Note Selected")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        val messageLabel = JBLabel("Select a note from the list to view details.")

        panel.add(icon, VerticalLayout.CENTER)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(messageLabel, VerticalLayout.CENTER)

        return panel
    }

    private fun createContentPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))

        // Header panel with title
        val headerPanel = JPanel(VerticalLayout(0))
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel)

        panel.add(headerPanel)
        panel.add(tagsPanel)
        panel.add(contentScrollPane)
        panel.add(metadataPanel)

        // Secondary actions only (Pin, Favorite, Clear)
        val secondaryButtonPanel = JPanel()
        secondaryButtonPanel.layout = BoxLayout(secondaryButtonPanel, BoxLayout.X_AXIS)
        secondaryButtonPanel.add(pinButton)
        secondaryButtonPanel.add(Box.createHorizontalStrut(JBUI.scale(5)))
        secondaryButtonPanel.add(favoriteButton)
        secondaryButtonPanel.add(Box.createHorizontalStrut(JBUI.scale(10)))
        secondaryButtonPanel.add(clearButton)
        secondaryButtonPanel.add(Box.createHorizontalGlue())

        panel.add(secondaryButtonPanel)

        return panel
    }

    /**
     * Set the note to display.
     */
    fun setNote(note: Note?) {
        LOG.debug("Setting note: ${note?.id ?: "null"}")

        currentNote = note

        if (note == null) {
            showEmptyState()
        } else {
            showContentState()
            updateContentPanel(note)
        }
    }

    /**
     * Get the currently displayed note.
     */
    fun getNote(): Note? = currentNote

    private fun updateContentPanel(note: Note) {
        // Update title
        titleLabel.text = escapeHtml(note.title)

        // Update tags
        updateTagsPanel(note.tags)

        // Update content
        if (note.isEncrypted()) {
            contentTextArea.text = "[ Encrypted Content ]\n\n" +
                "This note content is encrypted with E2EE.\n" +
                "Unlock with your master password or recovery code to view the content."
            contentTextArea.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        } else {
            val content = note.content.take(MAX_CONTENT_LENGTH)
            contentTextArea.text = content
            contentTextArea.foreground = JBUI.CurrentTheme.Label.foreground()

            if (note.content.length > MAX_CONTENT_LENGTH) {
                LOG.warn("Content truncated for note ${note.id}: ${note.content.length} > $MAX_CONTENT_LENGTH")
            }
        }

        // Update metadata
        updateMetadataPanel(note)

        // Update button states
        updateButtonStates(note)

        // Scroll to top of content
        SwingUtilities.invokeLater {
            contentScrollPane.verticalScrollBar.value = 0
        }
    }

    private fun updateButtonStates(note: Note) {
        pinButton.text = if (note.isPinned) "Unpin" else "Pin"
        pinButton.toolTipText = if (note.isPinned) "Remove from pinned" else "Pin this note"

        favoriteButton.text = if (note.isFavorite) "Unfavorite" else "Favorite"
        favoriteButton.toolTipText = if (note.isFavorite) "Remove from favorites" else "Add to favorites"
    }

    private fun updateTagsPanel(tags: List<Tag>) {
        tagsPanel.removeAll()

        if (tags.isNotEmpty()) {
            val tagsRow = JPanel()
            tagsRow.layout = BoxLayout(tagsRow, BoxLayout.X_AXIS)
            tagsRow.isOpaque = false

            tags.forEach { tag ->
                val tagLabel = createTagLabel(tag)
                tagsRow.add(tagLabel)
                tagsRow.add(Box.createHorizontalStrut(JBUI.scale(5)))
            }

            tagsPanel.add(tagsRow)
        }

        tagsPanel.revalidate()
        tagsPanel.repaint()
    }

    private fun createTagLabel(tag: Tag): JLabel {
        val color = tag.getColorOrDefault()
        val html = "<html><div style='background-color: $color; color: white; " +
            "padding: 2px 8px; border-radius: 3px; font-size: 11px;'>${escapeHtml(tag.name)}</div></html>"
        return JLabel(html)
    }

    private fun updateMetadataPanel(note: Note) {
        metadataPanel.removeAll()

        // Created timestamp
        note.createdAt?.let { created ->
            val label = JBLabel("Created: ${formatTimestamp(created)}")
            label.font = label.font.deriveFont(10f)
            label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            metadataPanel.add(label)
        }

        // Updated timestamp
        note.updatedAt?.let { updated ->
            val label = JBLabel("Updated: ${formatTimestamp(updated)}")
            label.font = label.font.deriveFont(10f)
            label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            metadataPanel.add(label)
        }

        // Public indicator
        if (note.isPublic) {
            val label = JBLabel("Public note")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        // Pinned indicator
        if (note.isPinned) {
            val label = JBLabel("Pinned")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        // Favorite indicator
        if (note.isFavorite) {
            val label = JBLabel("Favorite")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        // Encrypted indicator
        if (note.isEncrypted()) {
            val label = JBLabel("Encrypted (E2EE)")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(255, 152, 0)
            metadataPanel.add(label)
        }

        metadataPanel.revalidate()
        metadataPanel.repaint()
    }

    private fun togglePin() {
        val note = currentNote ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNotePin(project, note.slug ?: note.id)

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        setNote(updated)
                        actionListeners.forEach { it.onNoteUpdated(updated) }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle pin", e)
            }
        }
    }

    private fun toggleFavorite() {
        val note = currentNote ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNoteFavorite(project, note.slug ?: note.id)

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        setNote(updated)
                        actionListeners.forEach { it.onNoteUpdated(updated) }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle favorite", e)
            }
        }
    }

    fun clearNote() {
        LOG.debug("Clearing note selection")
        setNote(null)
    }

    private fun showEmptyState() {
        LOG.debug("Showing empty state")
        currentState = STATE_EMPTY
        cardLayout.show(cardPanel, STATE_EMPTY)
        cursor = Cursor.getDefaultCursor()
    }

    private fun showContentState() {
        LOG.debug("Showing content state")
        currentState = STATE_CONTENT
        cardLayout.show(cardPanel, STATE_CONTENT)
        cursor = Cursor.getDefaultCursor()
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            timestamp.substring(0, 10)
        } catch (e: Exception) {
            ""
        }
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
}