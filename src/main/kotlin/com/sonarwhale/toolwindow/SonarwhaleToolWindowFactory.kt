package com.sonarwhale.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.sonarwhale.service.CollectionService
import com.sonarwhale.service.RouteIndexService

class SonarwhaleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Panel may have been created eagerly at startup so the run-request listener
        // is active before the user opens the tool window for the first time.
        if (toolWindow.contentManager.contentCount > 0) return

        if (CollectionService.isInitialized(project)) {
            initContent(project, toolWindow)
        } else {
            initBlankContent(project, toolWindow)
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun isApplicable(project: Project): Boolean = true

    // Explicit overrides to avoid Kotlin-generated bridge methods that call deprecated/
    // experimental interface defaults — anchor and icon are declared in plugin.xml.
    @Suppress("OVERRIDE_DEPRECATION")
    override val isDoNotActivateOnStart: Boolean = false
    @Suppress("UnstableApiUsage")
    override val anchor: ToolWindowAnchor? = null
    @Suppress("UnstableApiUsage")
    override val icon: javax.swing.Icon? = null
    @Suppress("UnstableApiUsage")
    override suspend fun manage(toolWindow: ToolWindow, toolWindowManager: ToolWindowManager) {}

    companion object {
        /** Shows the normal endpoint panel. Called after initialization. */
        fun initContent(project: Project, toolWindow: ToolWindow) {
            val service = RouteIndexService.getInstance(project)
            val panel   = SonarwhalePanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            toolWindow.contentManager.addContent(content)
            panel.updateEndpoints(service.endpoints)
            service.refresh()
        }

        /** Shows the init panel when the project has not yet been set up. */
        fun initBlankContent(project: Project, toolWindow: ToolWindow) {
            val panel   = SonarwhaleInitPanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            toolWindow.contentManager.addContent(content)
        }
    }
}
