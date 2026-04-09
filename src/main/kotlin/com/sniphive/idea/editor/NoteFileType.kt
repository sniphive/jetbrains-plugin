package com.sniphive.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

/**
 * FileType for SnipHive notes opened in the editor.
 * Uses Markdown syntax highlighting.
 */
object NoteFileType : LanguageFileType(NoteLanguage) {
    override fun getName(): String = "SnipHive Note"
    override fun getDescription(): String = "SnipHive markdown note"
    override fun getDefaultExtension(): String = "sniphive-note"
    override fun getIcon(): Icon = AllIcons.FileTypes.Text
    override fun isReadOnly(): Boolean = false
}

/**
 * Language for notes - Markdown-based.
 */
object NoteLanguage : com.intellij.lang.Language("SnipHiveNote", "text/markdown") {
    override fun getDisplayName(): @NlsSafe String = "SnipHive Note"
}