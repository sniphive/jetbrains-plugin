package com.sniphive.idea.status

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnippetCacheListener
import com.sniphive.idea.services.NoteCacheListener
import com.sniphive.idea.models.Snippet
import com.sniphive.idea.models.Note
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.UIManager

/**
 * Status bar widget that shows SnipHive status.
 *
 * Displays:
 * - Current workspace
 * - Snippet count
 * - Note count
 *
 * Click action: Open SnipHive tool window
 */
class SnipHiveStatusBarWidget(private val project: Project) : StatusBarWidget {

    companion object {
        private val LOG = Logger.getInstance(SnipHiveStatusBarWidget::class.java)
        const val ID = "SnipHiveStatusBar"
    }

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation {
        return object : StatusBarWidget.TextPresentation {
            override fun getText(): String {
                val settings = SnipHiveSettings.getInstance(project)
                val snippetService = SnippetLookupService.getInstance(project)
                val noteService = NoteLookupService.getInstance(project)

                val workspaceName = if (settings.isLoggedIn()) {
                    // Try to get workspace name from selector
                    "Workspace"
                } else {
                    "Not logged in"
                }

                val snippetCount = snippetService.getCacheSize()
                val noteCount = noteService.getCacheSize()

                return if (settings.isLoggedIn()) {
                    "SnipHive: $snippetCount snippets, $noteCount notes"
                } else {
                    "SnipHive: Not logged in"
                }
            }

            override fun getTooltipText(): String {
                val settings = SnipHiveSettings.getInstance(project)
                val snippetService = SnippetLookupService.getInstance(project)
                val noteService = NoteLookupService.getInstance(project)

                return if (settings.isLoggedIn()) {
                    """
                    SnipHive Status
                    User: ${settings.getUserEmail()}
                    Snippets: ${snippetService.getCacheSize()}
                    Notes: ${noteService.getCacheSize()}
                    Click to open tool window
                    """.trimIndent()
                } else {
                    "Click to login to SnipHive"
                }
            }

            override fun getAlignment(): Float = 0.5f

            override fun getClickConsumer(): Consumer<MouseEvent>? {
                return Consumer { event ->
                    // Open SnipHive tool window
                    val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                    val toolWindow = toolWindowManager.getToolWindow("SnipHive")
                    toolWindow?.activate(null)
                }
            }
        }
    }

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar

        // Subscribe to cache changes to update widget
        val bus = ApplicationManager.getApplication().messageBus.connect()

        bus.subscribe(SnippetLookupService.SNIPPET_CACHE_TOPIC, object : SnippetCacheListener {
            override fun onRefreshStarted() {
                updateWidget()
            }

            override fun onSnippetsRefreshed(snippets: List<Snippet>) {
                updateWidget()
            }

            override fun onRefreshFailed(error: String) {
                updateWidget()
            }
        })

        bus.subscribe(NoteLookupService.NOTE_CACHE_TOPIC, object : NoteCacheListener {
            override fun onRefreshStarted() {
                updateWidget()
            }

            override fun onNotesRefreshed(notes: List<Note>) {
                updateWidget()
            }

            override fun onRefreshFailed(error: String) {
                updateWidget()
            }
        })

        LOG.debug("SnipHive status bar widget installed")
    }

    override fun dispose() {
        statusBar = null
    }

    private fun updateWidget() {
        ApplicationManager.getApplication().invokeLater {
            statusBar?.updateWidget(ID)
        }
    }
}

/**
 * Factory for creating SnipHive status bar widgets.
 */
class SnipHiveStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = SnipHiveStatusBarWidget.ID

    override fun getDisplayName(): String = "SnipHive Status"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return SnipHiveStatusBarWidget(project)
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}