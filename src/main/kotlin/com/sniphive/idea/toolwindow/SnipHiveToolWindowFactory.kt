package com.sniphive.idea.toolwindow

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ide.BrowserUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.services.NoteCacheListener
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnippetCacheListener
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveApiService
import com.sniphive.idea.services.SnipHiveAuthService
import com.sniphive.idea.services.SecureCredentialStorage
import com.sniphive.idea.crypto.E2EECryptoService
import com.sniphive.idea.crypto.E2EEProfile
import com.sniphive.idea.ui.*
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.*

/**
 * Factory class for creating the SnipHive tool window.
 *
 * The tool window provides a user interface for:
 * - Browsing and searching snippets (tab 1)
 * - Browsing and searching notes (tab 2)
 * - Viewing details
 * - Creating snippets from selected code
 * - Authentication with embedded login form
 *
 * Layout:
 * ┌─────────────────────────────┐
 * │ [Snippets] [Notes]          │  <- Tabs
 * ├─────────────────────────────┤
 * │ Search Panel                │
 * ├─────────────────────────────┤
 * │ List (Snippets/Notes)       │
 * │ (scrollable)                │
 * ├─────────────────────────────┤
 * │ Detail Panel                │
 * ├─────────────────────────────┤
 * │ Actions: Refresh | Create   │
 * └─────────────────────────────┘
 *
 * Security Note:
 * - No sensitive data (tokens, passwords, private keys) is displayed
 * - Content is loaded dynamically based on authentication status
 * - All secure data is stored in IDE Password Safe
 */
class SnipHiveToolWindowFactory : ToolWindowFactory {

    companion object {
        private val LOG = Logger.getInstance(SnipHiveToolWindowFactory::class.java)

        const val TOOL_WINDOW_ID = "SnipHive"
        const val TOOL_WINDOW_DISPLAY_NAME = "SnipHive"

        // Card layout states
        private const val CARD_LOGIN = "login"
        private const val CARD_MASTER_PASSWORD = "master_password"
        private const val CARD_CONTENT = "content"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        try {
            val settings = SnipHiveSettings.getInstance(project)
            val contentPanel = createContentPanel(project, settings, toolWindow)

            val contentFactory = com.intellij.ui.content.ContentFactory.getInstance()
            val content = contentFactory.createContent(contentPanel, TOOL_WINDOW_DISPLAY_NAME, false)
            content.isCloseable = false

            toolWindow.contentManager.addContent(content)

        } catch (e: Exception) {
            LOG.error("Failed to create SnipHive tool window content", e)

            val errorPanel = createErrorPanel(e.message ?: "Unknown error occurred")
            val contentFactory = com.intellij.ui.content.ContentFactory.getInstance()
            val errorContent = contentFactory.createContent(errorPanel, "Error", false)
            errorContent.isCloseable = false
            toolWindow.contentManager.addContent(errorContent)
        }
    }

    private fun createContentPanel(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow
    ): JComponent {
        val mainPanel = JPanel(CardLayout())

        // Create workspace selector reference to be reused
        var workspaceSelector: WorkspaceSelector? = null

        // Create cards
        val loginPanel = createLoginPanel(project, settings, toolWindow, mainPanel) { selector ->
            // Callback when login succeeds - load workspaces
            selector.loadWorkspaces()
        }
        val masterPasswordPanel = createMasterPasswordPanel(project, settings, toolWindow, mainPanel) {
            // Callback when master password unlock succeeds - load workspaces
            workspaceSelector?.loadWorkspaces()
        }
        val contentPanel = createMainContentPanel(project, settings, toolWindow, mainPanel) { selector ->
            workspaceSelector = selector
        }

        mainPanel.add(loginPanel, CARD_LOGIN)
        mainPanel.add(masterPasswordPanel, CARD_MASTER_PASSWORD)
        mainPanel.add(contentPanel, CARD_CONTENT)

        // Determine initial card based on auth and E2EE state
        // IMPORTANT: PasswordSafe operations must be off EDT to avoid SEVERE errors
        val userEmail = settings.getUserEmail()

        if (userEmail.isNotEmpty()) {
            // User is authenticated - show loading state initially
            // Show content card as loading placeholder while checking E2EE state
            showCard(mainPanel, CARD_CONTENT)

            // Check E2EE state on background thread (PasswordSafe operations prohibited on EDT)
            // Flattened threading: check both private key AND master password in single background call
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    // Validate auth token before attempting E2EE unlock
                    val authService = SnipHiveAuthService.getInstance()
                    val apiUrl = settings.getApiUrl().ifBlank { SnipHiveApiService.DEFAULT_API_URL }
                    val isTokenValid = authService.verifyToken(project, apiUrl, userEmail)

                    if (!isTokenValid) {
                        ApplicationManager.getApplication().invokeLater {
                            showCard(mainPanel, CARD_LOGIN)
                        }
                        return@executeOnPooledThread
                    }

                    val secureStorage = SecureCredentialStorage.getInstance()
                    val existingPrivateKey = secureStorage.getPrivateKey(project, userEmail)

                    val storedMasterPassword = if (existingPrivateKey == null) {
                        secureStorage.getMasterPassword(project, userEmail)
                    } else null

                    ApplicationManager.getApplication().invokeLater {
                        when {
                            existingPrivateKey != null -> {
                                // E2EE already unlocked - show content
                                settings.setE2eeUnlocked(true)
                                showCard(mainPanel, CARD_CONTENT)
                                workspaceSelector?.loadWorkspaces()
                            }
                            storedMasterPassword != null -> {
                                // Attempt auto-unlock with stored master password
                                attemptAutoUnlock(project, userEmail, storedMasterPassword, mainPanel, settings) { success ->
                                    if (success) {
                                        workspaceSelector?.loadWorkspaces()
                                    } else {
                                        // Auto-unlock failed - show master password panel
                                        showCard(mainPanel, CARD_MASTER_PASSWORD)
                                    }
                                }
                            }
                            else -> {
                                // No stored master password - show master password panel
                                showCard(mainPanel, CARD_MASTER_PASSWORD)
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to check E2EE state", e)
                    ApplicationManager.getApplication().invokeLater {
                        showCard(mainPanel, CARD_LOGIN)
                    }
                }
            }
        } else {
            // Not authenticated - show login
            showCard(mainPanel, CARD_LOGIN)
        }

        return mainPanel
    }

    private fun createMainContentPanel(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow,
        mainPanel: JPanel,
        onWorkspaceSelectorCreated: (WorkspaceSelector) -> Unit
    ): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(10)))
        panel.border = JBUI.Borders.empty(10)

        // Header with user info
        val headerPanel = createHeaderPanel(project, settings, toolWindow, mainPanel, onWorkspaceSelectorCreated)
        panel.add(headerPanel, BorderLayout.NORTH)

        // Tabbed pane for Snippets, Notes, Favorites, Archive
        val tabbedPane = JTabbedPane()

        // Snippets Tab
        val snippetsPanel = createSnippetsPanel(project)
        tabbedPane.addTab("Snippets", snippetsPanel)

        // Notes Tab
        val notesPanel = createNotesPanel(project)
        tabbedPane.addTab("Notes", notesPanel)

        // Favorites Tab
        val favoritesPanel = createFavoritesPanel(project, toolWindow.disposable)
        tabbedPane.addTab("Favorites", favoritesPanel)

        // Archive Tab
        val archivePanel = createArchivePanel(project)
        tabbedPane.addTab("Archive", archivePanel)

        // Pinned Tab
        val pinnedPanel = createPinnedPanel(project, toolWindow.disposable)
        tabbedPane.addTab("Pinned", pinnedPanel)

        panel.add(tabbedPane, BorderLayout.CENTER)

        return panel
    }

    private fun createSnippetsPanel(project: Project): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(5)))

        // Search panel
        val searchPanel = SearchPanel(project)

        // List only (detail panel removed - actions are now inline on list items)
        val listPanel = SnippetListPanel(project)

        // Actions panel
        val actionsPanel = createSnippetActionsPanel(project, listPanel)

        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(listPanel, BorderLayout.CENTER)
        panel.add(actionsPanel, BorderLayout.SOUTH)

        // Setup listeners (detailPanel is no longer needed)
        setupSnippetListeners(project, searchPanel, listPanel)

        // Subscribe to snippet cache changes
        subscribeToSnippetChanges(project, listPanel)

        return panel
    }

    private fun createNotesPanel(project: Project): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(5)))

        // Simple search field for notes
        val searchField = JBTextField()
        searchField.emptyText.text = "Search notes..."
        searchField.columns = 30

        val searchPanel = JPanel(BorderLayout(5, 0))
        searchPanel.border = JBUI.Borders.empty(5)
        searchPanel.add(JBLabel("Search:"), BorderLayout.WEST)
        searchPanel.add(searchField, BorderLayout.CENTER)

        // List only (detail panel removed - actions are now inline on list items)
        val listPanel = NoteListPanel(project)

        // Actions panel
        val actionsPanel = createNoteActionsPanel(project, listPanel)

        panel.add(searchPanel, BorderLayout.NORTH)
        panel.add(listPanel, BorderLayout.CENTER)
        panel.add(actionsPanel, BorderLayout.SOUTH)

        // Setup listeners (detailPanel is no longer needed)
        setupNoteListeners(project, searchField, listPanel)

        // Subscribe to note cache changes
        subscribeToNoteChanges(project, listPanel)

        return panel
    }

    private fun createSnippetActionsPanel(project: Project, listPanel: SnippetListPanel): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        panel.border = JBUI.Borders.emptyTop(10)

        val refreshButton = JButton("Refresh")
        refreshButton.toolTipText = "Refresh snippets from server"
        refreshButton.addActionListener {
            val lookupService = SnippetLookupService.getInstance(project)
            lookupService.refreshSnippets()
        }

        val createButton = JButton("Create Snippet")
        createButton.toolTipText = "Create a new snippet from selected code"
        createButton.addActionListener {
            JOptionPane.showMessageDialog(
                panel,
                "To create a snippet:\n\n1. Select code in the editor\n2. Right-click and choose 'Create Snippet'\n   OR press Shift+Alt+S",
                "Create Snippet",
                JOptionPane.INFORMATION_MESSAGE
            )
        }

        panel.add(refreshButton)
        panel.add(createButton)

        return panel
    }

    private fun createNoteActionsPanel(project: Project, listPanel: NoteListPanel): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        panel.border = JBUI.Borders.emptyTop(10)

        val refreshButton = JButton("Refresh")
        refreshButton.toolTipText = "Refresh notes from server"
        refreshButton.addActionListener {
            val lookupService = NoteLookupService.getInstance(project)
            lookupService.refreshNotes()
        }

        val createButton = JButton("Create Note")
        createButton.toolTipText = "Create a new note"
        createButton.addActionListener {
            val dialog = CreateNoteDialog(project)

            // Load tags
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val apiService = SnipHiveApiService.getInstance()
                    val tags = apiService.getTags(project)

                    ApplicationManager.getApplication().invokeLater {
                        dialog.setAvailableTags(tags)
                        dialog.show()
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to load tags for note dialog", e)
                }
            }
        }

        panel.add(refreshButton)
        panel.add(createButton)

        return panel
    }

    private fun createFavoritesPanel(project: Project, disposable: com.intellij.openapi.Disposable): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(5)))

        // Favorites panel (card-based, has its own scroll pane)
        val favoritesPanel = FavoritesPanel(project)

        // Wire up action handler for pin/favorite toggles
        favoritesPanel.setActionHandler(object : FavoritesActionHandler {
            override fun onTogglePin(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> toggleSnippetPin(project, item.snippet, favoritesPanel)
                    is ContentItem.NoteItem -> toggleNotePin(project, item.note, favoritesPanel)
                }
            }

            override fun onToggleFavorite(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> toggleSnippetFavorite(project, item.snippet, favoritesPanel)
                    is ContentItem.NoteItem -> toggleNoteFavorite(project, item.note, favoritesPanel)
                }
            }

            override fun onArchive(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> archiveSnippet(project, item.snippet, favoritesPanel)
                    is ContentItem.NoteItem -> archiveNote(project, item.note, favoritesPanel)
                }
            }
        })

        // Actions panel
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        actionsPanel.border = JBUI.Borders.emptyTop(10)

        val refreshButton = JButton("Refresh")
        refreshButton.toolTipText = "Refresh favorites from server"
        refreshButton.addActionListener {
            SnippetLookupService.getInstance(project).refreshSnippets()
            NoteLookupService.getInstance(project).refreshNotes()
        }

        actionsPanel.add(refreshButton)

        panel.add(favoritesPanel, BorderLayout.CENTER)
        panel.add(actionsPanel, BorderLayout.SOUTH)

        // Load favorites
        loadFavorites(project, favoritesPanel)

        // Subscribe to cache changes to auto-refresh favorites when snippets or notes are loaded/refreshed
        // Connection is scoped to the tool window's disposable to prevent leaks on close/reopen
        val bus = ApplicationManager.getApplication().messageBus.connect(disposable)
        bus.subscribe(SnippetLookupService.SNIPPET_CACHE_TOPIC, object : SnippetCacheListener {
            override fun onSnippetsRefreshed(snippets: List<Snippet>) {
                ApplicationManager.getApplication().invokeLater {
                    loadFavorites(project, favoritesPanel)
                }
            }
            override fun onRefreshStarted() {}
            override fun onRefreshFailed(error: String) {}
        })
        bus.subscribe(NoteLookupService.NOTE_CACHE_TOPIC, object : NoteCacheListener {
            override fun onNotesRefreshed(notes: List<Note>) {
                ApplicationManager.getApplication().invokeLater {
                    loadFavorites(project, favoritesPanel)
                }
            }
            override fun onRefreshStarted() {}
            override fun onRefreshFailed(error: String) {}
        })

        return panel
    }

    private fun loadFavorites(project: Project, favoritesPanel: FavoritesPanel) {
        val snippetService = SnippetLookupService.getInstance(project)
        val noteService = NoteLookupService.getInstance(project)

        val favoriteSnippets = snippetService.getAllSnippets().filter { it.isFavorite }
        val favoriteNotes = noteService.getAllNotes().filter { it.isFavorite }

        favoritesPanel.setItems(favoriteSnippets, favoriteNotes)
    }

    private fun createArchivePanel(project: Project): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(5)))

        // Archive panel (card-based, has its own scroll pane)
        val archivePanel = ArchivePanel(project)

        panel.add(archivePanel, BorderLayout.CENTER)

        // Load archived items
        loadArchivedItems(project, archivePanel)

        return panel
    }

    private fun loadArchivedItems(project: Project, archivePanel: ArchivePanel) {
        val snippetService = SnippetLookupService.getInstance(project)
        val noteService = NoteLookupService.getInstance(project)

        val archivedSnippets = snippetService.getAllSnippets().filter { it.isArchived() }
        val archivedNotes = noteService.getAllNotes().filter { it.isArchived() }

        archivePanel.setItems(archivedSnippets, archivedNotes)
    }

    private fun createPinnedPanel(project: Project, disposable: com.intellij.openapi.Disposable): JComponent {
        val panel = JPanel(BorderLayout(0, JBUI.scale(5)))

        // Pinned panel (card-based, has its own scroll pane)
        val pinnedPanel = PinnedPanel(project)

        // Wire up action handler for pin/favorite toggles
        val actionHandler = ItemActionHandler(project)
        pinnedPanel.setActionHandler(object : PinnedActionHandler {
            override fun onTogglePin(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> toggleSnippetPin(project, item.snippet, pinnedPanel)
                    is ContentItem.NoteItem -> toggleNotePin(project, item.note, pinnedPanel)
                }
            }

            override fun onToggleFavorite(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> toggleSnippetFavorite(project, item.snippet, pinnedPanel)
                    is ContentItem.NoteItem -> toggleNoteFavorite(project, item.note, pinnedPanel)
                }
            }

            override fun onArchive(item: ContentItem) {
                when (item) {
                    is ContentItem.SnippetItem -> archiveSnippet(project, item.snippet, pinnedPanel)
                    is ContentItem.NoteItem -> archiveNote(project, item.note, pinnedPanel)
                }
            }
        })

        // Actions panel
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        actionsPanel.border = JBUI.Borders.emptyTop(10)

        val refreshButton = JButton("Refresh")
        refreshButton.toolTipText = "Refresh pinned items from server"
        refreshButton.addActionListener {
            SnippetLookupService.getInstance(project).refreshSnippets()
            NoteLookupService.getInstance(project).refreshNotes()
        }

        actionsPanel.add(refreshButton)

        panel.add(pinnedPanel, BorderLayout.CENTER)
        panel.add(actionsPanel, BorderLayout.SOUTH)

        // Load pinned items
        loadPinnedItems(project, pinnedPanel)

        // Subscribe to cache changes to auto-refresh pinned items when snippets or notes are loaded/refreshed
        // Connection is scoped to the tool window's disposable to prevent leaks on close/reopen
        val bus = ApplicationManager.getApplication().messageBus.connect(disposable)
        bus.subscribe(SnippetLookupService.SNIPPET_CACHE_TOPIC, object : SnippetCacheListener {
            override fun onSnippetsRefreshed(snippets: List<Snippet>) {
                ApplicationManager.getApplication().invokeLater {
                    loadPinnedItems(project, pinnedPanel)
                }
            }
            override fun onRefreshStarted() {}
            override fun onRefreshFailed(error: String) {}
        })
        bus.subscribe(NoteLookupService.NOTE_CACHE_TOPIC, object : NoteCacheListener {
            override fun onNotesRefreshed(notes: List<Note>) {
                ApplicationManager.getApplication().invokeLater {
                    loadPinnedItems(project, pinnedPanel)
                }
            }
            override fun onRefreshStarted() {}
            override fun onRefreshFailed(error: String) {}
        })

        return panel
    }

    private fun loadPinnedItems(project: Project, pinnedPanel: PinnedPanel) {
        val snippetService = SnippetLookupService.getInstance(project)
        val noteService = NoteLookupService.getInstance(project)

        val pinnedSnippets = snippetService.getAllSnippets().filter { it.isPinned }
        val pinnedNotes = noteService.getAllNotes().filter { it.isPinned }

        pinnedPanel.setItems(pinnedSnippets, pinnedNotes)
    }

    private fun setupSnippetListeners(
        project: Project,
        searchPanel: SearchPanel,
        listPanel: SnippetListPanel
    ) {
        // Search listener
        searchPanel.addSearchChangeListener(object : SearchPanel.SearchChangeListener {
            override fun onSearchChanged(query: String, language: String?, tags: List<String>) {
                filterSnippets(project, query, language, tags, listPanel)
            }
        })

        // Toolbar action handler
        val actionHandler = ItemActionHandler(project)
        listPanel.setActionHandler(object : SnippetListPanel.ActionHandler {
            override fun onCopyContent(snippet: Snippet) {
                actionHandler.copyContent(snippet.content, snippet.id, snippet.isEncrypted())
            }

            override fun onEditSnippet(snippet: Snippet) {
                actionHandler.editItem(
                    title = snippet.title,
                    content = snippet.content,
                    dialogTitle = "Edit Snippet",
                    contentLabel = "Content:",
                    onSave = { newTitle, newContent ->
                        updateSnippet(project, snippet, newTitle, newContent, listPanel)
                    }
                )
            }

            override fun onDeleteSnippet(snippet: Snippet) {
                actionHandler.deleteItem(
                    title = snippet.title,
                    itemType = "Snippet",
                    onConfirm = {
                        deleteSnippet(project, snippet, listPanel)
                    }
                )
            }

            override fun onCopyPublicUrl(snippet: Snippet) {
                if (snippet.publicUrl != null) {
                    actionHandler.copyPublicUrl(
                        publicUrl = snippet.publicUrl,
                        encryptedDek = snippet.encryptedDek,
                        email = snippet.user?.email
                    )
                }
            }

            override fun onTogglePin(snippet: Snippet) {
                toggleSnippetPin(project, snippet, listPanel)
            }

            override fun onToggleFavorite(snippet: Snippet) {
                toggleSnippetFavorite(project, snippet, listPanel)
            }

            override fun onArchiveSnippet(snippet: Snippet) {
                archiveSnippet(project, snippet, listPanel)
            }
        })
    }

    private fun setupNoteListeners(
        project: Project,
        searchField: JBTextField,
        listPanel: NoteListPanel
    ) {
        // Search listener with debounce
        var searchTimer: Timer? = null
        searchField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                searchTimer?.stop()
                searchTimer = Timer(300) {
                    filterNotes(project, searchField.text, listPanel)
                }.apply {
                    isRepeats = false
                    start()
                }
            }
        })

        // Toolbar action handler
        val actionHandler = ItemActionHandler(project)
        listPanel.setActionHandler(object : NoteListPanel.ActionHandler {
            override fun onCopyContent(note: Note) {
                actionHandler.copyContent(note.content, note.id, note.isEncrypted())
            }

            override fun onEditNote(note: Note) {
                actionHandler.editItem(
                    title = note.title,
                    content = note.content,
                    dialogTitle = "Edit Note",
                    contentLabel = "Content (Markdown):",
                    onSave = { newTitle, newContent ->
                        updateNote(project, note, newTitle, newContent, listPanel)
                    }
                )
            }

            override fun onDeleteNote(note: Note) {
                actionHandler.deleteItem(
                    title = note.title,
                    itemType = "Note",
                    onConfirm = {
                        deleteNote(project, note, listPanel)
                    }
                )
            }

            override fun onCopyPublicUrl(note: Note) {
                if (note.publicUrl != null) {
                    actionHandler.copyPublicUrl(
                        publicUrl = note.publicUrl,
                        encryptedDek = note.encryptedDek,
                        email = note.user?.email
                    )
                }
            }

            override fun onTogglePin(note: Note) {
                toggleNotePin(project, note, listPanel)
            }

            override fun onToggleFavorite(note: Note) {
                toggleNoteFavorite(project, note, listPanel)
            }

            override fun onArchiveNote(note: Note) {
                archiveNote(project, note, listPanel)
            }
        })
    }

    private fun filterSnippets(
        project: Project,
        query: String,
        language: String?,
        tags: List<String>,
        listPanel: SnippetListPanel
    ) {
        val lookupService = SnippetLookupService.getInstance(project)
        var snippets = lookupService.getAllSnippets()

        // Filter by query
        if (query.isNotEmpty()) {
            snippets = snippets.filter { snippet ->
                snippet.title.contains(query, ignoreCase = true) ||
                snippet.content.contains(query, ignoreCase = true) ||
                snippet.tags.any { it.name.contains(query, ignoreCase = true) }
            }
        }

        // Filter by language
        if (language != null) {
            snippets = snippets.filter { it.language.equals(language, ignoreCase = true) }
        }

        // Filter by tags
        if (tags.isNotEmpty()) {
            snippets = snippets.filter { snippet ->
                snippet.tags.any { it.id in tags }
            }
        }

        listPanel.setSnippets(snippets)
    }

    private fun filterNotes(
        project: Project,
        query: String,
        listPanel: NoteListPanel
    ) {
        val lookupService = NoteLookupService.getInstance(project)
        var notes = lookupService.getAllNotes()

        // Filter by query
        if (query.isNotEmpty()) {
            notes = notes.filter { note ->
                note.title.contains(query, ignoreCase = true) ||
                note.content.contains(query, ignoreCase = true) ||
                note.tags.any { it.name.contains(query, ignoreCase = true) }
            }
        }

        listPanel.setNotes(notes)
    }

    private fun subscribeToSnippetChanges(
        project: Project,
        listPanel: SnippetListPanel
    ) {
        val bus = ApplicationManager.getApplication().messageBus.connect()
        bus.subscribe(SnippetLookupService.SNIPPET_CACHE_TOPIC, object : SnippetCacheListener {
            override fun onRefreshStarted() {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setLoading(true)
                }
            }

            override fun onSnippetsRefreshed(snippets: List<Snippet>) {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setSnippets(snippets)
                }
            }

            override fun onRefreshFailed(error: String) {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.showErrorState(error)
                }
            }
        })
    }

    private fun subscribeToNoteChanges(
        project: Project,
        listPanel: NoteListPanel
    ) {
        val bus = ApplicationManager.getApplication().messageBus.connect()
        bus.subscribe(NoteLookupService.NOTE_CACHE_TOPIC, object : NoteCacheListener {
            override fun onRefreshStarted() {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setLoading(true)
                }
            }

            override fun onNotesRefreshed(notes: List<Note>) {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.setNotes(notes)
                }
            }

            override fun onRefreshFailed(error: String) {
                ApplicationManager.getApplication().invokeLater {
                    listPanel.showErrorState(error)
                }
            }
        })
    }

    private fun createHeaderPanel(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow,
        mainPanel: JPanel,
        onWorkspaceSelectorCreated: (WorkspaceSelector) -> Unit
    ): JPanel {
        val panel = JPanel(BorderLayout(5, 0))
        panel.border = JBUI.Borders.emptyBottom(10)

        // User info
        val userLabel = JBLabel("<html><b>Logged in as:</b> ${settings.getUserEmail()}</html>")

        // Workspace selector
        val workspaceSelector = WorkspaceSelector(project)
        onWorkspaceSelectorCreated(workspaceSelector)

        // Logout button
        val logoutButton = JButton("Logout")
        logoutButton.toolTipText = "Logout from SnipHive"
        logoutButton.addActionListener {
            performLogout(project, settings, toolWindow, mainPanel)
        }

        // Left panel (user info)
        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(userLabel, BorderLayout.CENTER)

        // Right panel (workspace + logout)
        val rightPanel = JPanel(BorderLayout(5, 0))
        rightPanel.add(workspaceSelector, BorderLayout.CENTER)
        rightPanel.add(logoutButton, BorderLayout.EAST)

        panel.add(leftPanel, BorderLayout.WEST)
        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }

    /**
     * Create master password panel with embedded form for E2EE unlock.
     */
    private fun createMasterPasswordPanel(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow,
        mainPanel: JPanel,
        onUnlockSuccess: () -> Unit
    ): JComponent {
        val mainUnlockPanel = JPanel(BorderLayout(10, 10))
        mainUnlockPanel.border = JBUI.Borders.empty(20)

        // Header with lock icon
        val headerLabel = JBLabel("<html><h2 style='margin: 0;'>🔒 Unlock Encrypted Content</h2></html>")
        val subtitleLabel = JBLabel("<html><p style='color: gray;'>Your snippets and notes are encrypted with end-to-end encryption. Enter your master password to access them.</p></html>")

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerLabel.alignmentX = JComponent.CENTER_ALIGNMENT
        subtitleLabel.alignmentX = JComponent.CENTER_ALIGNMENT
        headerPanel.add(headerLabel)
        headerPanel.add(Box.createVerticalStrut(5))
        headerPanel.add(subtitleLabel)

        // Unlock form
        val formPanel = JPanel(GridBagLayout())
        formPanel.border = JBUI.Borders.emptyTop(20)

        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Master password field
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        formPanel.add(JLabel("Master Password:"), gbc)

        val passwordField = JBPasswordField()
        passwordField.columns = 25
        gbc.gridx = 1
        gbc.weightx = 1.0
        formPanel.add(passwordField, gbc)

        // Auto-unlock info
        val rememberInfoLabel = JBLabel("Master password will be stored securely for auto-unlock.")
        rememberInfoLabel.foreground = java.awt.Color.GRAY
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.gridwidth = 2
        formPanel.add(rememberInfoLabel, gbc)

        // Error label
        val errorLabel = JBLabel("")
        errorLabel.foreground = java.awt.Color(211, 47, 47)
        errorLabel.isVisible = false
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        formPanel.add(errorLabel, gbc)

        // Buttons panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))

        val unlockButton = JButton("Unlock")
        unlockButton.toolTipText = "Unlock your encrypted content"

        val recoveryButton = JButton("Use Recovery Code")
        recoveryButton.toolTipText = "Unlock using your recovery code"

        val logoutButton = JButton("Logout")
        logoutButton.toolTipText = "Logout and switch to a different account"
        logoutButton.addActionListener {
            performLogout(project, settings, toolWindow, mainPanel)
        }

        buttonPanel.add(unlockButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(recoveryButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(logoutButton)

        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 2
        formPanel.add(buttonPanel, gbc)

        // Unlock action
        unlockButton.addActionListener {
            val password = String(passwordField.password)

            if (password.isEmpty()) {
                errorLabel.text = "Master password is required"
                errorLabel.isVisible = true
                passwordField.requestFocusInWindow()
                return@addActionListener
            }

            // Disable button and show loading
            unlockButton.isEnabled = false
            unlockButton.text = "Unlocking..."
            errorLabel.isVisible = false

            // Perform unlock
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val apiService = SnipHiveApiService.getInstance()
                    val securityStatus = apiService.getSecurityStatus(project)

                    if (securityStatus == null) {
                        // Auth token is invalid or network error
                        ApplicationManager.getApplication().invokeLater {
                            errorLabel.text = "Authentication failed. Please log in again."
                            errorLabel.isVisible = true
                            unlockButton.isEnabled = true
                            unlockButton.text = "Unlock"
                            showCard(mainPanel, CARD_LOGIN)
                        }
                        return@executeOnPooledThread
                    }

                    if (!securityStatus.setupComplete || securityStatus.e2eeProfile == null) {
                        ApplicationManager.getApplication().invokeLater {
                            errorLabel.text = "E2EE is not set up for this account"
                            errorLabel.isVisible = true
                            unlockButton.isEnabled = true
                            unlockButton.text = "Unlock"
                        }
                        return@executeOnPooledThread
                    }

                    val profile = securityStatus.e2eeProfile
                    if (profile.kdfSalt == null || profile.privateKeyIV == null ||
                        profile.encryptedPrivateKey == null || profile.kdfIterations == null) {
                        ApplicationManager.getApplication().invokeLater {
                            errorLabel.text = "E2EE profile incomplete. Please contact support or try re-logging in."
                            errorLabel.isVisible = true
                            unlockButton.isEnabled = true
                            unlockButton.text = "Unlock"
                        }
                        return@executeOnPooledThread
                    }

                    val privateKey = E2EECryptoService.unlockWithMasterPassword(password, profile)

                    // Store decrypted private key
                    val secureStorage = SecureCredentialStorage.getInstance()
                    val privateKeyJwk = com.sniphive.idea.crypto.RSACrypto.exportPrivateKeyToJWK(privateKey).toString()
                    secureStorage.storePrivateKey(project, settings.getUserEmail(), privateKeyJwk)

                    // Store master password for auto-unlock after a successful unlock.
                    secureStorage.storeMasterPassword(project, settings.getUserEmail(), password)
                    settings.setRememberMasterPassword(true)

                    // Update E2EE state
                    settings.setE2eeUnlocked(true)

                    ApplicationManager.getApplication().invokeLater {
                        showCard(mainPanel, CARD_CONTENT)
                        onUnlockSuccess()
                    }
                } catch (e: Exception) {
                    LOG.warn("Failed to unlock E2EE: ${e.message}")
                    ApplicationManager.getApplication().invokeLater {
                        errorLabel.text = "Invalid master password. Please try again."
                        errorLabel.isVisible = true
                        unlockButton.isEnabled = true
                        unlockButton.text = "Unlock"
                        passwordField.text = ""
                        passwordField.requestFocusInWindow()
                    }
                }
            }
        }

        // Recovery code action
        recoveryButton.addActionListener {
            val email = settings.getUserEmail()
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val apiService = SnipHiveApiService.getInstance()
                    val securityStatus = apiService.getSecurityStatus(project)

                    if (securityStatus != null && securityStatus.e2eeProfile != null) {
                        ApplicationManager.getApplication().invokeLater {
                            val dialog = MasterPasswordDialog(project, email, securityStatus.e2eeProfile)
                            dialog.show()

                            if (dialog.isUnlockSuccessful()) {
                                settings.setE2eeUnlocked(true)
                                showCard(mainPanel, CARD_CONTENT)
                                onUnlockSuccess()
                            }
                        }
                    }
                } catch (e: Exception) {
                    LOG.error("Failed to show recovery dialog", e)
                }
            }
        }

        // Enter key support
        passwordField.addActionListener { unlockButton.doClick() }

        // Center the form
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(formPanel, BorderLayout.NORTH)

        mainUnlockPanel.add(headerPanel, BorderLayout.NORTH)
        mainUnlockPanel.add(centerPanel, BorderLayout.CENTER)

        return mainUnlockPanel
    }

    /**
     * Attempt auto-unlock with stored master password.
     */
    private fun attemptAutoUnlock(
        project: Project,
        email: String,
        masterPassword: String,
        mainPanel: JPanel,
        settings: SnipHiveSettings,
        callback: (Boolean) -> Unit
    ) {

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val securityStatus = apiService.getSecurityStatus(project)


                if (securityStatus != null && securityStatus.setupComplete && securityStatus.e2eeProfile != null) {
                    val privateKey = E2EECryptoService.unlockWithMasterPassword(masterPassword, securityStatus.e2eeProfile)

                    // Store decrypted private key
                    val secureStorage = SecureCredentialStorage.getInstance()
                    val privateKeyJwk = com.sniphive.idea.crypto.RSACrypto.exportPrivateKeyToJWK(privateKey).toString()
                    secureStorage.storePrivateKey(project, email, privateKeyJwk)

                    // Update E2EE state
                    settings.setE2eeUnlocked(true)

                    ApplicationManager.getApplication().invokeLater {
                        showCard(mainPanel, CARD_CONTENT)
                        callback(true)
                    }
                } else {
                    LOG.warn("E2EE not set up for user: $email")
                    ApplicationManager.getApplication().invokeLater { callback(false) }
                }
            } catch (e: Exception) {
                LOG.warn("Auto-unlock failed: ${e.message}")
                // Clear stored master password on failure (it's wrong)
                SecureCredentialStorage.getInstance().removeMasterPassword(project, email)
                ApplicationManager.getApplication().invokeLater { callback(false) }
            }
        }
    }

    /**
     * Create login panel with embedded form.
     */
    private fun createLoginPanel(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow,
        mainPanel: JPanel,
        onLoginSuccess: (WorkspaceSelector) -> Unit
    ): JComponent {
        val mainLoginPanel = JPanel(BorderLayout(10, 10))
        mainLoginPanel.border = JBUI.Borders.empty(20)

        // Header
        val headerLabel = JBLabel("<html><h2 style='margin: 0;'>SnipHive</h2></html>")
        val subtitleLabel = JBLabel("<html><p style='color: gray;'>Login to access your snippets and notes</p></html>")

        val headerPanel = JPanel()
        headerPanel.layout = BoxLayout(headerPanel, BoxLayout.Y_AXIS)
        headerLabel.alignmentX = JComponent.CENTER_ALIGNMENT
        subtitleLabel.alignmentX = JComponent.CENTER_ALIGNMENT
        headerPanel.add(headerLabel)
        headerPanel.add(Box.createVerticalStrut(5))
        headerPanel.add(subtitleLabel)

        // Login form
        val formPanel = JPanel(GridBagLayout())
        formPanel.border = JBUI.Borders.emptyTop(20)

        val gbc = GridBagConstraints()
        gbc.insets = Insets(5, 5, 5, 5)
        gbc.fill = GridBagConstraints.HORIZONTAL

        // Email field
        gbc.gridx = 0
        gbc.gridy = 0
        gbc.weightx = 0.0
        formPanel.add(JLabel("Email:"), gbc)

        val emailField = JBTextField()
        emailField.columns = 25
        gbc.gridx = 1
        gbc.weightx = 1.0
        formPanel.add(emailField, gbc)

        // Password field
        gbc.gridx = 0
        gbc.gridy = 1
        gbc.weightx = 0.0
        formPanel.add(JLabel("Password:"), gbc)

        val passwordField = JBPasswordField()
        passwordField.columns = 25
        gbc.gridx = 1
        gbc.weightx = 1.0
        formPanel.add(passwordField, gbc)

        // Error label
        val errorLabel = JBLabel("")
        errorLabel.foreground = java.awt.Color(211, 47, 47)
        errorLabel.isVisible = false
        gbc.gridx = 0
        gbc.gridy = 2
        gbc.gridwidth = 2
        formPanel.add(errorLabel, gbc)

        // Login button
        val loginButton = JButton("Login")
        loginButton.toolTipText = "Login to SnipHive"

        // Register button
        val registerButton = JButton("Register")
        registerButton.toolTipText = "Create a new SnipHive account"

        val buttonPanel = JPanel(FlowLayout(FlowLayout.CENTER))
        buttonPanel.add(loginButton)
        buttonPanel.add(Box.createHorizontalStrut(10))
        buttonPanel.add(registerButton)

        // Register action - opens registration page in browser
        registerButton.addActionListener {
            BrowserUtil.browse("https://sniphive.net/register")
        }

        gbc.gridx = 0
        gbc.gridy = 3
        gbc.gridwidth = 2
        formPanel.add(buttonPanel, gbc)

        // Login action
        loginButton.addActionListener {
            val email = emailField.text.trim()
            val password = String(passwordField.password)
            

            if (email.isEmpty()) {
                errorLabel.text = "Email is required"
                errorLabel.isVisible = true
                emailField.requestFocusInWindow()
                return@addActionListener
            }

            if (password.isEmpty()) {
                errorLabel.text = "Password is required"
                errorLabel.isVisible = true
                passwordField.requestFocusInWindow()
                return@addActionListener
            }


            // Disable button and show loading
            loginButton.isEnabled = false
            loginButton.text = "Logging in..."
            errorLabel.isVisible = false


            // Perform login on background thread (PasswordSafe operations prohibited on EDT)
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    val authService = SnipHiveAuthService.getInstance()
                    val result = authService.login(project, settings.getApiUrl(), email, password)

                    ApplicationManager.getApplication().invokeLater {
                        if (result.success) {

                            // Check E2EE status and redirect to appropriate card
                            checkE2EEAndRedirect(project, email, mainPanel, settings) { unlocked ->
                                if (unlocked) {
                                    // E2EE unlocked - load workspaces
                                    val contentPanel = mainPanel.getComponent(2) as? JPanel
                                    if (contentPanel != null) {
                                        val workspaceSelector = findWorkspaceSelector(contentPanel)
                                        if (workspaceSelector != null) {
                                            onLoginSuccess(workspaceSelector)
                                        }
                                    }
                                }
                                // If not unlocked, user is on master password card
                            }
                        } else {
                            LOG.warn("Login failed: ${result.message}")
                            errorLabel.text = result.message
                            errorLabel.isVisible = true
                            loginButton.isEnabled = true
                            loginButton.text = "Login"
                            passwordField.text = ""
                            passwordField.requestFocusInWindow()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        errorLabel.text = "An error occurred. Please try again."
                        errorLabel.isVisible = true
                        loginButton.isEnabled = true
                        loginButton.text = "Login"
                    }
                }
            }
        }

        // Enter key support
        passwordField.addActionListener { loginButton.doClick() }
        emailField.addActionListener { passwordField.requestFocusInWindow() }

        // Center the form
        val centerPanel = JPanel(BorderLayout())
        centerPanel.add(formPanel, BorderLayout.NORTH)

        mainLoginPanel.add(headerPanel, BorderLayout.NORTH)
        mainLoginPanel.add(centerPanel, BorderLayout.CENTER)

        return mainLoginPanel
    }

    private fun showCard(mainPanel: JPanel, cardName: String) {
        val cardLayout = mainPanel.layout as CardLayout
        cardLayout.show(mainPanel, cardName)
    }

    /**
     * Perform logout and refresh tool window.
     */
    private fun performLogout(
        project: Project,
        settings: SnipHiveSettings,
        toolWindow: ToolWindow,
        mainPanel: JPanel
    ) {
        val email = settings.getUserEmail()

        // Clear E2EE session state
        settings.clearE2eeSession()

        // Perform logout on background thread (PasswordSafe operations prohibited on EDT)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val secureStorage = SecureCredentialStorage.getInstance()
                // Clear stored master password and decrypted private key
                secureStorage.removeMasterPassword(project, email)
                secureStorage.removePrivateKey(project, email)

                val authService = SnipHiveAuthService.getInstance()
                val success = authService.logout(project, settings.getApiUrl(), email, notifyApi = true)

                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        // Clear caches
                        SnippetLookupService.getInstance(project).clearCache()
                        NoteLookupService.getInstance(project).clearCache()
                        showCard(mainPanel, CARD_LOGIN)
                    } else {
                        LOG.warn("Logout failed for user: $email")
                        JOptionPane.showMessageDialog(
                            mainPanel,
                            "Failed to logout. Please try again.",
                            "Logout Failed",
                            JOptionPane.ERROR_MESSAGE
                        )
                    }
                }
            } catch (e: Exception) {
                LOG.error("Logout error", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        mainPanel,
                        "Failed to logout. Please try again.",
                        "Logout Failed",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    /**
     * Check E2EE status and redirect to appropriate card.
     * Shows master password card if E2EE needs unlock, content card otherwise.
     *
     * @param project The current project
     * @param email The user's email
     * @param mainPanel The main panel with card layout
     * @param settings The settings instance
     * @param onComplete Callback with unlock status (true if unlocked, false if needs master password)
     */
    private fun checkE2EEAndRedirect(
        project: Project,
        email: String,
        mainPanel: JPanel,
        settings: SnipHiveSettings,
        onComplete: (Boolean) -> Unit
    ) {

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val securityStatus = apiService.getSecurityStatus(project)

                if (securityStatus == null) {
                    // Auth token invalid or network failure - redirect to login
                    ApplicationManager.getApplication().invokeLater {
                        showCard(mainPanel, CARD_LOGIN)
                        onComplete(false)
                    }
                    return@executeOnPooledThread
                }

                if (securityStatus.setupComplete) {
                    // Check if we already have the private key
                    val secureStorage = SecureCredentialStorage.getInstance()
                    val existingPrivateKey = secureStorage.getPrivateKey(project, email)

                    if (existingPrivateKey == null) {
                        // Need to unlock E2EE - show master password card
                        // Try auto-unlock with stored master password
                        val storedMasterPassword = secureStorage.getMasterPassword(project, email)

                        if (storedMasterPassword != null && securityStatus.e2eeProfile != null) {
                            try {
                                val privateKey = E2EECryptoService.unlockWithMasterPassword(storedMasterPassword, securityStatus.e2eeProfile)
                                val privateKeyJwk = com.sniphive.idea.crypto.RSACrypto.exportPrivateKeyToJWK(privateKey).toString()
                                secureStorage.storePrivateKey(project, email, privateKeyJwk)
                                settings.setE2eeUnlocked(true)

                                ApplicationManager.getApplication().invokeLater {
                                    showCard(mainPanel, CARD_CONTENT)
                                    onComplete(true)
                                }
                            } catch (e: Exception) {
                                secureStorage.removeMasterPassword(project, email)
                                ApplicationManager.getApplication().invokeLater {
                                    showCard(mainPanel, CARD_MASTER_PASSWORD)
                                    onComplete(false)
                                }
                            }
                        } else {
                            // No stored master password - show master password card
                            ApplicationManager.getApplication().invokeLater {
                                showCard(mainPanel, CARD_MASTER_PASSWORD)
                                onComplete(false)
                            }
                        }
                    } else {
                        // Private key already available
                        settings.setE2eeUnlocked(true)
                        ApplicationManager.getApplication().invokeLater {
                            showCard(mainPanel, CARD_CONTENT)
                            onComplete(true)
                        }
                    }
                } else {
                    // E2EE not set up (should not happen with mandatory E2EE)
                    ApplicationManager.getApplication().invokeLater {
                        showCard(mainPanel, CARD_CONTENT)
                        onComplete(true)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Login error", e)
                ApplicationManager.getApplication().invokeLater {
                    showCard(mainPanel, CARD_MASTER_PASSWORD)
                    onComplete(false)
                }
            }
        }
    }

    /**
     * Find WorkspaceSelector in component hierarchy.
     */
    private fun findWorkspaceSelector(component: java.awt.Component): WorkspaceSelector? {
        if (component is WorkspaceSelector) return component
        if (component is java.awt.Container) {
            for (child in component.components) {
                val found = findWorkspaceSelector(child)
                if (found != null) return found
            }
        }
        return null
    }

    /**
     * Create an error panel to display when tool window creation fails.
     */
    private fun createErrorPanel(errorMessage: String): JComponent {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(20)

        val errorLabel = JBLabel(
            """<html>
            <div style='text-align: center; padding: 20px; color: #CC0000;'>
            <h3>SnipHive Error</h3>
            <p>Failed to load SnipHive tool window.</p>
            <p style='font-size: 11px; color: #666666;'>Error: $errorMessage</p>
            </div>
            </html>""".trimIndent()
        )
        errorLabel.horizontalAlignment = SwingConstants.CENTER

        panel.add(errorLabel, BorderLayout.CENTER)

        return panel
    }

    // ───────────────────────── Inline Action Helper Methods ─────────────────────────

    private fun updateSnippet(
        project: Project,
        snippet: Snippet,
        newTitle: String,
        newContent: String,
        listPanel: SnippetListPanel
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.updateSnippet(
                    project = project,
                    snippetSlug = snippet.slug ?: snippet.id,
                    title = newTitle,
                    content = newContent
                )

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        Messages.showInfoMessage(project, "Snippet updated successfully!", "Success")
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        listPanel.updateSnippet(updated)
                    } else {
                        Messages.showErrorDialog(project, "Failed to update snippet.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to update snippet", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun deleteSnippet(
        project: Project,
        snippet: Snippet,
        listPanel: SnippetListPanel
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val success = apiService.deleteSnippet(project, snippet.slug ?: snippet.id)

                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Snippet deleted successfully!", "Deleted")
                        SnippetLookupService.getInstance(project).removeSnippet(snippet.id)
                        listPanel.removeSnippet(snippet.id)
                    } else {
                        Messages.showErrorDialog(project, "Failed to delete snippet.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete snippet", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun updateNote(
        project: Project,
        note: Note,
        newTitle: String,
        newContent: String,
        listPanel: NoteListPanel
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.updateNote(
                    project = project,
                    noteSlug = note.slug ?: note.id,
                    title = newTitle,
                    content = newContent
                )

                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        Messages.showInfoMessage(project, "Note updated successfully!", "Success")
                        NoteLookupService.getInstance(project).updateNote(updated)
                        listPanel.updateNote(updated)
                    } else {
                        Messages.showErrorDialog(project, "Failed to update note.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to update note", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun deleteNote(
        project: Project,
        note: Note,
        listPanel: NoteListPanel
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val success = apiService.deleteNote(project, note.slug ?: note.id)

                ApplicationManager.getApplication().invokeLater {
                    if (success) {
                        Messages.showInfoMessage(project, "Note deleted successfully!", "Deleted")
                        NoteLookupService.getInstance(project).removeNote(note.id)
                        listPanel.removeNote(note.id)
                    } else {
                        Messages.showErrorDialog(project, "Failed to delete note.", "Error")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to delete note", e)
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, "Error: ${e.message}", "Error")
                }
            }
        }
    }

    private fun toggleSnippetPin(project: Project, snippet: Snippet, listPanel: SnippetListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.togglePin(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        listPanel.updateSnippet(updated)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet pin", e)
            }
        }
    }

    private fun toggleSnippetFavorite(project: Project, snippet: Snippet, listPanel: SnippetListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleFavorite(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        listPanel.updateSnippet(updated)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet favorite", e)
            }
        }
    }

    private fun archiveSnippet(project: Project, snippet: Snippet, listPanel: SnippetListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveSnippet(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        listPanel.removeSnippet(snippet.id)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive snippet", e)
            }
        }
    }

    private fun toggleNotePin(project: Project, note: Note, listPanel: NoteListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNotePin(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        listPanel.updateNote(updated)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note pin", e)
            }
        }
    }

    private fun toggleNoteFavorite(project: Project, note: Note, listPanel: NoteListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNoteFavorite(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        listPanel.updateNote(updated)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note favorite", e)
            }
        }
    }

    private fun archiveNote(project: Project, note: Note, listPanel: NoteListPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveNote(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        listPanel.removeNote(note.id)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive note", e)
            }
        }
    }

    // ───────────────────────── Toggle methods for PinnedPanel ─────────────────────────

    private fun toggleSnippetPin(project: Project, snippet: Snippet, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.togglePin(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet pin", e)
            }
        }
    }

    private fun toggleSnippetPin(project: Project, snippet: Snippet, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.togglePin(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet pin", e)
            }
        }
    }

    private fun toggleSnippetFavorite(project: Project, snippet: Snippet, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleFavorite(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet favorite", e)
            }
        }
    }

    private fun toggleSnippetFavorite(project: Project, snippet: Snippet, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleFavorite(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle snippet favorite", e)
            }
        }
    }

    private fun toggleNoteFavorite(project: Project, note: Note, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNoteFavorite(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note favorite", e)
            }
        }
    }

    private fun toggleNotePin(project: Project, note: Note, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNotePin(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note pin", e)
            }
        }
    }

    private fun toggleNotePin(project: Project, note: Note, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNotePin(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note pin", e)
            }
        }
    }

    private fun toggleNoteFavorite(project: Project, note: Note, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.toggleNoteFavorite(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to toggle note favorite", e)
            }
        }
    }

    private fun archiveSnippet(project: Project, snippet: Snippet, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveSnippet(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive snippet", e)
            }
        }
    }

    private fun archiveNote(project: Project, note: Note, pinnedPanel: PinnedPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveNote(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadPinnedItems(project, pinnedPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive note", e)
            }
        }
    }

    private fun archiveSnippet(project: Project, snippet: Snippet, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveSnippet(project, snippet.slug ?: snippet.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        SnippetLookupService.getInstance(project).updateSnippet(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive snippet", e)
            }
        }
    }

    private fun archiveNote(project: Project, note: Note, favoritesPanel: FavoritesPanel) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val updated = apiService.archiveNote(project, note.slug ?: note.id)
                ApplicationManager.getApplication().invokeLater {
                    if (updated != null) {
                        NoteLookupService.getInstance(project).updateNote(updated)
                        loadFavorites(project, favoritesPanel)
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to archive note", e)
            }
        }
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
