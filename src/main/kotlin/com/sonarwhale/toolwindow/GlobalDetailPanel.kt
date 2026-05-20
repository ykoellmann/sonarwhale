package com.sonarwhale.toolwindow

import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.script.ScriptLevel
import java.awt.BorderLayout
import javax.swing.JPanel

class GlobalDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stateService = SonarwhaleStateService.getInstance(project)

    private val configPanel = HierarchyConfigPanel(
        project = project,
        config = stateService.getGlobalConfig().config,
        onSave = { updated ->
            stateService.setGlobalConfig(stateService.getGlobalConfig().copy(config = updated))
        },
        scriptContext = ScriptContext(level = ScriptLevel.GLOBAL)
    )

    init {
        add(configPanel, BorderLayout.CENTER)
    }

    fun refresh() {
        configPanel.setConfig(stateService.getGlobalConfig().config)
    }
}
