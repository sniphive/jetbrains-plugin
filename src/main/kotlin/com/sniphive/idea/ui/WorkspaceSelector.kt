package com.sniphive.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sniphive.idea.config.SnipHiveSettings
import com.sniphive.idea.models.Workspace
import com.sniphive.idea.services.NoteLookupService
import com.sniphive.idea.services.SnippetLookupService
import com.sniphive.idea.services.SnipHiveApiService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ActionEvent
import javax.swing.*

/**
 * Listener for workspace changes.
 */
interface WorkspaceChangeListener {
    fun onWorkspaceChanged(workspace: Workspace)
}

/**
 * Workspace selector dropdown component.
 *
 * Features:
 * - Dropdown list of all user workspaces
 * - Shows current workspace
 * - Refreshes content when workspace changes
 */
class WorkspaceSelector(private val project: Project) : JPanel(BorderLayout()) {

    companion object {
        private val LOG = Logger.getInstance(WorkspaceSelector::class.java)
    }

    private val workspaceComboBox = JComboBox<Workspace>()
    private val refreshButton = JButton("↻")
    private val loadingLabel = JBLabel("Loading...")

    private var workspaces: List<Workspace> = emptyList()
    private var isLoading = false
    private val listeners = mutableListOf<WorkspaceChangeListener>()

    init {
        border = JBUI.Borders.empty(5)

        // Configure combo box
        workspaceComboBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): JComponent {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                if (value is Workspace) {
                    label.text = "${value.name} (${value.getDisplayRole()})"
                    label.toolTipText = "Type: ${value.type ?: "Unknown"} | Role: ${value.role ?: "Member"}"
                }
                return label
            }
        }

        workspaceComboBox.addActionListener { e ->
            if (e.actionCommand == "comboBoxChanged") {
                val selected = workspaceComboBox.selectedItem as? Workspace
                if (selected != null) {
                    onWorkspaceSelected(selected)
                }
            }
        }

        // Refresh button
        refreshButton.toolTipText = "Refresh workspaces"
        refreshButton.preferredSize = Dimension(30, 30)
        refreshButton.addActionListener {
            loadWorkspaces()
        }

        // Layout
        add(JBLabel("Workspace: "), BorderLayout.WEST)
        add(workspaceComboBox, BorderLayout.CENTER)
        add(refreshButton, BorderLayout.EAST)

        // Don't auto-load workspaces - will be loaded after login
    }

    /**
     * Load workspaces from API.
     */
    fun loadWorkspaces() {
        if (isLoading) return

        isLoading = true
        refreshButton.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiService = SnipHiveApiService.getInstance()
                val loadedWorkspaces = apiService.getWorkspaces(project)

                ApplicationManager.getApplication().invokeLater {
                    workspaces = loadedWorkspaces
                    updateComboBox()
                    isLoading = false
                    refreshButton.isEnabled = true
                }
            } catch (e: Exception) {
                LOG.error("Failed to load workspaces", e)
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    refreshButton.isEnabled = true
                }
            }
        }
    }

    private fun updateComboBox() {
        workspaceComboBox.removeAllItems()

        workspaces.sortedBy { it.name }.forEach { workspace ->
            workspaceComboBox.addItem(workspace)
        }

        // Auto-select workspace - this will trigger ActionListener which calls onWorkspaceSelected
        if (workspaceComboBox.itemCount > 0) {
            val settings = SnipHiveSettings.getInstance()
            val savedWorkspaceId = settings.getWorkspaceId()

            val workspaceToSelect = if (savedWorkspaceId.isNotEmpty()) {
                // Match by UUID first, then by ID for backward compatibility
                workspaces.find { it.uuid == savedWorkspaceId || it.id == savedWorkspaceId } ?: workspaces.firstOrNull()
            } else {
                workspaces.firstOrNull()
            }

            if (workspaceToSelect != null) {
                // This will trigger the ActionListener which calls onWorkspaceSelected -> refresh
                workspaceComboBox.selectedItem = workspaceToSelect
            }
        }
    }

    private fun onWorkspaceSelected(workspace: Workspace) {
        LOG.info("Workspace selected: ${workspace.name}")

        // Save to settings - use UUID for API calls
        val settings = SnipHiveSettings.getInstance()
        settings.setWorkspaceId(workspace.uuid ?: workspace.id)

        // Refresh snippets and notes
        SnippetLookupService.getInstance(project).refreshSnippets()
        NoteLookupService.getInstance(project).refreshNotes()

        // Notify listeners
        listeners.forEach { it.onWorkspaceChanged(workspace) }
    }

    /**
     * Get the currently selected workspace.
     */
    fun getSelectedWorkspace(): Workspace? {
        return workspaceComboBox.selectedItem as? Workspace
    }

    /**
     * Set the selected workspace by ID.
     */
    fun setSelectedWorkspace(workspaceId: String) {
        val workspace = workspaces.find { it.id == workspaceId || it.uuid == workspaceId }
        if (workspace != null) {
            workspaceComboBox.selectedItem = workspace
        }
    }

    /**
     * Add a workspace change listener.
     */
    fun addWorkspaceChangeListener(listener: WorkspaceChangeListener) {
        listeners.add(listener)
    }

    /**
     * Remove a workspace change listener.
     */
    fun removeWorkspaceChangeListener(listener: WorkspaceChangeListener) {
        listeners.remove(listener)
    }
}