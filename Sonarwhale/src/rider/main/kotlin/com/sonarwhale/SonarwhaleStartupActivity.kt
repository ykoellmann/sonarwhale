package com.sonarwhale

import com.intellij.openapi.project.Project
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
        scriptService.ensureSwDts()
        // Refresh VFS so IntelliJ discovers tsconfig.json and sw.d.ts immediately
        LocalFileSystem.getInstance().refreshNioFiles(
            listOf(scriptService.getScriptsRoot()), true, false, null
        )
    }
}
