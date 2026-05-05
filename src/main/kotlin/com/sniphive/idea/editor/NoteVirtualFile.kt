package com.sniphive.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.fileTypes.FileType
import com.sniphive.idea.models.Note
import com.sniphive.idea.models.Tag
import java.io.OutputStream

/**
 * Simple in-memory VirtualFile for SnipHive notes.
 */
class NoteVirtualFile : VirtualFile {

    var noteId: String = ""
    var slug: String = ""  // Used for API calls
    var title: String = "New Note"
    var noteContent: String = ""  // Decrypted content for editing
    var tags: List<Tag> = emptyList()
    var isPublic: Boolean = false
    var isPinned: Boolean = false
    var isFavorite: Boolean = false
    var isNew: Boolean = true

    // E2EE fields
    var encryptedDek: String? = null  // Encrypted DEK from API
    var originalEncryptedContent: String? = null  // Original encrypted content

    constructor(note: Note, decryptedContent: String = note.content ?: "") {
        noteId = note.id
        slug = note.slug ?: note.id
        title = note.title
        noteContent = decryptedContent
        tags = note.tags ?: emptyList()
        isPublic = note.isPublic ?: false
        isPinned = note.isPinned ?: false
        isFavorite = note.isFavorite ?: false
        encryptedDek = note.encryptedDek
        originalEncryptedContent = note.content
        isNew = false
    }

    constructor()

    /**
     * Check if this note was encrypted.
     */
    fun isEncrypted(): Boolean = !encryptedDek.isNullOrEmpty()

    override fun getName(): String = title

    override fun getFileSystem(): VirtualFileSystem = SnipHiveVirtualFileSystem

    override fun getFileType(): FileType = NoteFileType

    override fun getPath(): String = "sniphive://note/$noteId"

    override fun isWritable(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile>? = null

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        return java.io.ByteArrayOutputStream()
    }

    override fun getInputStream(): java.io.InputStream {
        return java.io.ByteArrayInputStream(noteContent.toByteArray())
    }

    override fun contentsToByteArray(): ByteArray = noteContent.toByteArray()

    override fun getTimeStamp(): Long = System.currentTimeMillis()

    override fun getModificationStamp(): Long = System.currentTimeMillis()

    override fun getLength(): Long = noteContent.length.toLong()

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
}
