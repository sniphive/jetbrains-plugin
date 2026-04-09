package com.sniphive.idea.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Tag
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Component
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * Panel for searching and filtering snippets in the SnipHive tool window.
 *
 * This panel provides:
 * - Text search field for keyword search
 * - Language filter dropdown
 * - Tags filter with checkboxes
 * - Clear filters button
 * - Search button
 *
 * The panel notifies listeners when search parameters change, allowing the
 * snippet list to be filtered accordingly.
 *
 * Usage Example:
 * ```kotlin
 * val panel = SearchPanel(project)
 * panel.setAvailableLanguages(listOf("kotlin", "java", "python"))
 * panel.setAvailableTags(tagList)
 * panel.addSearchChangeListener { query, language, tags ->
 *     // Perform search with filters
 * }
 * ```
 *
 * Security Note:
 * - No sensitive data (tokens, passwords) is handled in this panel
 * - Search queries are not logged (privacy)
 *
 * @property project The current project
 */
class SearchPanel(project: com.intellij.openapi.project.Project) : JPanel() {

    companion object {
        private val LOG = Logger.getInstance(SearchPanel::class.java)

        /**
         * Value for "All Languages" selection in language filter.
         */
        private const val ALL_LANGUAGES = "All Languages"

        /**
         * Maximum number of tag checkboxes shown before scroll is enabled.
         */
        private const val MAX_VISIBLE_TAGS = 8
    }

    /**
     * Listener interface for search/filter change events.
     */
    interface SearchChangeListener {
        /**
         * Called when search parameters change.
         *
         * @param query The search query text (may be empty)
         * @param language The selected language filter (null for all)
         * @param tags The list of selected tag IDs (empty for all)
         */
        fun onSearchChanged(query: String, language: String?, tags: List<String>)
    }

    private val project: com.intellij.openapi.project.Project = project
    private val searchChangeListeners = mutableListOf<SearchChangeListener>()

    // UI Components
    private val searchField: JBTextField = JBTextField()
    private val languageComboBox: JComboBox<String> = JComboBox()
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val tagsScrollPane: JBScrollPane = JBScrollPane(tagsPanel)
    private val searchButton: JButton = JButton("Search")
    private val clearButton: JButton = JButton("Clear")

    // Data
    private val tagCheckBoxes = mutableMapOf<String, JBCheckBox>()
    private var availableTags: List<Tag> = emptyList()

    init {
        LOG.debug("Initializing SearchPanel for project: ${project.name}")

        // Setup main panel
        layout = BorderLayout()
        border = EmptyBorder(JBUI.insets(10, 10, 10, 10))

        // Create components
        val controlPanel = createControlPanel()
        add(controlPanel, BorderLayout.NORTH)

        // Setup listeners
        setupListeners()

        // Initialize with default values
        resetFilters()

        LOG.debug("SearchPanel initialized successfully")
    }

    /**
     * Create the main control panel with all filter components.
     */
    private fun createControlPanel(): JPanel {
        val panel = JPanel(VerticalLayout(10))
        panel.border = EmptyBorder(JBUI.insets(0, 0, 10, 0))

        // Search section
        val searchSection = createSearchSection()
        panel.add(searchSection)

        // Language filter section
        val languageSection = createLanguageFilterSection()
        panel.add(languageSection)

        // Tags filter section
        val tagsSection = createTagsFilterSection()
        panel.add(tagsSection)

        // Button row
        val buttonRow = createButtonRow()
        panel.add(buttonRow)

        return panel
    }

    /**
     * Create the search input section.
     */
    private fun createSearchSection(): JPanel {
        val panel = JPanel(BorderLayout(5, 0))

        val label = JBLabel("Search:")
        searchField.setColumns(30)
        searchField.setToolTipText("Search by title, content, or tag name")

        panel.add(label, BorderLayout.WEST)
        panel.add(searchField, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create the language filter section.
     */
    private fun createLanguageFilterSection(): JPanel {
        val panel = JPanel(BorderLayout(5, 0))

        val label = JBLabel("Language:")
        languageComboBox.addItem(ALL_LANGUAGES)
        languageComboBox.setToolTipText("Filter snippets by programming language")

        panel.add(label, BorderLayout.WEST)
        panel.add(languageComboBox, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create the tags filter section.
     */
    private fun createTagsFilterSection(): JPanel {
        val panel = JPanel(BorderLayout(5, 0))

        val label = JBLabel("Tags:")
        tagsPanel.isOpaque = false
        tagsScrollPane.setBorder(BorderFactory.createEmptyBorder())
        tagsScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        tagsScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)

        panel.add(label, BorderLayout.WEST)
        panel.add(tagsScrollPane, BorderLayout.CENTER)

        return panel
    }

    /**
     * Create the button row for search and clear actions.
     */
    private fun createButtonRow(): JPanel {
        val panel = JPanel()

        searchButton.setToolTipText("Apply search filters")
        clearButton.setToolTipText("Clear all filters")

        panel.add(searchButton)
        panel.add(Box.createHorizontalStrut(JBUI.scale(5)))
        panel.add(clearButton)

        return panel
    }

    /**
     * Setup event listeners for UI components.
     */
    private fun setupListeners() {
        // Search field - trigger search on Enter key
        searchField.addActionListener {
            notifySearchChanged()
        }

        // Search field - also trigger on document changes with debounce
        searchField.document.addDocumentListener(object : DocumentListener {
            private var timer: Timer? = null

            override fun insertUpdate(e: DocumentEvent) {
                scheduleSearch()
            }

            override fun removeUpdate(e: DocumentEvent) {
                scheduleSearch()
            }

            override fun changedUpdate(e: DocumentEvent) {
                scheduleSearch()
            }

            private fun scheduleSearch() {
                timer?.stop()
                timer = Timer(300) {
                    notifySearchChanged()
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        })

        // Language combo box
        languageComboBox.addActionListener {
            notifySearchChanged()
        }

        // Search button
        searchButton.addActionListener {
            notifySearchChanged()
        }

        // Clear button
        clearButton.addActionListener {
            resetFilters()
            notifySearchChanged()
        }
    }

    /**
     * Set available languages for the language filter.
     *
     * @param languages List of language names to show in dropdown
     */
    fun setAvailableLanguages(languages: List<String>) {
        LOG.debug("Setting ${languages.size} available languages")

        // Save current selection
        val currentSelection = languageComboBox.getSelectedItem() as? String

        // Clear and repopulate combo box
        languageComboBox.removeAllItems()
        languageComboBox.addItem(ALL_LANGUAGES)

        // Sort languages alphabetically
        languages.sorted().forEach { language ->
            languageComboBox.addItem(language)
        }

        // Restore selection if possible
        if (currentSelection != null && languages.contains(currentSelection)) {
            languageComboBox.setSelectedItem(currentSelection)
        } else {
            languageComboBox.setSelectedItem(ALL_LANGUAGES)
        }
    }

    /**
     * Set available tags for the tags filter.
     *
     * @param tags List of tags to show as checkboxes
     */
    fun setAvailableTags(tags: List<Tag>) {
        LOG.debug("Setting ${tags.size} available tags")

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

            // Add listener to tag checkbox
            checkBox.addActionListener {
                notifySearchChanged()
            }
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
     * Get the current search query text.
     *
     * @return The search query (may be empty)
     */
    fun getSearchQuery(): String {
        return searchField.text.trim()
    }

    /**
     * Set the search query text.
     *
     * @param query The search query to set
     */
    fun setSearchQuery(query: String) {
        searchField.text = query
    }

    /**
     * Get the currently selected language filter.
     *
     * @return The selected language, or null if "All Languages" is selected
     */
    fun getSelectedLanguage(): String? {
        val selection = languageComboBox.getSelectedItem() as? String
        return if (selection == ALL_LANGUAGES) null else selection
    }

    /**
     * Set the selected language filter.
     *
     * @param language The language to select, or null for "All Languages"
     */
    fun setSelectedLanguage(language: String?) {
        languageComboBox.setSelectedItem(language ?: ALL_LANGUAGES)
    }

    /**
     * Get the list of selected tag IDs.
     *
     * @return List of selected tag IDs (empty if none selected)
     */
    fun getSelectedTagIds(): List<String> {
        return tagCheckBoxes.filter { it.value.isSelected }.keys.toList()
    }

    /**
     * Get the list of selected tags.
     *
     * @return List of selected Tag objects
     */
    fun getSelectedTags(): List<Tag> {
        val selectedIds = getSelectedTagIds()
        return availableTags.filter { it.id in selectedIds }
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
     * Clear all selected tags.
     */
    fun clearSelectedTags() {
        tagCheckBoxes.values.forEach { it.isSelected = false }
    }

    /**
     * Reset all filters to default values.
     */
    fun resetFilters() {
        LOG.debug("Resetting all filters")

        searchField.setText("")
        languageComboBox.setSelectedItem(ALL_LANGUAGES)
        tagCheckBoxes.values.forEach { it.isSelected = false }
    }

    /**
     * Add a search change listener.
     *
     * @param listener The listener to add
     */
    fun addSearchChangeListener(listener: SearchChangeListener) {
        searchChangeListeners.add(listener)
    }

    /**
     * Remove a search change listener.
     *
     * @param listener The listener to remove
     */
    fun removeSearchChangeListener(listener: SearchChangeListener) {
        searchChangeListeners.remove(listener)
    }

    /**
     * Notify all listeners of search parameter changes.
     */
    private fun notifySearchChanged() {
        val query = getSearchQuery()
        val language = getSelectedLanguage()
        val tags = getSelectedTagIds()

        LOG.debug("Search changed: query='$query', language=$language, tags=$tags")

        searchChangeListeners.forEach { listener ->
            try {
                listener.onSearchChanged(query, language, tags)
            } catch (e: Exception) {
                LOG.error("Error in search change listener", e)
            }
        }
    }

    /**
     * Get the number of selected filters.
     *
     * @return Count of active filters (language + tags)
     */
    fun getActiveFilterCount(): Int {
        var count = 0
        if (getSelectedLanguage() != null) count++
        count += getSelectedTagIds().size
        return count
    }

    /**
     * Check if any filters are active.
     *
     * @return true if any filter is selected
     */
    fun hasActiveFilters(): Boolean {
        return getActiveFilterCount() > 0 || getSearchQuery().isNotEmpty()
    }

    /**
     * Enable or disable the search panel.
     *
     * @param enabled true to enable, false to disable
     */
    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        searchField.isEnabled = enabled
        languageComboBox.isEnabled = enabled
        tagCheckBoxes.values.forEach { it.isEnabled = enabled }
        searchButton.isEnabled = enabled
        clearButton.isEnabled = enabled
    }

    /**
     * Focus the search field.
     */
    fun focusSearchField() {
        searchField.requestFocusInWindow()
    }
}
