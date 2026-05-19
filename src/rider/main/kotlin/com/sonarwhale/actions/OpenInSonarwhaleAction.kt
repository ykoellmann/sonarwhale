package com.sonarwhale.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.gutter.SourceLocationService
import com.sonarwhale.service.RouteIndexService

class OpenInSonarwhaleAction : AnAction("Open in Sonarwhale"), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project   = e.project ?: return
        val file      = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val caretLine = e.getData(CommonDataKeys.EDITOR)?.caretModel?.logicalPosition?.line ?: 0

        val locService = SourceLocationService.getInstance(project)
        val service    = RouteIndexService.getInstance(project)

        val candidates = service.endpoints
            .mapNotNull { ep -> locService.get(ep.id)?.let { loc -> Pair(ep, loc) } }
            .filter { (_, loc) -> loc.file == file }

        // Prefer the endpoint whose declaration line is at or just above the caret
        val match = candidates
            .filter { (_, loc) -> loc.line <= caretLine }
            .maxByOrNull { (_, loc) -> loc.line }
            ?: candidates.minByOrNull { (_, loc) -> loc.line }
            ?: return

        ToolWindowManager.getInstance(project).getToolWindow("Sonarwhale")?.show(null)
        service.selectEndpoint(match.first.id)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val ext = e.getData(CommonDataKeys.VIRTUAL_FILE)?.extension?.lowercase()
        val supported = project != null && ext != null &&
            SonarwhaleGutterService.getInstance(project).isSupported(ext)
        e.presentation.isVisible = supported
        e.presentation.isEnabled = supported
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
