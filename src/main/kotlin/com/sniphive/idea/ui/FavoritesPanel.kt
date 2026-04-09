package com.sniphive.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.sniphive.idea.editor.EditorUtils
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
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
 * Unified item for displaying both snippets and notes in favorites/pinned views.
 */
sealed class ContentItem {
    abstract val id: String
    abstract val title: String
    abstract val preview: String
    abstract val updatedAt: String?
    abstract val isEncrypted: Boolean
    abstract val type: ContentType

    data class SnippetItem(val snippet: Snippet) : ContentItem() {
        override val id: String get() = snippet.id
        override val title: String get() = snippet.title
        override val preview: String get() = 
            if (snippet.isEncrypted()) "[Encrypted - Click to view]"
            else snippet.content.lines().take(2).joinToString(" ")
        override val updatedAt: String? get() = snippet.updatedAt
        override val isEncrypted: Boolean get() = snippet.isEncrypted()
        override val type: ContentType get() = ContentType.SNIPPET
    }

    data class NoteItem(val note: Note) : ContentItem() {
        override val id: String get() = note.id
        override val title: String get() = note.title
        override val preview: String get() = 
            if (note.isEncrypted()) "[Encrypted - Click to view]"
            else note.content.lines().take(2).joinToString(" ")
        override val updatedAt: String? get() = note.updatedAt
        override val isEncrypted: Boolean get() = note.isEncrypted()
        override val type: ContentType get() = ContentType.NOTE
    }
}

enum class ContentType {
    SNIPPET, NOTE
}

/**
 * Listener for content item selection.
 */
interface ContentItemSelectionListener {
    fun onItemSelectionChanged(item: ContentItem?)
    fun onSnippetSelected(snippet: Snippet)
    fun onNoteSelected(note: Note)
}

/**
 * Action handler for card-level actions (pin, favorite) in FavoritesPanel.
 */
interface FavoritesActionHandler {
    fun onTogglePin(item: ContentItem)
    fun onToggleFavorite(item: ContentItem)
    fun onArchive(item: ContentItem)
}

/**
 * Panel for displaying favorite items (snippets and notes combined) as interactive cards.
 *
 * Features:
 * - Filter tabs: All / Snippets / Notes
 * - Card-based layout matching SnippetListPanel/NoteListPanel styling
 * - Selection support with ContentItemSelectionListener
 * - Loading, empty, and error states
 * - Double-click to open in editor
 */
class FavoritesPanel(private val project: Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(FavoritesPanel::class.java)
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
        private val ICON_PIN = AllIcons.General.Pin_tab
        private val ICON_STAR = AllIcons.Nodes.Favorite
        private val ICON_LOCKED = AllIcons.Nodes.Locked
        private val ICON_GLOBE = AllIcons.General.Web
        private val ICON_ARCHIVE = AllIcons.Actions.Close
    }

    private val selectionListeners = mutableListOf<ContentItemSelectionListener>()
    private var actionHandler: FavoritesActionHandler? = null

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
    private var allItems: List<ContentItem> = emptyList()
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

        panel.add(JBLabel("Filter: "))
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

        val label = JBLabel("Loading favorites...")
        label.font = label.font.deriveFont(14f)

        panel.add(spinner, VerticalLayout.CENTER)
        panel.add(label, VerticalLayout.CENTER)
        return panel
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.InformationDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("No Favorites")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Add snippets or notes to favorites to see them here."), VerticalLayout.CENTER)
        return panel
    }

    private fun createErrorPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.ErrorDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Error Loading Favorites")
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
        allItems = snippets.map { ContentItem.SnippetItem(it) } +
                   notes.map { ContentItem.NoteItem(it) }

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

    // ───────────────────────── Selection ─────────────────────────

    fun addSelectionListener(listener: ContentItemSelectionListener) {
        selectionListeners.add(listener)
    }

    fun removeSelectionListener(listener: ContentItemSelectionListener) {
        selectionListeners.remove(listener)
    }

    /**
     * Set the action handler for card-level actions (pin, favorite).
     */
    fun setActionHandler(handler: FavoritesActionHandler?) {
        actionHandler = handler
    }

    private fun notifySelectionChanged(item: ContentItem?) {
        selectionListeners.forEach { listener ->
            try {
                listener.onItemSelectionChanged(item)
                when (item) {
                    is ContentItem.SnippetItem -> listener.onSnippetSelected(item.snippet)
                    is ContentItem.NoteItem -> listener.onNoteSelected(item.note)
                    null -> {}
                }
            } catch (e: Exception) {
                LOG.error("Error in selection listener", e)
            }
        }
    }

    // ───────────────────────── Card rendering ─────────────────────────

    private fun rebuildCards(items: List<ContentItem>) {
        cardsContainer.removeAll()

        items.forEach { item ->
            cardsContainer.add(createContentCard(item))
            cardsContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun createContentCard(item: ContentItem): JPanel {
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

        // Public/Private indicator
        when (item) {
            is ContentItem.SnippetItem -> {
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
            is ContentItem.NoteItem -> {
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

        // ── Bottom: Date + Action Buttons ──
        val bottomRow = JPanel(BorderLayout())
        bottomRow.isOpaque = false

        // Date
        val dateText = item.updatedAt?.let { formatTimestamp(it) } ?: ""
        val dateLabel = JLabel(dateText).apply {
            font = font.deriveFont(11f)
            foreground = DATE_FOREGROUND
        }
        bottomRow.add(dateLabel, BorderLayout.WEST)

        // Action buttons
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actionsPanel.isOpaque = false

        // Pin button
        val pinBtn = createActionButton(
            ICON_PIN,
            "Unpin"
        )
        pinBtn.addActionListener {
            actionHandler?.onTogglePin(item)
        }
        actionsPanel.add(pinBtn)

        // Favorite button (already favorited, so show filled star)
        val favBtn = createActionButton(ICON_STAR, "Unfavorite")
        favBtn.addActionListener {
            actionHandler?.onToggleFavorite(item)
        }
        actionsPanel.add(favBtn)

        val archiveBtn = createActionButton(ICON_ARCHIVE, "Archive")
        archiveBtn.addActionListener {
            actionHandler?.onArchive(item)
        }
        actionsPanel.add(archiveBtn)

        bottomRow.add(actionsPanel, BorderLayout.EAST)

        // ── Assemble card ──
        val centerPanel = JPanel(VerticalLayout(4))
        centerPanel.isOpaque = false
        centerPanel.add(topRow)

        // Preview text (first 80 chars of content)
        val previewLabel = JLabel(escapeHtml(item.preview.take(80))).apply {
            font = font.deriveFont(11f)
            foreground = DATE_FOREGROUND
            toolTipText = escapeHtml(item.preview)
        }
        centerPanel.add(previewLabel)

        centerPanel.add(bottomRow)

        card.add(centerPanel, BorderLayout.CENTER)

        // Single MouseAdapter handling click, double-click, hover
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                when {
                    e.clickCount == 2 -> {
                        when (item) {
                            is ContentItem.SnippetItem -> EditorUtils.openSnippetInEditor(project, item.snippet)
                            is ContentItem.NoteItem -> EditorUtils.openNoteInEditor(project, item.note)
                        }
                    }
                    e.clickCount == 1 -> notifySelectionChanged(item)
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
