package com.sniphive.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.sniphive.idea.models.Snippet
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Panel for displaying a list of snippets as interactive cards.
 *
 * Each card shows:
 * - Snippet title (bold)
 * - Last updated date
 * - Action buttons: Delete, Copy Public URL (if public), Pin, Favorite
 * - Public/Private indicator icon
 *
 * Double-click on a card opens the snippet in the editor.
 *
 * @property project The current project
 */
class SnippetListPanel(private val project: com.intellij.openapi.project.Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(SnippetListPanel::class.java)
        private const val STATE_LOADING = "loading"
        private const val STATE_EMPTY = "empty"
        private const val STATE_ERROR = "error"
        private const val STATE_POPULATED = "populated"

        // Theme-aware colors for cards
        private val CARD_BACKGROUND = JBColor.namedColor("Panel.background", JBColor(0xfafafc, 0x2b2b2b))
        private val CARD_BORDER_COLOR = JBColor.namedColor("Component.borderColor", JBColor(0xdcdcdc, 0x646464))
        private val CARD_HOVER_BACKGROUND = JBColor(0xf0f5ff, 0x282d35)
        private val CARD_HOVER_BORDER_COLOR = JBColor.namedColor("Focus.borderColor", JBColor(0x6496ff, 0x6496ff))
        private val TITLE_FOREGROUND = JBColor.namedColor("Label.foreground", JBColor(0x1e1e1e, 0xffffff))
        private val DATE_FOREGROUND = JBColor.namedColor("Label.disabledForeground", JBColor(0x888888, 0x888888))
        private val BUTTON_FOREGROUND = JBColor.namedColor("Button.foreground", JBColor(0x505050, 0xffffff))

        // AllIcons to avoid IntelliJ VFS SVG language detection bug (Read access error on DefaultDispatcher)
        private val ICON_PIN = AllIcons.General.Pin_tab
        private val ICON_PIN_SELECTED = AllIcons.Actions.PinTab
        private val ICON_STAR = AllIcons.Nodes.Favorite
        private val ICON_STAR_EMPTY = AllIcons.Nodes.NotFavoriteOnHover
        private val ICON_LOCKED = AllIcons.Nodes.Locked
        private val ICON_GLOBE = AllIcons.General.Web
        private val ICON_EXTERNAL_LINK = AllIcons.Actions.Copy
        private val ICON_ARCHIVE = AllIcons.FileTypes.Archive
    }

    /**
     * Callback interface for card actions.
     */
    interface ActionHandler {
        fun onCopyContent(snippet: Snippet)
        fun onEditSnippet(snippet: Snippet)
        fun onDeleteSnippet(snippet: Snippet)
        fun onCopyPublicUrl(snippet: Snippet)
        fun onTogglePin(snippet: Snippet)
        fun onToggleFavorite(snippet: Snippet)
        fun onArchiveSnippet(snippet: Snippet)
    }

    private var actionHandler: ActionHandler? = null

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

    private var currentState: String = STATE_LOADING
    private var snippets: List<Snippet> = emptyList()

    init {
        layout = BorderLayout()

        cardPanel.layout = cardLayout
        cardPanel.add(loadingPanel, STATE_LOADING)
        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(errorPanel, STATE_ERROR)
        cardPanel.add(contentPanel, STATE_POPULATED)

        add(cardPanel, BorderLayout.CENTER)

        setupContentPanel()

        showLoadingState()

    }

    /** Set the action handler for card button clicks. */
    fun setActionHandler(handler: ActionHandler) {
        actionHandler = handler
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

        val label = JBLabel("Loading snippets...")
        label.font = label.font.deriveFont(14f)

        panel.add(spinner, VerticalLayout.CENTER)
        panel.add(label, VerticalLayout.CENTER)

        return panel
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.InformationDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("No Snippets Found")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Create your first snippet to get started."), VerticalLayout.CENTER)

        return panel
    }

    private fun createErrorPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.ErrorDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Error Loading Snippets")
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
        currentState = STATE_ERROR

        if (message != null) {
            LOG.warn("Showing error state: $message")
            updateErrorMessage(message)
        }

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
            if (snippets.isEmpty()) showEmptyState() else showPopulatedState()
        }
    }

    // ───────────────────────── List manipulation ─────────────────────────

fun setSnippets(snippets: List<Snippet>) {
        this.snippets = snippets

        rebuildCards()

        if (snippets.isEmpty()) {
            showEmptyState()
        } else {
            showPopulatedState()
        }

    }

    fun addSnippet(snippet: Snippet) {
        snippets = snippets + snippet
        rebuildCards()
        if (currentState != STATE_POPULATED) showPopulatedState()
    }

    fun updateSnippet(snippet: Snippet) {
        snippets = snippets.map { if (it.id == snippet.id) snippet else it }
        rebuildCards()
    }

    fun removeSnippet(snippetId: String) {
        snippets = snippets.filter { it.id != snippetId }
        rebuildCards()
        if (snippets.isEmpty()) showEmptyState()
    }

    fun clearSnippets() {
        snippets = emptyList()
        rebuildCards()
        showEmptyState()
    }

    fun getSnippetCount(): Int = snippets.size

    fun isLoading(): Boolean = currentState == STATE_LOADING

    // ───────────────────────── Card rendering ─────────────────────────

    private fun rebuildCards() {
        cardsContainer.removeAll()

        snippets.forEach { snippet ->
            cardsContainer.add(createSnippetCard(snippet))
            cardsContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun createSnippetCard(snippet: Snippet): JPanel {
        val card = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(4)))
        card.border = CompoundBorder(
            LineBorder(CARD_BORDER_COLOR, 1, true),
            EmptyBorder(JBUI.insets(8, 12, 8, 12))
        )
        card.background = CARD_BACKGROUND
        card.isOpaque = true
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // ── Top: Title + Indicator Icons ──
        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false

        // Title
        val titleLabel = JLabel(escapeHtml(snippet.title)).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            foreground = TITLE_FOREGROUND
        }
        topRow.add(titleLabel, BorderLayout.CENTER)

        // Right side: indicator icons
        val indicatorsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        indicatorsPanel.isOpaque = false

        // Public/Private indicator
        if (snippet.isPublic) {
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

        topRow.add(indicatorsPanel, BorderLayout.EAST)

        // ── Bottom: Date + Action Buttons ──
        val bottomRow = JPanel(BorderLayout())
        bottomRow.isOpaque = false

        // Date
        val dateText = snippet.updatedAt?.let { formatTimestamp(it) } ?: ""
        val dateLabel = JLabel(dateText).apply {
            font = font.deriveFont(11f)
            foreground = DATE_FOREGROUND
        }
        bottomRow.add(dateLabel, BorderLayout.WEST)

        // Action buttons
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actionsPanel.isOpaque = false

        // Delete button
        val deleteBtn = createActionButton(AllIcons.Actions.GC, "Delete Snippet")
        deleteBtn.addActionListener {
            actionHandler?.onDeleteSnippet(snippet)
        }
        actionsPanel.add(deleteBtn)

        // Copy Public URL button (only if public)
        if (snippet.isPublic && snippet.publicUrl != null) {
            val copyUrlBtn = createActionButton(ICON_EXTERNAL_LINK, "Copy Public URL")
            copyUrlBtn.addActionListener {
                actionHandler?.onCopyPublicUrl(snippet)
            }
            actionsPanel.add(copyUrlBtn)
        }

        // Pin button
        val pinBtn = createActionButton(
            if (snippet.isPinned) ICON_PIN_SELECTED else ICON_PIN,
            if (snippet.isPinned) "Unpin" else "Pin"
        )
        pinBtn.addActionListener {
            actionHandler?.onTogglePin(snippet)
        }
        actionsPanel.add(pinBtn)

        // Favorite button
        val favBtn = createActionButton(
            if (snippet.isFavorite) ICON_STAR else ICON_STAR_EMPTY,
            if (snippet.isFavorite) "Unfavorite" else "Favorite"
        )
        favBtn.addActionListener {
            actionHandler?.onToggleFavorite(snippet)
        }
        actionsPanel.add(favBtn)

        val archiveBtn = createActionButton(ICON_ARCHIVE, "Archive")
        archiveBtn.addActionListener {
            actionHandler?.onArchiveSnippet(snippet)
        }
        actionsPanel.add(archiveBtn)

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
                    com.sniphive.idea.editor.EditorUtils.openSnippetInEditor(project, snippet)
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
