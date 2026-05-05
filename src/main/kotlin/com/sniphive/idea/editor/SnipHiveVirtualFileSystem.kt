package com.sniphive.idea.editor

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.IOException

/**
 * Non-physical VFS for in-memory SnipHive editor files.
 */
object SnipHiveVirtualFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "sniphive"

    override fun findFileByPath(path: String): VirtualFile? = null

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null

    override fun refresh(asynchronous: Boolean) {}

    override fun addVirtualFileListener(listener: VirtualFileListener) {}

    override fun removeVirtualFileListener(listener: VirtualFileListener) {}

    @Throws(IOException::class)
    override fun deleteFile(requestor: Any?, vFile: VirtualFile) {
        throw IOException("SnipHive virtual files cannot be deleted through VFS")
    }

    @Throws(IOException::class)
    override fun moveFile(requestor: Any?, vFile: VirtualFile, newParent: VirtualFile) {
        throw IOException("SnipHive virtual files cannot be moved through VFS")
    }

    @Throws(IOException::class)
    override fun renameFile(requestor: Any?, vFile: VirtualFile, newName: String) {
        throw IOException("SnipHive virtual files cannot be renamed through VFS")
    }

    @Throws(IOException::class)
    override fun createChildFile(requestor: Any?, vDir: VirtualFile, fileName: String): VirtualFile {
        throw IOException("SnipHive virtual files cannot create children")
    }

    @Throws(IOException::class)
    override fun createChildDirectory(requestor: Any?, vDir: VirtualFile, dirName: String): VirtualFile {
        throw IOException("SnipHive virtual files cannot create directories")
    }

    @Throws(IOException::class)
    override fun copyFile(requestor: Any?, virtualFile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw IOException("SnipHive virtual files cannot be copied through VFS")
    }

    override fun isReadOnly(): Boolean = false
}
