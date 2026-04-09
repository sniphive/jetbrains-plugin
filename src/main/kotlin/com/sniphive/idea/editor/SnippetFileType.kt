package com.sniphive.idea.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.NlsSafe
import javax.swing.Icon

/**
 * FileType for SnipHive snippets opened in the editor.
 * Uses dynamic syntax highlighting based on the snippet's programming language.
 */
object SnippetFileType : LanguageFileType(SnippetLanguage) {
    override fun getName(): String = "SnipHive Snippet"
    override fun getDescription(): String = "SnipHive code snippet"
    override fun getDefaultExtension(): String = "sniphive-snippet"
    override fun getIcon(): Icon = AllIcons.FileTypes.Java // Will be replaced dynamically
    override fun isReadOnly(): Boolean = false
}

/**
 * Dynamic language for snippets that delegates to the actual language.
 */
object SnippetLanguage : com.intellij.lang.Language("SnipHiveSnippet") {
    override fun getDisplayName(): @NlsSafe String = "SnipHive Snippet"
}