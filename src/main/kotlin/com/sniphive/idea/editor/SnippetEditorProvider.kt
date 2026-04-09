package com.sniphive.idea.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * FileEditorProvider for SnipHive snippets.
 * Creates custom editors with syntax highlighting based on the snippet's language.
 */
class SnippetEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "sniphive-snippet-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is SnippetVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return SnippetEditor(project, file as SnippetVirtualFile)
    }
}