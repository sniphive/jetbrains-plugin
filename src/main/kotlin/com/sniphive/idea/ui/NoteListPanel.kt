package com.sniphive.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.ui.JBColor
import com.sniphive.idea.models.Note
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Component
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
 * Panel for displaying a list of notes as interactive cards.
 *
 * Each card shows:
 * - Note title (bold)
 * - Last updated date
 * - Action buttons: Delete, Copy Public URL (if public), Pin, Favorite
 * - Public/Private indicator icon
 *
 * Double-click on a card opens the note in the editor.
 *
 * @property project The current project
 */
class NoteListPanel(private val project: com.intellij.openapi.project.Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(NoteListPanel::class.java)
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
        fun onCopyContent(note: Note)
        fun onEditNote(note: Note)
        fun onDeleteNote(note: Note)
        fun onCopyPublicUrl(note: Note)
        fun onTogglePin(note: Note)
        fun onToggleFavorite(note: Note)
        fun onArchiveNote(note: Note)
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
    private var notes: List<Note> = emptyList()

    init {
        LOG.debug("Initializing NoteListPanel for project: ${project.name}")

        layout = BorderLayout()

        cardPanel.layout = cardLayout
        cardPanel.add(loadingPanel, STATE_LOADING)
        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(errorPanel, STATE_ERROR)
        cardPanel.add(contentPanel, STATE_POPULATED)

        add(cardPanel, BorderLayout.CENTER)

        setupContentPanel()

        showLoadingState()

        LOG.debug("NoteListPanel initialized successfully")
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

        val label = JBLabel("Loading notes...")
        label.font = label.font.deriveFont(14f)

        panel.add(spinner, VerticalLayout.CENTER)
        panel.add(label, VerticalLayout.CENTER)

        return panel
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.InformationDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("No Notes Found")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Create your first note to get started."), VerticalLayout.CENTER)

        return panel
    }

    private fun createErrorPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.ErrorDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Error Loading Notes")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(JBLabel("Please check your connection and try again."), VerticalLayout.CENTER)

        return panel
    }

    // ───────────────────────── State management ─────────────────────────

    fun showLoadingState() {
        LOG.debug("Showing loading state")
        currentState = STATE_LOADING
        cardLayout.show(cardPanel, STATE_LOADING)
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
    }

    fun showEmptyState() {
        LOG.debug("Showing empty state")
        currentState = STATE_EMPTY
        cardLayout.show(cardPanel, STATE_EMPTY)
        cursor = Cursor.getDefaultCursor()
    }

    fun showErrorState(message: String? = null) {
        LOG.warn("Showing error state: ${message ?: "Unknown error"}")
        currentState = STATE_ERROR

        if (message != null) {
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
        LOG.debug("Showing populated state with ${notes.size} notes")
        currentState = STATE_POPULATED
        cardLayout.show(cardPanel, STATE_POPULATED)
        cursor = Cursor.getDefaultCursor()
    }

    fun setLoading(loading: Boolean) {
        if (loading) {
            showLoadingState()
        } else {
            if (notes.isEmpty()) showEmptyState() else showPopulatedState()
        }
    }

    // ───────────────────────── List manipulation ─────────────────────────

    fun setNotes(notes: List<Note>) {
        LOG.debug("Setting ${notes.size} notes in list")
        this.notes = notes
        rebuildCards()

        if (notes.isEmpty()) showEmptyState() else showPopulatedState()

        LOG.debug("Notes updated successfully")
    }

    fun addNote(note: Note) {
        LOG.debug("Adding note: ${note.id}")
        notes = notes + note
        rebuildCards()
        if (currentState != STATE_POPULATED) showPopulatedState()
    }

    fun updateNote(note: Note) {
        LOG.debug("Updating note: ${note.id}")
        notes = notes.map { if (it.id == note.id) note else it }
        rebuildCards()
    }

    fun removeNote(noteId: String) {
        LOG.debug("Removing note: $noteId")
        notes = notes.filter { it.id != noteId }
        rebuildCards()
        if (notes.isEmpty()) showEmptyState()
    }

    fun clearNotes() {
        LOG.debug("Clearing all notes")
        notes = emptyList()
        rebuildCards()
        showEmptyState()
    }

    fun getNoteCount(): Int = notes.size

    fun isLoading(): Boolean = currentState == STATE_LOADING

    // ───────────────────────── Card rendering ─────────────────────────

    private fun rebuildCards() {
        cardsContainer.removeAll()

        notes.forEach { note ->
            cardsContainer.add(createNoteCard(note))
            cardsContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun createNoteCard(note: Note): JPanel {
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
        val titleLabel = JLabel(escapeHtml(note.title)).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            foreground = TITLE_FOREGROUND
        }
        topRow.add(titleLabel, BorderLayout.CENTER)

        // Right side: indicator icons
        val indicatorsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        indicatorsPanel.isOpaque = false

        // Public/Private indicator
        if (note.isPublic) {
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

        topRow.add(indicatorsPanel, BorderLayout.EAST)

        // ── Bottom: Date + Action Buttons ──
        val bottomRow = JPanel(BorderLayout())
        bottomRow.isOpaque = false

        // Date
        val dateText = note.updatedAt?.let { formatTimestamp(it) } ?: ""
        val dateLabel = JLabel(dateText).apply {
            font = font.deriveFont(11f)
            foreground = DATE_FOREGROUND
        }
        bottomRow.add(dateLabel, BorderLayout.WEST)

        // Action buttons
        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actionsPanel.isOpaque = false

        // Delete button
        val deleteBtn = createActionButton(AllIcons.Actions.GC, "Delete Note")
        deleteBtn.addActionListener {
            actionHandler?.onDeleteNote(note)
        }
        actionsPanel.add(deleteBtn)

        // Copy Public URL button (only if public)
        if (note.isPublic && note.publicUrl != null) {
            val copyUrlBtn = createActionButton(ICON_EXTERNAL_LINK, "Copy Public URL")
            copyUrlBtn.addActionListener {
                actionHandler?.onCopyPublicUrl(note)
            }
            actionsPanel.add(copyUrlBtn)
        }

        // Pin button
        val pinBtn = createActionButton(
            if (note.isPinned) ICON_PIN_SELECTED else ICON_PIN,
            if (note.isPinned) "Unpin" else "Pin"
        )
        pinBtn.addActionListener {
            actionHandler?.onTogglePin(note)
        }
        actionsPanel.add(pinBtn)

        // Favorite button
        val favBtn = createActionButton(
            if (note.isFavorite) ICON_STAR else ICON_STAR_EMPTY,
            if (note.isFavorite) "Unfavorite" else "Favorite"
        )
        favBtn.addActionListener {
            actionHandler?.onToggleFavorite(note)
        }
        actionsPanel.add(favBtn)

        val archiveBtn = createActionButton(ICON_ARCHIVE, "Archive")
        archiveBtn.addActionListener {
            actionHandler?.onArchiveNote(note)
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
                    com.sniphive.idea.editor.EditorUtils.openNoteInEditor(project, note)
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
