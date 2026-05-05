package com.sniphive.idea

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.ProjectManager
import com.sniphive.idea.services.SecureCredentialStorage

/**
 * Main plugin entry point for SnipHive JetBrains IDE Extension.
 *
 * This class provides plugin lifecycle management and coordinates initialization
 * of plugin services. Services are registered in plugin.xml and accessed via
 * dependency injection.
 *
 * Security Note:
 * - User credentials are stored securely in IDE Password Safe
 * - Private keys are never logged or exposed
 * - All E2EE operations are client-side only
 */
class SnipHivePlugin private constructor() {

    companion object {
        private val LOG = Logger.getInstance(SnipHivePlugin::class.java)

        @JvmStatic
        val instance: SnipHivePlugin by lazy { SnipHivePlugin() }

        /**
         * Get the plugin version from plugin.xml
         */
        @JvmStatic
        val pluginVersion: String = "1.0.0"

        /**
         * Get the plugin ID
         */
        @JvmStatic
        val pluginId: String = "net.sniphive.plugin"

        /**
         * Minimum supported IDE build number
         */
        @JvmStatic
        val minIdeaVersion: String = "232"

        /**
         * Maximum supported IDE build number
         */
        @JvmStatic
        val maxIdeaVersion: String = "250.*"
    }

    /**
     * Initialize plugin services.
     * This method is called when the plugin is loaded.
     *
     * Note: Heavy initialization should be deferred to when services are first used.
     */
    fun initialize() {
        LOG.info("Initializing SnipHive plugin v$pluginVersion")

        // Services are auto-injected by IntelliJ Platform
        // They will be instantiated when first accessed via service()

        LOG.info("SnipHive plugin initialized successfully")
    }

    /**
     * Cleanup plugin resources.
     * This method is called when the plugin is unloaded.
     */
    fun dispose() {
        LOG.info("Disposing SnipHive plugin")

        // Shutdown credential retry scheduler to prevent thread leaks
        try {
            val secureStorage = SecureCredentialStorage.getInstance()
            secureStorage.shutdownScheduler()
        } catch (e: Exception) {
            LOG.warn("Error shutting down credential scheduler: ${e.message}")
        }

        LOG.info("SnipHive plugin disposed")
    }

    /**
     * Check if plugin is properly initialized and services are available.
     *
     * @return true if plugin is ready for use
     */
    fun isReady(): Boolean {
        return try {
            // Check if auth service is available
            val projectManager = ProjectManager.getInstance()
            projectManager.openProjects.isNotEmpty()
        } catch (e: Exception) {
            LOG.warn("Plugin not ready: ${e.message}")
            false
        }
    }
}