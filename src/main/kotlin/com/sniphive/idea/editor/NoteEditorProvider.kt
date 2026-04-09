package com.sniphive.idea.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * FileEditorProvider for SnipHive notes.
 * Creates editors with Markdown syntax highlighting.
 */
class NoteEditorProvider : FileEditorProvider, DumbAware {

    override fun getEditorTypeId(): String = "sniphive-note-editor"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is NoteVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return NoteEditor(project, file as NoteVirtualFile)
    }
}