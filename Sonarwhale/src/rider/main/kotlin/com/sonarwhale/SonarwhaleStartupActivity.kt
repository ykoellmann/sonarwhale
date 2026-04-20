package com.sonarwhale

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ex.ProjectRootManagerEx
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.service.RouteIndexService

class SonarwhaleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter service so it can register its editor listeners
        SonarwhaleGutterService.getInstance(project)
        RouteIndexService.getInstance(project).refresh()

        val scriptService = SonarwhaleScriptService.getInstance(project)
        val scriptsRoot = scriptService.getScriptsRoot()
        val alreadyExisted = scriptsRoot.toFile().exists()
        scriptService.ensureSwDts()

        if (!alreadyExisted) {
            // First run: directory was just created. Refresh VFS so IntelliJ discovers
            // the new library root and indexes sw.d.ts for autocomplete without restart.
            LocalFileSystem.getInstance().refreshNioFiles(listOf(scriptsRoot), true, false, null)
            ApplicationManager.getApplication().invokeLater {
                ApplicationManager.getApplication().runWriteAction {
                    ProjectRootManagerEx.getInstanceEx(project)
                        .makeRootsChange(Runnable {}, false, true)
                }
            }
        }
    }
}
