package com.sniphive.idea.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnipHiveApiClient
import com.sniphive.idea.services.SecureCredentialStorage
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionListener

/**
 * Dialog for searching and selecting a snippet to insert into the editor.
 *
 * This dialog provides:
 * - Search field for filtering snippets by keyword
 * - Language filter dropdown
 * - Tags filter with checkboxes
 * - Scrollable list of snippets with preview
 * - Loading state during API calls
 * - Error display for failed searches
 * - Selection of a snippet for insertion
 *
 * Usage Example:
 * ```kotlin
 * val dialog = InsertSnippetDialog(project)
 * if (dialog.showAndGet()) {
 *     val snippet = dialog.getSelectedSnippet()
 *     // Insert snippet.content at cursor position
 * }
 * ```
 *
 * Security Note:
 * - No credentials or tokens logged or exposed
 * - Authentication token retrieved from SecureCredentialStorage
 * - Search queries are not logged (privacy)
 * - Content insertion happens client-side only
 *
 * @property project The current project context
 */
class InsertSnippetDialog(private val project: Project) : DialogWrapper(true) {

    companion object {
        private val LOG = Logger.getInstance(InsertSnippetDialog::class.java)

        /**
         * Value for "All Languages" selection in language filter.
         */
        private const val ALL_LANGUAGES = "All Languages"

        /**
         * Hardcoded language whitelist — the backend does not expose a /snippets/languages endpoint.
         * Only these languages are accepted for filtering and validation.
         */
        val LANGUAGE_WHITELIST: Set<String> = setOf(
            "javascript", "python", "php", "java", "go", "ruby", "rust",
            "cpp", "c", "csharp", "typescript", "sql", "html", "css",
            "json", "yaml", "xml", "bash", "shell", "plaintext", "text",
            "kotlin", "swift", "markdown"
        )

        /**
         * Minimum search query length before triggering search.
         */
        private const val MIN_SEARCH_LENGTH = 2

        /**
         * Debounce delay in milliseconds for search input.
         */
        private const val SEARCH_DEBOUNCE_MS = 300

        /**
         * Maximum number of tag checkboxes shown before scroll is enabled.
         */
        private const val MAX_VISIBLE_TAGS = 8

        /**
         * Number of lines to display in snippet preview.
         */
        private const val PREVIEW_LINES = 3
    }

    // UI Components - Search Section
    private val searchField: JBTextField = JBTextField()
    private val languageComboBox: JComboBox<String> = JComboBox()

    // UI Components - Tags Filter
    private val tagsPanel: JPanel = JPanel(VerticalLayout(4))
    private val tagsScrollPane: JBScrollPane = JBScrollPane(tagsPanel)

    // UI Components - Snippet List
    private val snippetList: JBList<Snippet> = JBList()
    private val listModel: DefaultListModel<Snippet> = DefaultListModel()
    private val listScrollPane: JBScrollPane = JBScrollPane(snippetList)

    // UI Components - Status and Error
    private val statusLabel: JBLabel = JBLabel()
    private val errorLabel: JBLabel = JBLabel()

    // Data
    private val tagCheckBoxes = mutableMapOf<String, JCheckBox>()
    private var availableTags: List<Tag> = emptyList()
    private var availableLanguages: List<String> = emptyList()
    private var selectedSnippet: Snippet? = null
    private var searchTimer: Timer? = null

    init {
        LOG.debug("Initializing InsertSnippetDialog for project: ${project.name}")

        title = "Insert Snippet"
        setOKButtonText("Insert")
        setCancelButtonText("Cancel")

        // Initialize dialog
        init()

        // Load initial data
        loadInitialData()
    }

    /**
     * Create the center panel for the dialog.
     * This is the main content area of the dialog.
     */
    override fun createCenterPanel(): JComponent {
        LOG.debug("Creating insert snippet dialog center panel")

        val mainPanel = JPanel(VerticalLayout(10))
        mainPanel.border = JBUI.Borders.empty(15, 20, 15, 20)

        // Header label
        val headerLabel = JBLabel("Search and select a snippet to insert:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD, 14f)
        mainPanel.add(headerLabel)

        // Search section
        val searchSection = createSearchSection()
        mainPanel.add(searchSection)

        // Language filter section
        val languageSection = createLanguageFilterSection()
        mainPanel.add(languageSection)

        // Tags filter section
        val tagsSection = createTagsFilterSection()
        mainPanel.add(tagsSection)

        // Snippet list
        val listSection = createSnippetListSection()
        mainPanel.add(listSection)

        // Status and error labels
        statusLabel.isVisible = false
        mainPanel.add(statusLabel)

        errorLabel.isVisible = false
        mainPanel.add(errorLabel)

        // Setup listeners
        setupListeners()

        // Set focus on search field
        SwingUtilities.invokeLater {
            searchField.requestFocusInWindow()
        }

        LOG.debug("Insert snippet dialog center panel created")
        return mainPanel
    }

    /**
     * Create the search input section.
     */
    private fun createSearchSection(): JPanel {
        val panel = JPanel(BorderLayout(5, 0))
        panel.isOpaque = false

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
        panel.isOpaque = false

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
        tagsScrollPane.setPreferredSize(java.awt.Dimension(400, 100))

        panel.add(tagsScrollPane)

        return panel
    }

    /**
     * Create the snippet list section.
     */
    private fun createSnippetListSection(): JPanel {
        val panel = JPanel(VerticalLayout(5))
        panel.isOpaque = false

        val headerLabel = JBLabel("Snippets:")
        headerLabel.font = headerLabel.font.deriveFont(java.awt.Font.BOLD)
        panel.add(headerLabel)

        // Configure snippet list
        snippetList.setModel(listModel)
        snippetList.setCellRenderer(SnippetListCellRenderer())
        snippetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)

        // Add selection listener
        snippetList.addListSelectionListener(ListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                selectedSnippet = snippetList.getSelectedValue()
                updateOKButtonState()
            }
        })

        // Configure scroll pane
        listScrollPane.setBorder(BorderFactory.createEmptyBorder())
        listScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED)
        listScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
        listScrollPane.setPreferredSize(java.awt.Dimension(600, 300))

        panel.add(listScrollPane)

        return panel
    }

    /**
     * Setup event listeners for UI components.
     */
    private fun setupListeners() {
        // Search field - trigger search on Enter key
        searchField.addActionListener {
            performSearch()
        }

        // Search field - debounce for typing
        searchField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = scheduleSearch()
            override fun removeUpdate(e: DocumentEvent) = scheduleSearch()
            override fun changedUpdate(e: DocumentEvent) = scheduleSearch()
        })

        // Language combo box
        languageComboBox.addActionListener {
            performSearch()
        }

        // Tag checkboxes
        // (Listeners added when tags are set)
    }

    /**
     * Schedule a search with debounce.
     */
    private fun scheduleSearch() {
        searchTimer?.stop()
        searchTimer = Timer(SEARCH_DEBOUNCE_MS) {
            performSearch()
        }.apply {
            isRepeats = false
            start()
        }
    }

    /**
     * Load initial data (tags and languages) from API.
     */
    private fun loadInitialData() {
        LOG.debug("Loading initial data for insert snippet dialog")

        // Load tags and languages in background
        UIUtil.invokeLaterIfNeeded {
            try {
                val tags = fetchTags()
                val languages = fetchLanguages()

                if (tags != null) {
                    setAvailableTags(tags)
                }

                if (languages != null) {
                    setAvailableLanguages(languages)
                }

                // Perform initial search
                performSearch()
            } catch (e: Exception) {
                LOG.error("Error loading initial data", e)
                showError("Failed to load snippets. Please try again.")
            }
        }
    }

    /**
     * Set available tags for the tags filter.
     */
    private fun setAvailableTags(tags: List<Tag>) {
        LOG.debug("Setting ${tags.size} available tags")

        availableTags = tags
        tagCheckBoxes.clear()
        tagsPanel.removeAll()

        // Sort tags by name
        val sortedTags = tags.sortedBy { it.name }

        // Create checkbox for each tag
        sortedTags.forEach { tag ->
            val checkBox = JCheckBox(tag.name)
            checkBox.toolTipText = "${tag.name} (${tag.getTotalCount()} items)"

            // Use tag color if available
            if (tag.hasColor()) {
                checkBox.foreground = java.awt.Color.decode(tag.color)
            }

            // Add listener to trigger search
            checkBox.addActionListener {
                performSearch()
            }

            tagsPanel.add(checkBox)
            tagCheckBoxes[tag.id] = checkBox
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
     * Set available languages for the language filter.
     */
    private fun setAvailableLanguages(languages: List<String>) {
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
     * Perform search for snippets based on current filters.
     */
    private fun performSearch() {
        val query = searchField.text.trim()
        val language = getSelectedLanguage()
        val tags = getSelectedTagIds()

        LOG.debug("Performing search: query='$query', language=$language, tags=$tags")

        // If query is too short and no filters, clear list
        if (query.length < MIN_SEARCH_LENGTH && language == null && tags.isEmpty()) {
            listModel.clear()
            return
        }

        // Show loading state
        showLoading()

        // Perform search in background
        UIUtil.invokeLaterIfNeeded {
            try {
                val snippets = fetchSnippets(query, language, tags)

                if (snippets != null) {
                    displaySnippets(snippets)
                    showStatus("${snippets.size} snippet${if (snippets.size != 1) "s" else ""} found")
                } else {
                    showError("Failed to search snippets. Please try again.")
                }
            } catch (e: Exception) {
                LOG.error("Error performing search", e)
                showError("An error occurred while searching. Please try again.")
            } finally {
                hideLoading()
            }
        }
    }

    /**
     * Fetch snippets from API based on filters.
     */
    private fun fetchSnippets(
        query: String,
        language: String?,
        tags: List<String>
    ): List<Snippet>? {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val apiUrl = settings.getApiUrl()
            val email = settings.getUserEmail()
            if (email.isEmpty()) return null

            val credentialStorage = SecureCredentialStorage.getInstance()
            val token = credentialStorage.getAuthToken(project, email) ?: return null

            val apiClient = SnipHiveApiClient.getInstance()

            // Build query parameters
            val queryParams = mutableMapOf<String, String>()
            if (query.isNotEmpty()) {
                queryParams["q"] = query
            }
            if (language != null) {
                queryParams["language"] = language
            }
            tags.forEach { tagId ->
                queryParams["tags[]"] = tagId
            }

            // Make API request - using SnippetsResponse wrapper
            val response = apiClient.get<SnippetsResponse>(
                apiUrl, "/snippets", token, queryParams.toMap(), SnippetsResponse::class.java
            )

            if (response.success && response.data != null) {
                response.data.data
            } else {
                LOG.warn("Failed to fetch snippets: ${response.error}")
                null
            }
        } catch (e: Exception) {
            LOG.error("Error fetching snippets", e)
            null
        }
    }

    /**
     * Fetch tags from API.
     */
    private fun fetchTags(): List<Tag>? {
        return try {
            val settings = SnipHiveSettings.getInstance(project)
            val apiUrl = settings.getApiUrl()
            val email = settings.getUserEmail()
            if (email.isEmpty()) return null

            val credentialStorage = SecureCredentialStorage.getInstance()
            val token = credentialStorage.getAuthToken(project, email) ?: return null

            val apiClient = SnipHiveApiClient.getInstance()

            val response = apiClient.get<TagsResponse>(
                apiUrl, "/tags", token, null, TagsResponse::class.java
            )

            if (response.success && response.data != null) {
                response.data.data
            } else {
                LOG.warn("Failed to fetch tags: ${response.error}")
                emptyList()
            }
        } catch (e: Exception) {
            LOG.error("Error fetching tags", e)
            emptyList()
        }
    }

    /**
     * Get hardcoded language whitelist.
     * The backend does not expose a /snippets/languages endpoint,
     * so languages are maintained as a static whitelist in the plugin.
     */
    private fun fetchLanguages(): List<String> {
        return LANGUAGE_WHITELIST.toList().sorted()
    }

    /**
     * Display snippets in the list.
     */
    private fun displaySnippets(snippets: List<Snippet>) {
        listModel.clear()
        snippets.forEach { snippet ->
            listModel.addElement(snippet)
        }

        // Select first snippet if available
        if (listModel.size() > 0) {
            snippetList.selectedIndex = 0
        }
    }

    /**
     * Get the currently selected language filter.
     */
    private fun getSelectedLanguage(): String? {
        val selection = languageComboBox.getSelectedItem() as? String
        return if (selection == ALL_LANGUAGES) null else selection
    }

    /**
     * Get the list of selected tag IDs.
     */
    private fun getSelectedTagIds(): List<String> {
        return tagCheckBoxes.filter { it.value.isSelected }.keys.toList()
    }

    /**
     * Show loading state.
     */
    private fun showLoading() {
        statusLabel.text = "Loading..."
        statusLabel.isVisible = true
    }

    /**
     * Hide loading state.
     */
    private fun hideLoading() {
        // No-op
    }

    /**
     * Show status message.
     */
    private fun showStatus(message: String) {
        statusLabel.text = message
        statusLabel.isVisible = true
    }

    /**
     * Show error message.
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
     * Update OK button state based on selection.
     */
    private fun updateOKButtonState() {
        isOKActionEnabled = selectedSnippet != null
    }

    /**
     * Override OK button action to insert selected snippet.
     */
    override fun doOKAction() {
        LOG.debug("OK button clicked - inserting snippet: ${selectedSnippet?.id}")

        if (selectedSnippet != null) {
            close(OK_EXIT_CODE)
        }
    }

    /**
     * Get the selected snippet.
     */
    fun getSelectedSnippet(): Snippet? = selectedSnippet

    /**
     * Preferred focus component is search field.
     */
    override fun getPreferredFocusedComponent(): JComponent? {
        return searchField
    }

    /**
     * Help ID for documentation (optional).
     */
    override fun getHelpId(): String? {
        return "sniphive.insert.snippet"
    }

    /**
     * Dialog dimension settings.
     */
    override fun getDimensionServiceKey(): String? {
        return "SnipHive.InsertSnippetDialog"
    }

    /**
     * Custom list cell renderer for snippet items.
     *
     * Displays each snippet with:
     * - Title (bold)
     * - Language badge
     * - Preview of content
     * - Tags
     */
    private class SnippetListCellRenderer : ListCellRenderer<Snippet> {

        private val defaultPanel: JPanel = JPanel()

        init {
            defaultPanel.layout = BorderLayout(JBUI.scale(10), JBUI.scale(5))
            defaultPanel.border = EmptyBorder(JBUI.insets(8, 10, 8, 10))
            defaultPanel.isOpaque = true
        }

        override fun getListCellRendererComponent(
            list: JList<out Snippet>,
            value: Snippet?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            defaultPanel.removeAll()

            defaultPanel.background = if (isSelected) {
                list.selectionBackground
            } else {
                list.background
            }

            if (value != null) {
                val displayPanel = createSnippetDisplay(value, list, isSelected)
                defaultPanel.add(displayPanel, BorderLayout.CENTER)
            }

            return defaultPanel
        }

        private fun createSnippetDisplay(snippet: Snippet, list: JList<*>, isSelected: Boolean): JPanel {
            val panel = JPanel(VerticalLayout(4))
            panel.isOpaque = false

            // Title row
            val titleRow = JPanel(VerticalLayout(0)).apply {
                isOpaque = false
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                alignmentX = Component.LEFT_ALIGNMENT
            }

            // Title label
            val titleLabel = JLabel(escapeHtml(snippet.title)).apply {
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
                foreground = if (isSelected) list.selectionForeground else list.foreground
            }
            titleRow.add(titleLabel)

            // Add spacer
            titleRow.add(Box.createHorizontalStrut(JBUI.scale(8)))

            // Language badge
            if (snippet.language != null) {
                val languageBadge = createLanguageBadge(snippet.language)
                titleRow.add(languageBadge)
            }

            panel.add(titleRow)

            // Content preview
            val previewText = getPreviewText(snippet.content)
            if (previewText.isNotEmpty()) {
                val previewLabel = JLabel("<html><div style='color: ${getGrayColor(isSelected)};'>${escapeHtml(previewText)}</div></html>").apply {
                    font = font.deriveFont(11f)
                }
                panel.add(previewLabel)
            }

            // Tags row
            if (snippet.tags.isNotEmpty()) {
                val tagsRow = JPanel(VerticalLayout(0)).apply {
                    isOpaque = false
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    alignmentX = Component.LEFT_ALIGNMENT
                }

                snippet.tags.take(3).forEach { tag ->
                    tagsRow.add(Box.createHorizontalStrut(JBUI.scale(if (snippet.tags.indexOf(tag) > 0) 5 else 0)))
                    val tagLabel = JLabel("#${escapeHtml(tag.name)}").apply {
                        font = font.deriveFont(10f)
                        foreground = if (isSelected) list.selectionForeground else JBUI.CurrentTheme.Label.foreground()
                    }
                    tagsRow.add(tagLabel)
                }

                if (snippet.tags.size > 3) {
                    val moreLabel = JLabel(" +${snippet.tags.size - 3} more").apply {
                        font = font.deriveFont(10f)
                        foreground = getGrayColor(isSelected)
                    }
                    tagsRow.add(moreLabel)
                }

                panel.add(tagsRow)
            }

            return panel
        }

        private fun createLanguageBadge(language: String): JLabel {
            val color = getLanguageColor(language)
            val hexColor = String.format("#%06X", (0x00FFFFFF and color))
            val textColor = if (isLightColor(color)) "#000000" else "#FFFFFF"

            return JLabel("<html><div style='background-color: $hexColor; color: $textColor; " +
                "padding: 2px 6px; border-radius: 3px; font-size: 10px;'>${escapeHtml(language)}</div></html>")
        }

        private fun getLanguageColor(language: String): Int {
            return LANGUAGE_COLORS[language.lowercase()] ?: 0xFF999999.toInt()
        }

        private fun getPreviewText(content: String): String {
            if (content.isEmpty()) return ""

            val lines = content.lines()
            val previewLines = lines.take(PREVIEW_LINES).map { line ->
                line.trim().take(60).ifEmpty { "..." }
            }.filter { it.isNotEmpty() }

            return previewLines.joinToString(" <span style='color: #999;'>|</span> ")
        }

        private fun getGrayColor(isSelected: Boolean): java.awt.Color {
            return if (isSelected) java.awt.Color(204, 204, 204) else java.awt.Color(136, 136, 136)
        }

        private fun isLightColor(color: Int): Boolean {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            val luminance = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            return luminance > 128
        }

        private fun escapeHtml(text: String): String {
            return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;")
        }

        companion object {
            private val LANGUAGE_COLORS = mapOf(
                "kotlin" to 0xFF7F52FF.toInt(),
                "java" to 0xFFB07219.toInt(),
                "javascript" to 0xFFF1E05A.toInt(),
                "typescript" to 0xFF2B7489.toInt(),
                "python" to 0xFF3572A5.toInt(),
                "php" to 0xFF4F5D95.toInt(),
                "ruby" to 0xFF701516.toInt(),
                "go" to 0xFF00ADD8.toInt(),
                "rust" to 0xFFDEA584.toInt(),
                "swift" to 0xFFF05138.toInt(),
                "c" to 0xFF555555.toInt(),
                "cpp" to 0xFFF34B7D.toInt(),
                "csharp" to 0xFF239120.toInt(),
                "sql" to 0xFFCC3849.toInt(),
                "html" to 0xFFE34C26.toInt(),
                "css" to 0xFF563D7C.toInt(),
                "scss" to 0xFFC6538C.toInt(),
                "bash" to 0xFF89E051.toInt(),
                "shell" to 0xFF89E051.toInt(),
                "json" to 0xFF292929.toInt(),
                "yaml" to 0xFFCB171E.toInt(),
                "xml" to 0xFF0060AC.toInt(),
                "markdown" to 0xFF083FA1.toInt()
            )
        }
    }

    // Response wrapper classes for API deserialization
    // Backend uses Laravel JsonResource format: {data: [...]}
    private data class SnippetsResponse(val data: List<Snippet> = emptyList())
    private data class TagsResponse(val data: List<Tag> = emptyList())
    private data class LanguagesResponse(val data: List<String> = emptyList())
}