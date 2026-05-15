package com.sonarwhale.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.toolwindow.SonarwhaleInitPanel
import com.sonarwhale.toolwindow.SonarwhaleToolWindowFactory
import java.io.File
import java.nio.file.Path

/**
 * Central service for initializing, deactivating, and resetting Sonarwhale in a project.
 *
 * - initProject()   — creates default config, scripts dir, module root, refreshes endpoints,
 *                     switches tool window to the normal panel.
 * - deactivateProject() — deletes .idea/sonarwhale/ (gitignored), clears in-memory state,
 *                         switches tool window back to the init panel.
 * - resetProject()  — deletes both .idea/sonarwhale/ and .sonarwhale/ (scripts), same as above.
 */
@Service(Service.Level.PROJECT)
class SonarwhaleInitService(private val project: Project) {

    fun initProject() {
        // 1. Create default collection on disk (fast — just writes a small JSON)
        CollectionService.getInstance(project).createDefault()

        // 2–4: Heavy I/O runs off the EDT; UI switch is guaranteed via try/finally
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val scriptService = SonarwhaleScriptService.getInstance(project)
                scriptService.ensureSwDts()
                val scriptsRoot = scriptService.getScriptsRoot()
                LocalFileSystem.getInstance().refreshNioFiles(listOf(scriptsRoot), true, false, null)
                ensureIndexed(scriptsRoot)
                RouteIndexService.getInstance(project).refresh()
            } finally {
                // 5. Switch to normal panel — always runs, even on exception
                ApplicationManager.getApplication().invokeLater {
                    val toolWindow = ToolWindowManager.getInstance(project)
                        .getToolWindow("Sonarwhale") ?: return@invokeLater
                    toolWindow.contentManager.removeAllContents(true)
                    SonarwhaleToolWindowFactory.initContent(project, toolWindow)
                }
            }
        }
    }

    /**
     * Deactivates Sonarwhale for this project.
     * Deletes .idea/sonarwhale/ (local config — gitignored).
     * Leaves .sonarwhale/ (scripts) untouched.
     */
    fun deactivateProject() {
        File(project.basePath ?: return, ".idea/sonarwhale").deleteRecursively()
        clearServicesAndSwitchToInitPanel()
    }

    /**
     * Full reset — deletes both .idea/sonarwhale/ and .sonarwhale/.
     * The .sonarwhale/ directory may be committed to git; callers must confirm before calling.
     */
    fun resetProject() {
        File(project.basePath ?: return, ".idea/sonarwhale").deleteRecursively()
        File(project.basePath ?: return, ".sonarwhale").deleteRecursively()
        clearServicesAndSwitchToInitPanel()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun clearServicesAndSwitchToInitPanel() {
        CollectionService.getInstance(project).clear()
        RouteIndexService.getInstance(project).clear()  // gutter icons cleared via listener

        ApplicationManager.getApplication().invokeLater {
            val toolWindow = ToolWindowManager.getInstance(project)
                .getToolWindow("Sonarwhale") ?: return@invokeLater
            toolWindow.contentManager.removeAllContents(true)
            val panel   = SonarwhaleInitPanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            toolWindow.contentManager.addContent(content)
        }
    }

    private fun ensureIndexed(scriptsRoot: Path) {
        val scriptsUrl = com.intellij.openapi.vfs.VfsUtil.pathToUrl(scriptsRoot.toString())
        val module = ModuleManager.getInstance(project).modules.firstOrNull() ?: return
        val rootManager = ModuleRootManager.getInstance(module)
        if (rootManager.contentEntries.any { it.url == scriptsUrl }) return
        WriteAction.runAndWait<Exception> {
            val model = rootManager.modifiableModel
            model.addContentEntry(scriptsUrl)
            model.commit()
        }
    }

    companion object {
        fun getInstance(project: Project): SonarwhaleInitService = project.service()
    }
}
