package com.sniphive.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.fileTypes.FileType
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Tag
import java.io.OutputStream

/**
 * Simple in-memory VirtualFile for SnipHive snippets.
 */
class SnippetVirtualFile : VirtualFile {

    var snippetId: String = ""
    var slug: String = ""  // Used for API calls
    var title: String = "New Snippet"
    var snippetContent: String = ""  // Decrypted content for editing
    var language: String = "text"
    var tags: List<Tag> = emptyList()
    var isPublic: Boolean = false
    var isPinned: Boolean = false
    var isFavorite: Boolean = false
    var isNew: Boolean = true

    // E2EE fields
    var encryptedDek: String? = null  // Encrypted DEK from API
    var originalEncryptedContent: String? = null  // Original encrypted content (for comparison)

    constructor(snippet: Snippet, decryptedContent: String = snippet.content ?: "") {
        snippetId = snippet.id
        slug = snippet.slug ?: snippet.id
        title = snippet.title
        snippetContent = decryptedContent
        language = snippet.language ?: "text"
        tags = snippet.tags ?: emptyList()
        isPublic = snippet.isPublic ?: false
        isPinned = snippet.isPinned ?: false
        isFavorite = snippet.isFavorite ?: false
        encryptedDek = snippet.encryptedDek
        originalEncryptedContent = snippet.content
        isNew = false
    }

    constructor()

    /**
     * Check if this snippet was encrypted.
     */
    fun isEncrypted(): Boolean = !encryptedDek.isNullOrEmpty()

    override fun getName(): String = title

    override fun getFileSystem(): VirtualFileSystem = SnipHiveVirtualFileSystem

    override fun getFileType(): FileType = SnippetFileType

    override fun getPath(): String = "sniphive://snippet/$snippetId"

    override fun isWritable(): Boolean = true

    override fun isDirectory(): Boolean = false

    override fun isValid(): Boolean = true

    override fun getParent(): VirtualFile? = null

    override fun getChildren(): Array<VirtualFile>? = null

    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        return java.io.ByteArrayOutputStream()
    }

    override fun getInputStream(): java.io.InputStream {
        return java.io.ByteArrayInputStream(snippetContent.toByteArray())
    }

    override fun contentsToByteArray(): ByteArray = snippetContent.toByteArray()

    override fun getTimeStamp(): Long = System.currentTimeMillis()

    override fun getLength(): Long = snippetContent.length.toLong()

    override fun getModificationStamp(): Long = System.currentTimeMillis()

    override fun refresh(asynchronous: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
}
