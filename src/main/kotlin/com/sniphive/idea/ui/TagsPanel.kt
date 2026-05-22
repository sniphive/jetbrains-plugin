package com.sniphive.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.ScrollPaneConstants
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder

/**
 * Panel for listing and managing workspace tags directly in the tool window.
 */
class TagsPanel(private val project: Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(TagsPanel::class.java)
        private const val STATE_LOADING = "loading"
        private const val STATE_EMPTY = "empty"
        private const val STATE_ERROR = "error"
        private const val STATE_POPULATED = "populated"

        private val CARD_BACKGROUND = JBColor.namedColor("Panel.background", JBColor(0xfafafc, 0x2b2b2b))
        private val CARD_BORDER_COLOR = JBColor.namedColor("Component.borderColor", JBColor(0xdcdcdc, 0x646464))
        private val CARD_HOVER_BACKGROUND = JBColor(0xf0f5ff, 0x282d35)
        private val CARD_HOVER_BORDER_COLOR = JBColor.namedColor("Focus.borderColor", JBColor(0x6496ff, 0x6496ff))
        private val TITLE_FOREGROUND = JBColor.namedColor("Label.foreground", JBColor(0x1e1e1e, 0xffffff))
        private val SECONDARY_FOREGROUND = JBColor.namedColor("Label.disabledForeground", JBColor(0x888888, 0x888888))
        private val BUTTON_FOREGROUND = JBColor.namedColor("Button.foreground", JBColor(0x505050, 0xffffff))
    }

    private val cardPanel = JPanel(java.awt.CardLayout())
    private val cardLayout: java.awt.CardLayout
        get() = cardPanel.layout as java.awt.CardLayout

    private val cardsContainer = JPanel()
    private val contentPanel = JPanel(BorderLayout())
    private val errorMessageLabel = JBLabel("Please check your connection and try again.")
    private val loadingPanel = createLoadingPanel()
    private val emptyPanel = createEmptyPanel()
    private val errorPanel = createErrorPanel()

    private var tags: List<Tag> = emptyList()
    private val pendingDeletes = mutableSetOf<String>()

    init {
        layout = BorderLayout(0, JBUI.scale(5))

        cardPanel.add(loadingPanel, STATE_LOADING)
        cardPanel.add(emptyPanel, STATE_EMPTY)
        cardPanel.add(errorPanel, STATE_ERROR)
        cardPanel.add(contentPanel, STATE_POPULATED)

        setupContentPanel()

        add(cardPanel, BorderLayout.CENTER)
        showLoadingState()
        loadTags()
    }

    fun refresh() {
        loadTags()
    }

    private fun setupContentPanel() {
        cardsContainer.layout = VerticalLayout(0)
        cardsContainer.isOpaque = false

        val scrollPane = JBScrollPane(cardsContainer)
        scrollPane.border = BorderFactory.createEmptyBorder()
        scrollPane.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER

        contentPanel.add(scrollPane, BorderLayout.CENTER)
    }

    private fun loadTags() {
        showLoadingState()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val loadedTags = SnipHiveApiService.getInstance().getTags(project).sortedBy { it.name.lowercase() }
                ApplicationManager.getApplication().invokeLater {
                    setTags(loadedTags)
                }
            } catch (e: Exception) {
                LOG.error("Failed to load tags", e)
                ApplicationManager.getApplication().invokeLater {
                    showErrorState("Failed to load tags: ${e.message ?: "Unknown error"}")
                }
            }
        }
    }

    private fun setTags(tags: List<Tag>) {
        this.tags = tags
        rebuildCards()

        if (tags.isEmpty()) {
            showEmptyState()
        } else {
            showPopulatedState()
        }
    }

    private fun rebuildCards() {
        cardsContainer.removeAll()

        tags.forEach { tag ->
            cardsContainer.add(createTagCard(tag))
            cardsContainer.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        cardsContainer.revalidate()
        cardsContainer.repaint()
    }

    private fun createTagCard(tag: Tag): JPanel {
        val card = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(4)))
        card.border = createCardBorder(CARD_BORDER_COLOR)
        card.background = CARD_BACKGROUND
        card.isOpaque = true
        card.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val topRow = JPanel(BorderLayout())
        topRow.isOpaque = false

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(8), 0))
        titleRow.isOpaque = false
        titleRow.add(createColorSwatch(tag.getColorOrDefault()))

        val titleLabel = JLabel(escapeHtml(tag.name)).apply {
            font = font.deriveFont(java.awt.Font.BOLD, 13f)
            foreground = TITLE_FOREGROUND
            toolTipText = tag.name
        }
        titleRow.add(titleLabel)

        tag.slug?.takeIf { it.isNotBlank() }?.let { slug ->
            titleRow.add(JBLabel("/$slug").apply {
                font = font.deriveFont(11f)
                foreground = SECONDARY_FOREGROUND
                toolTipText = slug
            })
        }

        topRow.add(titleRow, BorderLayout.CENTER)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0))
        actionsPanel.isOpaque = false

        val editButton = createActionButton(AllIcons.Actions.Edit, "Edit Tag")
        editButton.addActionListener { editTag(tag) }
        actionsPanel.add(editButton)

        val deleteButton = createActionButton(AllIcons.Actions.GC, "Delete Tag")
        deleteButton.addActionListener { deleteTag(tag) }
        actionsPanel.add(deleteButton)

        topRow.add(actionsPanel, BorderLayout.EAST)

        val bottomRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), 0))
        bottomRow.isOpaque = false
        bottomRow.add(createCountLabel("Snippets", tag.snippetsCount ?: 0))
        bottomRow.add(createCountLabel("Notes", tag.notesCount ?: 0))
        bottomRow.add(createCountLabel("Total", tag.getTotalCount()))

        val centerPanel = JPanel(VerticalLayout(4))
        centerPanel.isOpaque = false
        centerPanel.add(topRow)
        centerPanel.add(bottomRow)

        card.add(centerPanel, BorderLayout.CENTER)
        card.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                card.background = CARD_HOVER_BACKGROUND
                card.border = createCardBorder(CARD_HOVER_BORDER_COLOR)
            }

            override fun mouseExited(e: MouseEvent) {
                card.background = CARD_BACKGROUND
                card.border = createCardBorder(CARD_BORDER_COLOR)
            }
        })

        return card
    }

    private fun createColorSwatch(color: String): JPanel {
        return JPanel().apply {
            preferredSize = Dimension(JBUI.scale(14), JBUI.scale(14))
            minimumSize = preferredSize
            maximumSize = preferredSize
            background = parseColor(color)
            border = LineBorder(CARD_BORDER_COLOR, 1, true)
            toolTipText = color
            isOpaque = true
        }
    }

    private fun createCountLabel(label: String, count: Int): JBLabel {
        return JBLabel("$label: $count").apply {
            font = font.deriveFont(11f)
            foreground = SECONDARY_FOREGROUND
        }
    }

    private fun createActionButton(icon: javax.swing.Icon, toolTip: String): JButton {
        return JButton(icon).apply {
            toolTipText = toolTip
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            minimumSize = preferredSize
            maximumSize = preferredSize
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            foreground = BUTTON_FOREGROUND
        }
    }

    private fun createCardBorder(color: Color): CompoundBorder {
        return CompoundBorder(
            LineBorder(color, 1, true),
            EmptyBorder(JBUI.insets(8, 12, 8, 12))
        )
    }

    private fun createLoadingPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        val spinner = JProgressBar()
        spinner.isIndeterminate = true
        spinner.putClientProperty("JProgressBar.style", "large")

        val label = JBLabel("Loading tags...")
        label.font = label.font.deriveFont(14f)

        panel.add(spinner, VerticalLayout.CENTER)
        panel.add(label, VerticalLayout.CENTER)

        return panel
    }

    private fun createEmptyPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.InformationDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("No Tags Found")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)

        val createButton = JButton("Create Tag")
        createButton.addActionListener { createTag() }
        panel.add(createButton, VerticalLayout.CENTER)

        return panel
    }

    private fun createErrorPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(20))

        panel.add(JBLabel(AllIcons.General.ErrorDialog), VerticalLayout.CENTER)
        val titleLabel = JBLabel("Error Loading Tags")
        titleLabel.font = titleLabel.font.deriveFont(14f)
        panel.add(titleLabel, VerticalLayout.CENTER)
        panel.add(errorMessageLabel, VerticalLayout.CENTER)

        val retryButton = JButton("Retry")
        retryButton.addActionListener { loadTags() }
        panel.add(retryButton, VerticalLayout.CENTER)

        return panel
    }

    private fun showLoadingState() {
        cardLayout.show(cardPanel, STATE_LOADING)
        cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
    }

    private fun showEmptyState() {
        cardLayout.show(cardPanel, STATE_EMPTY)
        cursor = Cursor.getDefaultCursor()
    }

    private fun showErrorState(message: String) {
        errorMessageLabel.text = message
        cardLayout.show(cardPanel, STATE_ERROR)
        cursor = Cursor.getDefaultCursor()
    }

    private fun showPopulatedState() {
        cardLayout.show(cardPanel, STATE_POPULATED)
        cursor = Cursor.getDefaultCursor()
    }

    fun createTag() {
        val dialog = CreateTagDialog(project)
        if (dialog.showAndGet()) {
            val name = dialog.getTagName()
            val color = dialog.getSelectedColor()

            LOG.info("Creating tag: $name")

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val createdTag = SnipHiveApiService.getInstance().createTag(project, name, color)
                    ApplicationManager.getApplication().invokeLater {
                        if (createdTag == null) {
                            Messages.showErrorDialog(project, "Failed to create tag. Please try again.", "Error")
                        }
                        refresh()
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to create tag", e)
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Failed to create tag: ${e.message}", "Error")
                        refresh()
                    }
                }
            }
        }
    }

    private fun editTag(tag: Tag) {
        val dialog = EditTagDialog(project, tag)
        if (dialog.showAndGet()) {
            refresh()
        }
    }

    private fun deleteTag(tag: Tag) {
        if (!pendingDeletes.add(tag.id)) {
            return
        }

        val confirmed = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete tag '${tag.name}'?\n\n" +
                "It is used in ${tag.snippetsCount ?: 0} snippet(s) and ${tag.notesCount ?: 0} note(s).\n" +
                "This will remove the tag from all items.",
            "Delete Tag",
            Messages.getYesButton(),
            Messages.getNoButton(),
            Messages.getWarningIcon()
        )

        if (confirmed != Messages.YES) {
            pendingDeletes.remove(tag.id)
            return
        }

        LOG.info("Deleting tag: ${tag.id}")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val success = SnipHiveApiService.getInstance().deleteTag(project, tag.id)
                ApplicationManager.getApplication().invokeLater {
                    pendingDeletes.remove(tag.id)
                    if (success) {
                        refresh()
                    } else {
                        Messages.showErrorDialog(project, "Failed to delete tag. Please try again.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete tag", e)
                ApplicationManager.getApplication().invokeLater {
                    pendingDeletes.remove(tag.id)
                    Messages.showErrorDialog(project, "Failed to delete tag: ${e.message}", "Error")
                }
            }
        }
    }

    private fun parseColor(color: String): Color {
        return try {
            Color.decode(color)
        } catch (e: Exception) {
            LOG.warn("Invalid tag color: $color")
            JBColor.GRAY
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
