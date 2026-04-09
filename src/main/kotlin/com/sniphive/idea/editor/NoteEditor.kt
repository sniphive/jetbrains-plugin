package com.sniphive.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.lang.Language
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import javax.swing.*

/**
 * FileEditor implementation for SnipHive notes.
 * Provides an editor with Markdown syntax highlighting.
 */
class NoteEditor(
    private val project: Project,
    private val virtualFile: NoteVirtualFile
) : FileEditor {

    private val LOG = Logger.getInstance(NoteEditor::class.java)

    private val mainPanel = JPanel(BorderLayout())
    private var editor: Editor? = null
    private var editorTextField: EditorTextField? = null

    // Toolbar components
    private val titleField = JTextField(virtualFile.title, 30)
    private val isPublicCheckbox = JCheckBox("Public", virtualFile.isPublic)
    private val isPinnedCheckbox = JCheckBox("Pinned", virtualFile.isPinned)
    private val saveButton = JButton("Save", AllIcons.Actions.MenuSaveall)
    private val statusLabel = JLabel()

    private val debouncedSaver = DebouncedSaver { saveNote() }
    private var isDirty = false

    init {
        setupToolbar()
        setupEditor()
        setupListeners()
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
        titleRow.add(isPublicCheckbox)
        titleRow.add(isPinnedCheckbox)
        titleRow.add(Box.createHorizontalStrut(20))
        titleRow.add(saveButton)
        titleRow.add(statusLabel)

        // Tags row
        val tagsRow = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        tagsRow.add(JLabel("Tags: ${virtualFile.tags.joinToString(", ") { it.name ?: "Unknown" }}"))

        toolbarPanel.add(titleRow)
        toolbarPanel.add(tagsRow)

        mainPanel.add(toolbarPanel, BorderLayout.NORTH)
    }

    private fun setupEditor() {
        // Create editor with the content
        val document = createDocument()
        editor = EditorFactory.getInstance().createEditor(document, project)

        // Configure editor
        editor?.settings?.isLineNumbersShown = true
        editor?.settings?.isLineMarkerAreaShown = true
        editor?.settings?.isFoldingOutlineShown = true

        // Create EditorTextField for embedding with Markdown highlighting
        editorTextField = EditorTextField(document, project, NoteFileType)
        editorTextField?.setOneLineMode(false)
        editorTextField?.preferredSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        mainPanel.add(editorTextField!!, BorderLayout.CENTER)
    }

    private fun createDocument(): Document {
        val factory = EditorFactory.getInstance()
        val document = factory.createDocument(virtualFile.noteContent)
        document.setReadOnly(false)
        return document
    }

    private fun getMarkdownLanguage(): Language {
        return com.intellij.lang.Language.findLanguageByID("Markdown")
            ?: com.intellij.lang.Language.findLanguageByID("markdown")
            ?: Language.ANY
    }

    private fun setupListeners() {
        // Title changes
        titleField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onTitleChanged()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onTitleChanged()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onTitleChanged()
        })

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
                virtualFile.noteContent = editor?.document?.text ?: ""
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

    private fun saveNote() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val settings = com.sniphive.idea.config.SnipHiveSettings.getInstance(project)
                val email = settings.getUserEmail()

                val title = virtualFile.title
                val plainContent = virtualFile.noteContent
                val tagIds = virtualFile.tags.mapNotNull { it.id }

                // Encrypt content if E2EE is enabled
                val (content, encryptedDek) = if (virtualFile.isEncrypted() || virtualFile.isNew) {
                    val encrypted = E2EEContentService.encryptContentForSave(project, email, plainContent)
                    if (encrypted != null) {
                        Pair(encrypted.encryptedContent, encrypted.encryptedDek)
                    } else {
                        Pair(plainContent, null)
                    }
                } else {
                    Pair(plainContent, null)
                }

                if (virtualFile.isNew) {
                    // Create new note
                    val note = apiService.createNote(
                        project = project,
                        title = title,
                        content = content,
                        tags = tagIds,
                        encryptedDek = encryptedDek
                    )
                    if (note != null) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = "Saved"
                            statusLabel.foreground = JBColor.GREEN
                            isDirty = false
                        }
                    } else {
                        showError("Failed to create note")
                    }
                } else {
                    // Update existing note
                    val note = apiService.updateNote(
                        project = project,
                        noteSlug = virtualFile.slug,
                        title = title,
                        content = content,
                        tags = tagIds,
                        encryptedDek = encryptedDek,
                        isPublic = virtualFile.isPublic
                    )
                    if (note != null) {
                        ApplicationManager.getApplication().invokeLater {
                            statusLabel.text = "Saved"
                            statusLabel.foreground = JBColor.GREEN
                            isDirty = false
                        }
                    } else {
                        showError("Failed to save note")
                    }
                }
            } catch (e: Exception) {
                LOG.error("Failed to save note", e)
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

    // FileEditor interface implementation

    override fun getComponent(): JComponent = mainPanel

    override fun getPreferredFocusedComponent(): JComponent? = editorTextField

    override fun getName(): String = "Note Editor"

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
    private val userDataHolder = com.intellij.openapi.util.UserDataHolderBase()

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