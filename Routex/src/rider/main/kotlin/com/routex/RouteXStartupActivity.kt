package com.routex

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.routex.gutter.RouteXGutterService
import com.routex.service.RouteIndexService

class RouteXStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter service so it can register its editor listeners
        RouteXGutterService.getInstance(project)
        RouteIndexService.getInstance(project).refresh()
    }
}
