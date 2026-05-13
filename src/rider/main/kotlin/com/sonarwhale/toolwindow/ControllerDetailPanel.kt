package com.sonarwhale.toolwindow

import com.intellij.openapi.project.Project
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.TagConfig
import com.sonarwhale.script.ScriptLevel
import java.awt.BorderLayout
import javax.swing.JPanel

class ControllerDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stateService = SonarwhaleStateService.getInstance(project)
    private var configPanel: HierarchyConfigPanel? = null

    fun showController(tag: String) {
        val panel = HierarchyConfigPanel(
            project = project,
            config = stateService.getTagConfig(tag).config,
            onSave = { updated ->
                stateService.setTagConfig(TagConfig(tag = tag, config = updated))
            },
            scriptContext = ScriptContext(level = ScriptLevel.TAG, tag = tag)
        )
        configPanel?.let { remove(it) }
        configPanel = panel
        add(panel, BorderLayout.CENTER)
        revalidate(); repaint()
    }
}
