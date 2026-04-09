package com.sniphive.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.sniphive.idea.editor.EditorUtils
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Listener for archive actions.
 */
interface ArchiveActionListener {
    fun onSnippetRestored(snippet: Snippet)
    fun onNoteRestored(note: Note)
    fun onSnippetDeletedPermanently(snippetId: String)
    fun onNoteDeletedPermanently(noteId: String)
}

/**
 * Archived content item wrapper.
 */
sealed class ArchivedItem {
    abstract val id: String
    abstract val title: String
    abstract val archivedAt: String?
    abstract val type: ContentType

    data class ArchivedSnippet(val snippet: Snippet) : ArchivedItem() {
        override val id: String get() = snippet.id
        override val title: String get() = snippet.title
        override val archivedAt: String? get() = snippet.archivedAt
        override val type: ContentType get() = ContentType.SNIPPET
    }

    data class ArchivedNote(val note: Note) : ArchivedItem() {
        override val id: String get() = note.id
        override val title: String get() = note.title
        override val archivedAt: String? get() = note.archivedAt
        override val type: ContentType get() = ContentType.NOTE
    }
}

/**
 * Panel for displaying archived items as interactive cards.
 *
 * Features:
 * - Filter tabs: All / Snippets / Notes
 * - Card-based layout matching SnippetListPanel/NoteListPanel styling
 * - Inline Restore and Delete Permanently buttons on each card
 * - Loading, empty, and error states
 * - Double-click to open in editor
 */
class ArchivePanel(private val project: Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(ArchivePanel::class.java)
        private const val STATE_LOADING = "loading"
        private const val STATE_EMPTY = "empty"
        private const val STATE_ERROR = "error"
        private const val STATE_POPULATED = "populated"

        // Theme-aware colors for cards (matching SnippetListPanel)
        private val CARD_BACKGROUND = JBColor.namedColor("Panel.background", JBColor(0xfafafc, 0x2b2b2b))
        private val CARD_BORDER_COLOR = JBColor.namedColor("Component.borderColor", JBColor(0xdcdcdc, 0x646464))
        private val CARD_HOVER_BACKGROUND = JBColor(0xf0f5ff, 0x282d35)
        private val CARD_HOVER_BORDER_COLOR = JBColor.namedColor("Focus.borderColor", JBColor(0x6496ff, 0x6496ff))
        private val TITLE_FOREGROUND = JBColor.namedColor("Label.foreground", JBColor(0x1e1e1e, 0xffffff))
        private val DATE_FOREGROUND = JBColor.namedColor("Label.disabledForeground", JBColor(0x888888, 0x888888))
        private val BUTTON_FOREGROUND = JBColor.namedColor("Button.foreground", JBColor(0x505050, 0xffffff))

        // AllIcons to avoid IntelliJ VFS SVG language detection bug
        private val ICON_RESTORE = AllIcons.Actions.Rollback
        private val ICON_DELETE = AllIcons.Actions.GC
        private val ICON_LOCKED = AllIcons.Nodes.Locked
        private val ICON_GLOBE = AllIcons.General.Web
    }

    private val actionListeners = mutableListOf<ArchiveActionListener>()

    // UI Components
    private val cardsContainer: JPanel = JPanel()
    private val scrollPane: JBScrollPane = JBScrollPane()
    private val cardPanel: JPanel = JPanel()
    private val cardLayout: CardLayout = CardLayout()

    // State panels
    private val loadingPanel: JPanel = createLoadingPanel()
    private val emptyPanel: JPanel = createEmptyPanel()
    private val errorPanel: JPanel = createErrorPanel()
    private val contentPanel: JPanel = JPanel(BorderLayout())

    // Filter tabs
    private val allTab: JButton = JButton("All")
    private val snippetsTab: JButton = JButton("Snippets")
    private val notesTab: JButton = JButton("Notes")

    // Data
    private var allItems: List<ArchivedItem> = emptyList()
    private var currentFilter: FilterType = FilterType.ALL
    private var currentState: String = STATE_LOADING

    private enum class FilterType {
        ALL, SNIPPETS, NOTES
    }

    init {
        layout = BorderLayout()

        // Filter panel
        val filterPanel = createFilterPanel()
        add(filterPanel, BorderLayout.NORTH)

        // Content panel with card layout
        cardPanel.layout = cardLayout
        cardPanel.add(loadingPanel, STATE_LOADING)
        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(errorPanel, STATE_ERROR)
        cardPanel.add(contentPanel, STATE_POPULATED)
        add(cardPanel, BorderLayout.CENTER)

        setupContentPanel()
        showLoadingState()
    }

    private fun createFilterPanel(): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.X_AXIS)
        panel.border = JBUI.Borders.empty(5, 10)

        allTab.isOpaque = false
        snippetsTab.isOpaque = false
        notesTab.isOpaque = false

        allTab.addActionListener { setFilter(FilterType.ALL) }
        snippetsTab.addActionListener { setFilter(FilterType.SNIPPETS) }
        notesTab.addActionListener { setFilter(FilterType.NOTES) }

        panel.add(JBLabel("Archive - Filter: "))
        panel.add(Box.createHorizontalStrut(JBUI.scale(5)))
        panel.add(allTab)
        panel.add(Box.createHorizontalStrut(JBUI.scale(5)))
        panel.add(snippetsTab)
        panel.add(Box.createHorizontalStrut(JBUI.scale(5)))
        panel.add(notesTab)
        panel.add(Box.createHorizontalGlue())

        updateFilterButtons()
        return panel
    }

    private fun setFilter(filter: FilterType) {
        currentFilter = filter
        updateFilterButtons()
        applyFilter()
    }

    private fun updateFilterButtons() {
        allTab.isEnabled = currentFilter != FilterType.ALL
        snippetsTab.isEnabled = currentFilter != FilterType.SNIPPETS
        notesTab.isEnabled = currentFilter != FilterType.NOTES
    }

    private fun setupContentPanel() {
        cardsContainer.layout = VerticalLayout(0)
        cardsContainer.isOpaque = false

        scrollPane.setViewportView(cardsContainer)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        contentPanel.add(scrollPane, BorderLayout.CENTER)
    }

    // ───────────────────────── State panels ─────────────────────────

    private fun createLoadingPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        val spinner = JProgressBar()
        spinner.isIndeterminate = true
        spinner.putClientProperty("JProgressBar.style", "large")

        val label = JBLabel("Loading archived items...")
        label.font = label.font.deriveFont(14f)

        panel.add(spinner, VerticalLayout.CENTER)
        panel.add(label, VerticalLayout.CENTER)
        return panel
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.InformationDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Archive is Empty")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Archived items will appear here."), VerticalLayout.CENTER)
        return panel
    }

    private fun createErrorPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.ErrorDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Error Loading Archive")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Please check your connection and try again."), VerticalLayout.CENTER)
        return panel
    }

    // ───────────────────────── State management ─────────────────────────

    fun showLoadingState() {
        currentState = STATE_LOADING
        cardLayout.show(cardPanel, STATE_LOADING)
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
    }

    fun showEmptyState() {
        currentState = STATE_EMPTY
        cardLayout.show(cardPanel, STATE_EMPTY)
        cursor = Cursor.getDefaultCursor()
    }

    fun showErrorState(message: String? = null) {
        if (message != null) {
            updateErrorMessage(message)
        }
        currentState = STATE_ERROR
        cardLayout.show(cardPanel, STATE_ERROR)
        cursor = Cursor.getDefaultCursor()
    }

    private fun updateErrorMessage(message: String) {
        for (i in 0 until errorPanel.componentCount) {
            val component = errorPanel.getComponent(i)
            if (component is JBLabel && component.text.startsWith("Please check")) {
                component.text = message
                break
            }
        }
    }

    private fun showPopulatedState() {
        currentState = STATE_POPULATED
        cardLayout.show(cardPanel, STATE_POPULATED)
        cursor = Cursor.getDefaultCursor()
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            showLoadingState()
        } else {
            if (allItems.isEmpty()) showEmptyState() else showPopulatedState()
        }
    }

    // ───────────────────────── List manipulation ─────────────────────────

    /**
     * Set items to display.
     */
    fun setItems(snippets: List<Snippet>, notes: List<Note>) {
        allItems = snippets.map { ArchivedItem.ArchivedSnippet(it) } +
                   notes.map { ArchivedItem.ArchivedNote(it) }

        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (currentFilter) {
            FilterType.ALL -> allItems
            FilterType.SNIPPETS -> allItems.filter { it.type == ContentType.SNIPPET }
            FilterType.NOTES -> allItems.filter { it.type == ContentType.NOTE }
        }

        rebuildCards(filtered)

        if (filtered.isEmpty()) showEmptyState() else showPopulatedState()
    }

    fun clearItems() {
        allItems = emptyList()
        rebuildCards(emptyList())
        showEmptyState()
    }

    fun getItemCount(): Int = allItems.size

    // ───────────────────────── Action listeners ─────────────────────────

    fun addArchiveActionListener(listener: ArchiveActionListener) {
        actionListeners.add(listener)
    }

    fun removeArchiveActionListener(listener: ArchiveActionListener) {
        actionListeners.remove(listener)
    }

    private fun notifySnippetRestored(snippet: Snippet) {
        actionListeners.forEach { it.onSnippetRestored(snippet) }
    }

    private fun notifyNoteRestored(note: Note) {
        actionListeners.forEach { it.onNoteRestored(note) }
    }

    private fun notifySnippetDeletedPermanently(snippetId: String) {
        actionListeners.forEach { it.onSnippetDeletedPermanently(snippetId) }
    }

    private fun notifyNoteDeletedPermanently(noteId: String) {
        actionListeners.forEach { it.onNoteDeletedPermanently(noteId) }
    }

    // ───────────────────────── Card rendering ─────────────────────────

    private fun rebuildCards(items: List<ArchivedItem>) {
        cardsContainer.removeAll()

        items.forEach { item ->
            cardsContainer.add(createArchivedCard(item))
            cardsContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun createArchivedCard(item: ArchivedItem): JPanel {
        val card = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(4)))
        card.border = CompoundBorder(
            LineBorder(CARD_BORDER_COLOR, 1, true),
            EmptyBorder(JBUI.insets(8, 12, 8, 12))
        )
        card.background = CARD_BACKGROUND
        card.isOpaque = true
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // ── Top: Title + Type Badge + Indicator Icons ──
        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false

        // Title row with type badge
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
        titleRow.isOpaque = false

        val titleLabel = JLabel(escapeHtml(item.title)).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            foreground = TITLE_FOREGROUND
        }
        titleRow.add(titleLabel)

        // Type badge
        val badgeLabel = createTypeBadge(item.type)
        titleRow.add(badgeLabel)

        topRow.add(titleRow, BorderLayout.CENTER)

        // Right side: indicator icons
        val indicatorsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        indicatorsPanel.isOpaque = false

        when (item) {
            is ArchivedItem.ArchivedSnippet -> {
                if (item.snippet.isPublic) {
                    indicatorsPanel.add(JBLabel(ICON_GLOBE).apply {
                        toolTipText = "Public snippet"
                        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                    })
                } else {
                    indicatorsPanel.add(JBLabel(ICON_LOCKED).apply {
                        toolTipText = "Private snippet"
                        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                    })
                }
            }
            is ArchivedItem.ArchivedNote -> {
                if (item.note.isPublic) {
                    indicatorsPanel.add(JBLabel(ICON_GLOBE).apply {
                        toolTipText = "Public note"
                        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                    })
                } else {
                    indicatorsPanel.add(JBLabel(ICON_LOCKED).apply {
                        toolTipText = "Private note"
                        preferredSize = Dimension(JBUI.scale(16), JBUI.scale(16))
                    })
                }
            }
        }

        topRow.add(indicatorsPanel, BorderLayout.EAST)

        // ── Bottom: Archived Date + Action Buttons ──
        val bottomRow = JPanel(BorderLayout())
        bottomRow.isOpaque = false

        // Archived date
        val dateText = item.archivedAt?.let { "Archived: ${formatTimestamp(it)}" } ?: ""
        val dateLabel = JLabel(dateText).apply {
            font = font.deriveFont(11f)
            foreground = DATE_FOREGROUND
        }
        bottomRow.add(dateLabel, BorderLayout.WEST)

        // Action buttons: Restore + Delete Permanently
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actionsPanel.isOpaque = false

        // Restore button
        val restoreBtn = createActionButton(ICON_RESTORE, "Restore")
        restoreBtn.addActionListener {
            when (item) {
                is ArchivedItem.ArchivedSnippet -> restoreSnippet(item.snippet)
                is ArchivedItem.ArchivedNote -> restoreNote(item.note)
            }
        }
        actionsPanel.add(restoreBtn)

        // Delete Permanently button
        val deleteBtn = createActionButton(ICON_DELETE, "Delete Permanently")
        deleteBtn.addActionListener {
            when (item) {
                is ArchivedItem.ArchivedSnippet -> deleteSnippetPermanently(item.snippet)
                is ArchivedItem.ArchivedNote -> deleteNotePermanently(item.note)
            }
        }
        actionsPanel.add(deleteBtn)

        bottomRow.add(actionsPanel, BorderLayout.EAST)

        // ── Assemble card ──
        val centerPanel = JPanel(VerticalLayout(4))
        centerPanel.isOpaque = false
        centerPanel.add(topRow)
        centerPanel.add(bottomRow)

        card.add(centerPanel, BorderLayout.CENTER)

        // Double-click to open in editor
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    when (item) {
                        is ArchivedItem.ArchivedSnippet -> EditorUtils.openSnippetInEditor(project, item.snippet)
                        is ArchivedItem.ArchivedNote -> EditorUtils.openNoteInEditor(project, item.note)
                    }
                }
            }

            override fun mouseEntered(e: MouseEvent) {
                card.background = CARD_HOVER_BACKGROUND
                card.border = CompoundBorder(
                    LineBorder(CARD_HOVER_BORDER_COLOR, 1, true),
                    EmptyBorder(JBUI.insets(8, 12, 8, 12))
                )
            }

            override fun mouseExited(e: MouseEvent) {
                card.background = CARD_BACKGROUND
                card.border = CompoundBorder(
                    LineBorder(CARD_BORDER_COLOR, 1, true),
                    EmptyBorder(JBUI.insets(8, 12, 8, 12))
                )
            }
        })

        return card
    }

    private fun createTypeBadge(type: ContentType): JLabel {
        val (text, bgColor) = when (type) {
            ContentType.SNIPPET -> "Snippet" to "#3B82F6"
            ContentType.NOTE -> "Note" to "#10B981"
        }
        return JLabel(
            "<html><div style='background-color: $bgColor; color: #FFFFFF; " +
            "padding: 2px 6px; border-radius: 3px; font-size: 10px;'>$text</div></html>"
        )
    }

    private fun createActionButton(icon: Icon, toolTip: String): JButton {
        return JButton(icon).apply {
            this.toolTipText = toolTip
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            minimumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            maximumSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = BUTTON_FOREGROUND
        }
    }

    // ───────────────────────── Archive actions ─────────────────────────

    private fun restoreSnippet(snippet: Snippet) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val restored = apiService.unarchiveSnippet(project, snippet.slug ?: snippet.id)

                ApplicationManager.getApplication().invokeLater {
                    if (restored != null) {
                        Messages.showInfoMessage(project, "Snippet restored successfully!", "Restored")
                        SnippetLookupService.getInstance(project).addSnippet(restored)
                        // Remove from local list and rebuild
                        allItems = allItems.filterNot { it.id == snippet.id }
                        applyFilter()
                        notifySnippetRestored(restored)
                    } else {
                        Messages.showErrorDialog(project, "Failed to restore snippet.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to restore snippet", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun restoreNote(note: Note) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val restored = apiService.unarchiveNote(project, note.slug ?: note.id)

                ApplicationManager.getApplication().invokeLater {
                    if (restored != null) {
                        Messages.showInfoMessage(project, "Note restored successfully!", "Restored")
                        NoteLookupService.getInstance(project).addNote(restored)
                        // Remove from local list and rebuild
                        allItems = allItems.filterNot { it.id == note.id }
                        applyFilter()
                        notifyNoteRestored(restored)
                    } else {
                        Messages.showErrorDialog(project, "Failed to restore note.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to restore note", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun deleteSnippetPermanently(snippet: Snippet) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Are you sure you want to permanently delete '${snippet.title}'?\n\nThis action cannot be undone.",
            "Permanent Delete",
            Messages.getYesButton(),
            Messages.getNoButton(),
            Messages.getWarningIcon()
        )

        if (confirmed != Messages.YES) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val success = apiService.deleteSnippet(project, snippet.slug ?: snippet.id)

                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Snippet permanently deleted.", "Deleted")
                        // Remove from local list and rebuild
                        allItems = allItems.filterNot { it.id == snippet.id }
                        applyFilter()
                        notifySnippetDeletedPermanently(snippet.id)
                    } else {
                        Messages.showErrorDialog(project, "Failed to delete snippet.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete snippet permanently", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun deleteNotePermanently(note: Note) {
        val confirmed = Messages.showYesNoDialog(
            project,
            "Are you sure you want to permanently delete '${note.title}'?\n\nThis action cannot be undone.",
            "Permanent Delete",
            Messages.getYesButton(),
            Messages.getNoButton(),
            Messages.getWarningIcon()
        )

        if (confirmed != Messages.YES) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val success = apiService.deleteNote(project, note.slug ?: note.id)

                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Note permanently deleted.", "Deleted")
                        // Remove from local list and rebuild
                        allItems = allItems.filterNot { it.id == note.id }
                        applyFilter()
                        notifyNoteDeletedPermanently(note.id)
                    } else {
                        Messages.showErrorDialog(project, "Failed to delete note.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete note permanently", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun formatTimestamp(timestamp: String): String {
        return try {
            timestamp.substring(0, 10)
        } catch (e: Exception) {
            timestamp
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
