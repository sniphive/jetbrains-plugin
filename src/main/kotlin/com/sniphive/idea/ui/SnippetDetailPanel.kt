package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import javax.swing.*

/**
 * Listener interface for snippet actions.
 */
interface SnippetActionListener {
    fun onSnippetDeleted(snippetId: String)
    fun onSnippetUpdated(snippet: Snippet)
}

/**
 * Panel for displaying snippet details in the SnipHive tool window.
 *
 * This panel provides:
 * - Full snippet content display with syntax-aware text area
 * - Snippet metadata (title, language, tags, timestamps)
 * - Pin/Favorite toggle
 * - Empty state when no snippet is selected
 * - Encrypted content indicator and message
 *
 * Note: Copy/Edit/Delete actions have been moved to inline icons on the list panel.
 *
 * @property project The current project
 */
class SnippetDetailPanel(private val project: com.intellij.openapi.project.Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(SnippetDetailPanel::class.java)

        private const val STATE_EMPTY = "empty"
        private const val STATE_CONTENT = "content"
        private const val MAX_CONTENT_LENGTH = 500_000
    }

    private var currentSnippet: Snippet? = null
    private val actionListeners = mutableListOf<SnippetActionListener>()

    // UI Components
    private val cardPanel: JPanel = JPanel()
    private val cardLayout: CardLayout = CardLayout()

    // Content panel components
    private val titleLabel: JBLabel = JBLabel()
    private val languageLabel: JBLabel = JBLabel()
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val contentTextArea: JBTextArea = JBTextArea()
    private val contentScrollPane: JScrollPane = JScrollPane(contentTextArea)
    private val metadataPanel: JPanel = JPanel(VerticalLayout(4))

    // Buttons (secondary actions only — primary copy/edit/delete moved to inline list icons)
    private val pinButton: JButton = JButton("Pin")
    private val favoriteButton: JButton = JButton("Favorite")
    private val clearButton: JButton = JButton("Clear")

    // State panels
    private val emptyPanel: JPanel = createEmptyPanel()
    private val contentPanel: JPanel = createContentPanel()

    private var currentState: String = STATE_EMPTY

    init {
        LOG.debug("Initializing SnippetDetailPanel for project: ${project.name}")

        layout = BorderLayout()
        border = JBUI.Borders.empty(10, 10, 10, 10)

        cardPanel.layout = cardLayout
        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(contentPanel, STATE_CONTENT)

        add(cardPanel, BorderLayout.CENTER)

        setupContentComponents()

        showEmptyState()

        LOG.debug("SnippetDetailPanel initialized successfully")
    }

    fun addActionListener(listener: SnippetActionListener) {
        actionListeners.add(listener)
    }

    fun removeActionListener(listener: SnippetActionListener) {
        actionListeners.remove(listener)
    }

    private fun setupContentComponents() {
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 16f)

        languageLabel.font = languageLabel.font.deriveFont(12f)
        languageLabel.foreground = JBUI.CurrentTheme.Label.foreground()

        tagsPanel.border = JBUI.Borders.empty(5, 0, 10, 0)

        contentTextArea.font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        contentTextArea.lineWrap = false
        contentTextArea.wrapStyleWord = false
        contentTextArea.tabSize = 4
        contentTextArea.isEditable = false
        contentTextArea.margin = JBUI.insets(10)
        contentTextArea.background = JBUI.CurrentTheme.DefaultTabs.background()

        contentScrollPane.border = BorderFactory.createLineBorder(
            JBUI.CurrentTheme.DefaultTabs.borderColor(),
            1
        )
        contentScrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        contentScrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED

        metadataPanel.border = JBUI.Borders.empty(10, 0, 0, 0)

        pinButton.toolTipText = "Pin this snippet"
        pinButton.addActionListener { togglePin() }

        favoriteButton.toolTipText = "Add to favorites"
        favoriteButton.addActionListener { toggleFavorite() }

        clearButton.toolTipText = "Clear snippet selection"
        clearButton.addActionListener { clearSnippet() }
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = JBUI.Borders.empty(40, 20, 40, 20)

        val icon = JBLabel(com.intellij.icons.AllIcons.General.InformationDialog)
        val titleLabel = JBLabel("No Snippet Selected")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        val messageLabel = JBLabel("Select a snippet from the list to view details.")

        panel.add(icon, VerticalLayout.CENTER)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(messageLabel, VerticalLayout.CENTER)

        return panel
    }

    private fun createContentPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))

        val headerPanel = JPanel(VerticalLayout(0))
        headerPanel.isOpaque = false
        headerPanel.add(titleLabel)
        headerPanel.add(languageLabel)

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

    fun setSnippet(snippet: Snippet?) {
        LOG.debug("Setting snippet: ${snippet?.id ?: "null"}")

        currentSnippet = snippet

        if (snippet == null) {
            showEmptyState()
        } else {
            showContentState()
            updateContentPanel(snippet)
        }
    }

    fun getSnippet(): Snippet? = currentSnippet

    private fun updateContentPanel(snippet: Snippet) {
        titleLabel.text = escapeHtml(snippet.title)

        if (snippet.language != null) {
            languageLabel.text = "Language: ${snippet.getDisplayLanguage()}"
            languageLabel.isVisible = true
        } else {
            languageLabel.isVisible = false
        }

        updateTagsPanel(snippet.tags)

        if (snippet.isEncrypted()) {
            contentTextArea.text = "[ Encrypted Content ]\n\n" +
                "This snippet content is encrypted with E2EE.\n" +
                "Unlock with your master password or recovery code to view the content."
            contentTextArea.foreground = JBUI.CurrentTheme.Label.disabledForeground()
        } else {
            val content = snippet.content.take(MAX_CONTENT_LENGTH)
            contentTextArea.text = content
            contentTextArea.foreground = JBUI.CurrentTheme.Label.foreground()

            if (snippet.content.length > MAX_CONTENT_LENGTH) {
                LOG.warn("Content truncated for snippet ${snippet.id}: ${snippet.content.length} > $MAX_CONTENT_LENGTH")
            }
        }

        updateMetadataPanel(snippet)
        updateButtonStates(snippet)

        SwingUtilities.invokeLater {
            contentScrollPane.verticalScrollBar.value = 0
        }
    }

    private fun updateButtonStates(snippet: Snippet) {
        pinButton.text = if (snippet.isPinned) "Unpin" else "Pin"
        pinButton.toolTipText = if (snippet.isPinned) "Remove from pinned" else "Pin this snippet"

        favoriteButton.text = if (snippet.isFavorite) "Unfavorite" else "Favorite"
        favoriteButton.toolTipText = if (snippet.isFavorite) "Remove from favorites" else "Add to favorites"
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

    private fun updateMetadataPanel(snippet: Snippet) {
        metadataPanel.removeAll()

        snippet.createdAt?.let { created ->
            val label = JBLabel("Created: ${formatTimestamp(created)}")
            label.font = label.font.deriveFont(10f)
            label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            metadataPanel.add(label)
        }

        snippet.updatedAt?.let { updated ->
            val label = JBLabel("Updated: ${formatTimestamp(updated)}")
            label.font = label.font.deriveFont(10f)
            label.foreground = JBUI.CurrentTheme.Label.disabledForeground()
            metadataPanel.add(label)
        }

        if (snippet.isPublic) {
            val label = JBLabel("Public snippet")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        if (snippet.isPinned) {
            val label = JBLabel("Pinned")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        if (snippet.isFavorite) {
            val label = JBLabel("Favorite")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(76, 175, 80)
            metadataPanel.add(label)
        }

        if (snippet.isEncrypted()) {
            val label = JBLabel("Encrypted (E2EE)")
            label.font = label.font.deriveFont(java.awt.Font.BOLD, 10f)
            label.foreground = java.awt.Color(255, 152, 0)
            metadataPanel.add(label)
        }

        metadataPanel.revalidate()
        metadataPanel.repaint()
    }

    private fun togglePin() {
        val snippet = currentSnippet ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.togglePin(project, snippet.slug ?: snippet.id)

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        setSnippet(updated)
                        actionListeners.forEach { it.onSnippetUpdated(updated) }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle pin", e)
            }
        }
    }

    private fun toggleFavorite() {
        val snippet = currentSnippet ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleFavorite(project, snippet.slug ?: snippet.id)

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        setSnippet(updated)
                        actionListeners.forEach { it.onSnippetUpdated(updated) }
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle favorite", e)
            }
        }
    }

    fun clearSnippet() {
        LOG.debug("Clearing snippet selection")
        setSnippet(null)
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
