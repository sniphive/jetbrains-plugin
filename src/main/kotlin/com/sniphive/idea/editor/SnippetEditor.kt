package com.sniphive.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import com.intellij.ui.DocumentAdapter
import com.intellij.util.ui.JBUI
import com.sniphive.idea.models.Tag
import com.sniphive.idea.services.SnipHiveApiService
import com.sniphive.idea.services.SecureCredentialStorage
import org.jetbrains.annotations.NotNull
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.*

/**
 * FileEditor implementation for SnipHive snippets.
 * Provides a code editor with syntax highlighting based on the snippet's language.
 */
class SnippetEditor(
    private val project: Project,
    private val virtualFile: SnippetVirtualFile
) : FileEditor {

    private val LOG = Logger.getInstance(SnippetEditor::class.java)

    private val mainPanel = JPanel(BorderLayout())
    private var editor: Editor? = null

    // Toolbar components
    private val titleField = JTextField(virtualFile.title, 30)
    private val languageCombo = ComboBox(getSupportedLanguages())
    private val isPublicCheckbox = JCheckBox("Public", virtualFile.isPublic)
    private val isPinnedCheckbox = JCheckBox("Pinned", virtualFile.isPinned)
    private val saveButton = JButton("Save", AllIcons.Actions.MenuSaveall)
    private val statusLabel = JLabel()

    private val debouncedSaver = DebouncedSaver { saveSnippet() }
    private var isDirty = false

    init {
        setupToolbar()
        setupEditor()
        setupListeners()

        // Select the correct language
        languageCombo.selectedItem = virtualFile.language
    }

    private fun setupToolbar() {
        val toolbarPanel = JPanel()
        toolbarPanel.layout = BoxLayout(toolbarPanel, BoxLayout.Y_AXIS)
        toolbarPanel.border = JBUI.Borders.empty(5)

        // Title row
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        titleRow.add(JLabel("Title:"))
        titleRow.add(titleField)
        titleRow.add(Box.createHorizontalStrut(20))
        titleRow.add(JLabel("Language:"))
        languageCombo.preferredSize = Dimension(120, languageCombo.preferredSize.height)
        titleRow.add(languageCombo)
        titleRow.add(Box.createHorizontalStrut(20))
        titleRow.add(isPublicCheckbox)
        titleRow.add(isPinnedCheckbox)
        titleRow.add(Box.createHorizontalStrut(20))
        titleRow.add(saveButton)
        titleRow.add(statusLabel)

        // Tags row (simplified)
        val tagsRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        tagsRow.add(JLabel("Tags: ${virtualFile.tags.joinToString(", ") { it.name ?: "Unknown" }}"))

        toolbarPanel.add(titleRow)
        toolbarPanel.add(tagsRow)

        mainPanel.add(toolbarPanel, BorderLayout.NORTH)
    }

    private fun setupEditor() {
        // Get the actual language for syntax highlighting
        val language = getLanguageForFile()

        // Create document with the content
        val document = createDocument()

        // Create editor with proper language support
        editor = if (language != Language.ANY) {
            // Find the file type for the language
            val fileType = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByExtension(virtualFile.language)
            if (fileType != com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE) {
                EditorFactory.getInstance().createEditor(document, project, fileType, false)
            } else {
                EditorFactory.getInstance().createEditor(document, project)
            }
        } else {
            EditorFactory.getInstance().createEditor(document, project)
        }

        // Configure editor settings
        editor?.settings?.apply {
            isLineNumbersShown = true
            isLineMarkerAreaShown = true
            isFoldingOutlineShown = true
            isIndentGuidesShown = true
            isUseSoftWraps = false
        }

        // Set the editor component directly
        val editorComponent = editor?.component
        if (editorComponent != null) {
            mainPanel.add(editorComponent, BorderLayout.CENTER)
        }
    }

    private fun createDocument(): Document {
        // Create document from content
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument(virtualFile.snippetContent)
        document.setReadOnly(false)
        return document
    }

    private fun getLanguageForFile(): Language {
        // Map common languages to IntelliJ languages
        return when (virtualFile.language.lowercase()) {
            "kotlin" -> com.intellij.lang.Language.findLanguageByID("kotlin") ?: Language.ANY
            "java" -> com.intellij.lang.Language.findLanguageByID("JAVA") ?: Language.ANY
            "javascript", "js" -> com.intellij.lang.Language.findLanguageByID("JavaScript") ?: Language.ANY
            "typescript", "ts" -> com.intellij.lang.Language.findLanguageByID("TypeScript") ?: Language.ANY
            "python", "py" -> com.intellij.lang.Language.findLanguageByID("Python") ?: Language.ANY
            "go" -> com.intellij.lang.Language.findLanguageByID("go") ?: Language.ANY
            "rust" -> com.intellij.lang.Language.findLanguageByID("Rust") ?: Language.ANY
            "php" -> com.intellij.lang.Language.findLanguageByID("PHP") ?: Language.ANY
            "ruby", "rb" -> com.intellij.lang.Language.findLanguageByID("Ruby") ?: Language.ANY
            "swift" -> com.intellij.lang.Language.findLanguageByID("Swift") ?: Language.ANY
            "c" -> com.intellij.lang.Language.findLanguageByID("C") ?: Language.ANY
            "cpp", "c++" -> com.intellij.lang.Language.findLanguageByID("ObjectiveC") ?: Language.ANY
            "sql" -> com.intellij.lang.Language.findLanguageByID("SQL") ?: Language.ANY
            "html" -> com.intellij.lang.Language.findLanguageByID("HTML") ?: Language.ANY
            "css" -> com.intellij.lang.Language.findLanguageByID("CSS") ?: Language.ANY
            "json" -> com.intellij.lang.Language.findLanguageByID("JSON") ?: Language.ANY
            "yaml", "yml" -> com.intellij.lang.Language.findLanguageByID("yaml") ?: Language.ANY
            "markdown", "md" -> com.intellij.lang.Language.findLanguageByID("Markdown") ?: Language.ANY
            "shell", "bash", "sh" -> com.intellij.lang.Language.findLanguageByID("Bash") ?: Language.ANY
            else -> Language.ANY
        }
    }

    private fun setupListeners() {
        // Title changes
        titleField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                onTitleChanged()
            }
        })

        // Language changes
        languageCombo.addActionListener {
            virtualFile.language = languageCombo.selectedItem as String
            markDirty()
            debouncedSaver.onContentChanged()
        }

        // Checkbox changes
        isPublicCheckbox.addActionListener {
            virtualFile.isPublic = isPublicCheckbox.isSelected
            markDirty()
            debouncedSaver.onContentChanged()
        }

        isPinnedCheckbox.addActionListener {
            virtualFile.isPinned = isPinnedCheckbox.isSelected
            markDirty()
            debouncedSaver.onContentChanged()
        }

        // Save button
        saveButton.addActionListener {
            debouncedSaver.saveNow()
        }

        // Editor content changes
        editor?.document?.addDocumentListener(object : com.intellij.openapi.editor.event.DocumentListener {
            override fun documentChanged(event: com.intellij.openapi.editor.event.DocumentEvent) {
                virtualFile.snippetContent = editor?.document?.text ?: ""
                markDirty()
                debouncedSaver.onContentChanged()
            }
        })
    }

    private fun onTitleChanged() {
        virtualFile.title = titleField.text
        markDirty()
        debouncedSaver.onContentChanged()
    }

    private fun markDirty() {
        if (!isDirty) {
            isDirty = true
        }
    }

    private fun saveSnippet() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val settings = com.sniphive.idea.config.SnipHiveSettings.getInstance(project)
                val email = settings.getUserEmail()

                val title = virtualFile.title
                val plainContent = virtualFile.snippetContent
                val language = virtualFile.language
                val tagIds = virtualFile.tags.mapNotNull { it.id }

                // Check if user has E2EE enabled and ensure public key is available
                val securityStatus = apiService.getSecurityStatus(project)
                val userHasE2EE = securityStatus?.setupComplete == true

                // If E2EE is enabled, ensure we have the public key from API
                if (userHasE2EE && securityStatus?.e2eeProfile?.publicKeyJWK != null) {
                    val secureStorage = SecureCredentialStorage.getInstance()
                    val existingPublicKey = secureStorage.getPublicKey(project, email)
                    if (existingPublicKey.isNullOrEmpty()) {
                        // Save public key from API for encryption
                        val publicKeyJwk = securityStatus.e2eeProfile!!.publicKeyJWK.toString()
                        secureStorage.storePublicKey(project, email, publicKeyJwk)
                        LOG.info("Saved public key from API for user: $email")
                    }
                }

                // Encrypt content if E2EE is enabled or snippet is already encrypted
                val (content, encryptedDek) = if (userHasE2EE || virtualFile.isEncrypted()) {
                    val encrypted = E2EEContentService.encryptContentForSave(project, email, plainContent)
                    if (encrypted != null) {
                        Pair(encrypted.encryptedContent, encrypted.encryptedDek)
                    } else {
                        // E2EE required but encryption failed - show error
                        ApplicationManager.getApplication().invokeLater {
                            showError("E2EE encryption failed. Please set up E2EE in settings.")
                        }
                        return@executeOnPooledThread
                    }
                } else {
                    Pair(plainContent, null)
                }

                if (virtualFile.isNew) {
                    // Create new snippet
                    val snippet = apiService.createSnippet(
                        project = project,
                        title = title,
                        content = content,
                        language = language,
                        tags = tagIds,
                        encryptedDek = encryptedDek
                    )
                    if (snippet != null) {
                        // Update virtual file with server-generated data
                        virtualFile.snippetId = snippet.id
                        virtualFile.slug = snippet.slug ?: snippet.id
                        virtualFile.isNew = false

                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = "Saved"
                            statusLabel.foreground = JBColor.GREEN
                            isDirty = false
                        }
                    } else {
                        showError("Failed to create snippet")
                    }
                } else {
                    // Update existing snippet - use slug for API calls
                    val snippet = apiService.updateSnippet(
                        project = project,
                        snippetSlug = virtualFile.slug,
                        title = title,
                        content = content,
                        language = language,
                        tags = tagIds,
                        encryptedDek = encryptedDek,
                        isPublic = virtualFile.isPublic
                    )
                    if (snippet != null) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = "Saved"
                            statusLabel.foreground = JBColor.GREEN
                            isDirty = false
                        }
                    } else {
                        showError("Failed to save snippet")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to save snippet", e)
                showError("Error: ${e.message}")
            }
        }
    }

    private fun showError(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = message
            statusLabel.foreground = JBColor.RED
        }
    }

    private fun getSupportedLanguages(): Array<String> {
        return arrayOf(
            "text", "kotlin", "java", "javascript", "typescript", "python", "go", "rust",
            "php", "ruby", "swift", "c", "cpp", "sql", "html", "css", "json", "yaml",
            "markdown", "shell", "bash"
        )
    }

    // FileEditor interface implementation

    override fun getComponent(): JComponent = mainPanel

    override fun getPreferredFocusedComponent(): JComponent? = editor?.contentComponent

    override fun getName(): String = "Snippet Editor"

    override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = isDirty

    override fun isValid(): Boolean = true

    override fun getFile(): VirtualFile = virtualFile

    override fun selectNotify() {}

    override fun deselectNotify() {}

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getCurrentLocation(): FileEditorLocation? = null

    // UserDataHolder implementation
    private val userDataHolder = UserDataHolderBase()

    override fun <T : Any?> getUserData(key: com.intellij.openapi.util.Key<T>): T? {
        return userDataHolder.getUserData(key)
    }

    override fun <T : Any?> putUserData(key: com.intellij.openapi.util.Key<T>, value: T?) {
        userDataHolder.putUserData(key, value)
    }

    override fun dispose() {
        debouncedSaver.dispose()
        editor?.let {
            EditorFactory.getInstance().releaseEditor(it)
        }
    }
}